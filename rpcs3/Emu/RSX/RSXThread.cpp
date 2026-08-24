#include "stdafx.h"
#include "RSXThread.h"

#include "Capture/rsx_capture.h"
#include "Common/surface_store.h"
#include "Core/RSXReservationLock.hpp"
#include "Core/RSXEngLock.hpp"
#include "Host/MM.h"
#include "Host/RSXDMAWriter.h"
#include "NV47/HW/context.h"
#include "Program/GLSLCommon.h"
#include "rsx_methods.h"
#include "rsx_profiler.h"

#include "gcm_printing.h"
#include "RSXDisAsm.h"

#ifdef __ANDROID__
#include <unistd.h> // ::gettid() for the ADPF feed
#endif

#include "Emu/Memory/vm_reservation.h"
#include "Emu/System.h"
#include "Emu/system_utils.hpp"
#include "Emu/Cell/PPUThread.h"
#include "Emu/Cell/PPUDisAsm.h"
#include "Emu/Cell/SPUDisAsm.h"
#include "Emu/Cell/SPUThread.h"
#include "Emu/Cell/timers.hpp"
#include "Emu/Cell/lv2/sys_event.h"
#include "Emu/Cell/lv2/sys_spu.h"
#include "Emu/Cell/lv2/sys_time.h"
#include "Emu/Cell/Modules/cellGcmSys.h"
#include "util/serialization_ext.hpp"
#include "Overlays/overlay_perf_metrics.h"
#include "Overlays/overlay_debug_overlay.h"
#include "Overlays/overlay_manager.h"
#include "Overlays/overlay_message.h"
#include "Emu/system_progress.hpp"

#include "Utilities/date_time.h"

#include "util/asm.hpp"

#include <span>
#include <thread>
#include <unordered_set>

class GSRender;

#define CMD_DEBUG 0

atomic_t<bool> g_user_asked_for_recording = false;
atomic_t<bool> g_user_asked_for_screenshot = false;
atomic_t<bool> g_user_asked_for_frame_capture = false;
atomic_t<bool> g_disable_frame_limit = false;
rsx::frame_trace_data frame_debug;
rsx::frame_capture_data frame_capture;

extern CellGcmOffsetTable offsetTable;
extern thread_local std::string(*g_tls_log_prefix)();
extern atomic_t<u32> g_lv2_preempts_taken;

LOG_CHANNEL(perf_log, "PERF");

template <>
bool serialize<rsx::rsx_state>(utils::serial& ar, rsx::rsx_state& o)
{
	ar(o.transform_program);

	// Work around for old RSX captures.
	// RSX capture and savestates both call this method.
	// We do not want to grab transform constants if it is not savestate capture.
	const bool is_savestate_capture = thread_ctrl::get_current() && thread_ctrl::get_name() == "Emu State Capture Thread";
	if (GET_SERIALIZATION_VERSION(global_version) || is_savestate_capture)
	{
		ar(o.transform_constants);
	}

	return ar(o.registers);
}

template <>
bool serialize<rsx::frame_capture_data>(utils::serial& ar, rsx::frame_capture_data& o)
{
	ar(o.magic, o.version, o.LE_format);

	if (o.magic != rsx::c_fc_magic || o.version != rsx::c_fc_version || o.LE_format != u32{std::endian::little == std::endian::native})
	{
		return false;
	}

	return ar(o.tile_map, o.memory_map, o.memory_data_map, o.display_buffers_map, o.replay_commands, o.reg_state);
}

template <>
bool serialize<rsx::frame_capture_data::memory_block_data>(utils::serial& ar, rsx::frame_capture_data::memory_block_data& o)
{
	return ar(o.data);
}

template <>
bool serialize<rsx::frame_capture_data::replay_command>(utils::serial& ar, rsx::frame_capture_data::replay_command& o)
{
	return ar(o.rsx_command, o.memory_state, o.tile_state, o.display_buffer_state);
}

template <>
bool serialize<rsx::rsx_iomap_table>(utils::serial& ar, rsx::rsx_iomap_table& o)
{
	// We do not need more than that
	ar(std::span(o.ea.data(), 512));

	if (!ar.is_writing())
	{
		// Populate o.io
		for (const atomic_t<u32>& ea_addr : o.ea)
		{
			const u32& addr = ea_addr.raw();

			if (addr != umax)
			{
				o.io[addr >> 20].raw() = static_cast<u32>(&ea_addr - o.ea.data()) << 20;
			}
		}
	}

	return true;
}

namespace rsx
{
	std::function<bool(u32 addr, bool is_writing)> g_access_violation_handler;

	// TODO: Proper context manager
	static rsx::context s_ctx{ .rsxthr = nullptr, .register_state = &method_registers };

	rsx_iomap_table::rsx_iomap_table() noexcept
		: ea(fill_array(-1))
		, io(fill_array(-1))
	{
	}

	u32 get_address(u32 offset, u32 location, u32 size_to_check, std::source_location src_loc)
	{
		const auto render = get_current_renderer();
		std::string_view msg;

		switch (location)
		{
		case CELL_GCM_CONTEXT_DMA_MEMORY_FRAME_BUFFER:
		case CELL_GCM_LOCATION_LOCAL:
		{
			if (offset < render->local_mem_size && render->local_mem_size - offset >= size_to_check)
			{
				return rsx::constants::local_mem_base + offset;
			}

			msg = "Local RSX offset out of range!"sv;
			break;
		}

		case CELL_GCM_CONTEXT_DMA_MEMORY_HOST_BUFFER:
		case CELL_GCM_LOCATION_MAIN:
		{
			if (const u32 ea = render->iomap_table.get_addr(offset); ea != umax)
			{
				if (size_to_check <= 1 || (offset < render->main_mem_size && render->main_mem_size - offset >= size_to_check))
				{
					bool ok = true;

					for (u32 offs_index = 0x100000; offs_index < size_to_check + (offset & 0xfffff); offs_index += 0x100000)
					{
						// This check does not check continuity but rather that it's mapped at all
						if (render->iomap_table.get_addr(offset + offs_index) == umax)
						{
							ok = false;
						}
					}

					if (ok)
					{
						if (!size_to_check || vm::check_addr(ea, 0, size_to_check))
						{
							return ea;
						}
					}
				}
			}

			msg = "RSXIO memory not mapped!"sv;
			break;
		}

		case CELL_GCM_CONTEXT_DMA_REPORT_LOCATION_LOCAL:
		{
			if (offset < sizeof(RsxReports::report) /*&& (offset % 0x10) == 0*/)
			{
				return render->label_addr + ::offset32(&RsxReports::report) + offset;
			}

			msg = "Local RSX REPORT offset out of range!"sv;
			break;
		}

		case CELL_GCM_CONTEXT_DMA_REPORT_LOCATION_MAIN:
		{
			if (const u32 ea = offset < 0x1000000 ? render->iomap_table.get_addr(0x0e000000 + offset) : -1; ea != umax)
			{
				if (!size_to_check || vm::check_addr(ea, 0, size_to_check))
				{
					return ea;
				}
			}

			msg = "RSXIO REPORT memory not mapped!"sv;
			break;
		}

		// They are handled elsewhere for targeted methods, so it's unexpected for them to be passed here
		case CELL_GCM_CONTEXT_DMA_TO_MEMORY_GET_NOTIFY0:
		case CELL_GCM_CONTEXT_DMA_TO_MEMORY_GET_NOTIFY1:
		case CELL_GCM_CONTEXT_DMA_TO_MEMORY_GET_NOTIFY2:
		case CELL_GCM_CONTEXT_DMA_TO_MEMORY_GET_NOTIFY3:
		case CELL_GCM_CONTEXT_DMA_TO_MEMORY_GET_NOTIFY4:
		case CELL_GCM_CONTEXT_DMA_TO_MEMORY_GET_NOTIFY5:
		case CELL_GCM_CONTEXT_DMA_TO_MEMORY_GET_NOTIFY6:
		case CELL_GCM_CONTEXT_DMA_TO_MEMORY_GET_NOTIFY7:
			msg = "CELL_GCM_CONTEXT_DMA_TO_MEMORY_GET_NOTIFYx"sv; break;

		case CELL_GCM_CONTEXT_DMA_NOTIFY_MAIN_0:
		case CELL_GCM_CONTEXT_DMA_NOTIFY_MAIN_1:
		case CELL_GCM_CONTEXT_DMA_NOTIFY_MAIN_2:
		case CELL_GCM_CONTEXT_DMA_NOTIFY_MAIN_3:
		case CELL_GCM_CONTEXT_DMA_NOTIFY_MAIN_4:
		case CELL_GCM_CONTEXT_DMA_NOTIFY_MAIN_5:
		case CELL_GCM_CONTEXT_DMA_NOTIFY_MAIN_6:
		case CELL_GCM_CONTEXT_DMA_NOTIFY_MAIN_7:
			msg = "CELL_GCM_CONTEXT_DMA_NOTIFY_MAIN_x"sv; break;

		case CELL_GCM_CONTEXT_DMA_SEMAPHORE_RW:
		case CELL_GCM_CONTEXT_DMA_SEMAPHORE_R:
		{
			if (offset < sizeof(RsxReports::semaphore) /*&& (offset % 0x10) == 0*/)
			{
				return render->label_addr + offset;
			}

			msg = "DMA SEMAPHORE offset out of range!"sv;
			break;
		}

		case CELL_GCM_CONTEXT_DMA_DEVICE_RW:
		case CELL_GCM_CONTEXT_DMA_DEVICE_R:
		{
			if (offset < 0x100000 /*&& (offset % 0x10) == 0*/)
			{
				return render->device_addr + offset;
			}

			// TODO: What happens here? It could wrap around or access other segments of rsx internal memory etc
			// Or can simply throw access violation error
			msg = "DMA DEVICE offset out of range!"sv;
			break;
		}

		default:
		{
			msg = "Invalid location!"sv;
			break;
		}
		}

		if (size_to_check)
		{
			// Allow failure if specified size
			// This is to allow accurate recovery for failures
			rsx_log.warning("rsx::get_address(offset=0x%x, location=0x%x, size=0x%x): %s%s", offset, location, size_to_check, msg, src_loc);
			return 0;
		}

		fmt::throw_exception("rsx::get_address(offset=0x%x, location=0x%x): %s%s", offset, location, msg, src_loc);
	}

	extern void set_rsx_yield_flag() noexcept
	{
		if (auto rsx = get_current_renderer())
		{
			if (g_cfg.core.allow_rsx_cpu_preempt)
			{
				rsx->state += cpu_flag::yield;
			}
		}
	}

	extern void set_native_ui_flip()
	{
		if (auto rsxthr = rsx::get_current_renderer())
		{
			rsxthr->async_flip_requested |= rsx::thread::flip_request::native_ui;
		}
	}

	std::pair<u32, u32> interleaved_range_info::calculate_required_range(u32 first, u32 count)
	{
		if (vertex_range.second)
		{
			// Cached result
			return vertex_range;
		}

		if (single_vertex)
		{
			return { 0, 1 };
		}

		const u32 max_index = (first + count) - 1;
		u32 _max_index = 0;
		u32 _min_index = first;

		u32 frequencies[rsx::limits::vertex_count];
		u32 freq_count = rsx::method_registers.current_draw_clause.command == rsx::draw_command::indexed ? 0 : u32{umax};
		u32 max_result_by_division = 0; // Guaranteed maximum

		for (const auto &attrib : locations)
		{
			if (attrib.frequency <= 1) [[likely]]
			{
				freq_count = umax;
				_max_index = max_index;
			}
			else
			{
				if (attrib.modulo)
				{
					if (max_index >= attrib.frequency)
					{
						// Actually uses the modulo operator
						_min_index = 0;
						_max_index = std::max<u32>(_max_index, attrib.frequency - 1);

						if (max_result_by_division < _max_index)
						{
							if (freq_count != umax)
							{
								if (std::find(frequencies, frequencies + freq_count, attrib.frequency) == frequencies + freq_count)
								{
									frequencies[freq_count++] = attrib.frequency;
								}
							}
						}
					}
					else
					{
						// Same as having no modulo
						_max_index = max_index;
						freq_count = umax;
					}
				}
				else
				{
					// Division operator
					_min_index = std::min(_min_index, first / attrib.frequency);
					_max_index = std::max<u32>(_max_index, utils::aligned_div(max_index, attrib.frequency));

					if (freq_count > 0 && freq_count != umax)
					{
						const u32 max = utils::aligned_div(max_index, attrib.frequency);
						max_result_by_division = std::max<u32>(max_result_by_division, max);

						// Discard lower frequencies because it has been proven that there are indices higher than them
						const usz discard_cnt = frequencies + freq_count - std::remove_if(frequencies, frequencies + freq_count, [&max_result_by_division](u32 freq)
						{
							return freq <= max_result_by_division;
						});

						freq_count -= static_cast<u32>(discard_cnt);
					}
				}
			}
		}

		while (freq_count > 0 && freq_count != umax)
		{
			const rsx::index_array_type index_type = rsx::method_registers.current_draw_clause.is_immediate_draw ?
				rsx::index_array_type::u32 :
				rsx::method_registers.index_type();

			const u32 index_size = index_type == rsx::index_array_type::u32 ? 4 : 2;

			const auto render = rsx::get_current_renderer();

			// If we can access a bit a more memory than required - do it
			// The alternative would be re-iterating again over all of them
			if (get_location(real_offset_address) == CELL_GCM_LOCATION_LOCAL)
			{
				if (utils::add_saturate<u32>(real_offset_address - rsx::constants::local_mem_base, (_max_index + 1) * attribute_stride) <= render->local_mem_size)
				{
					break;
				}
			}
			else if (real_offset_address % 0x100000 + (_max_index + 1) * attribute_stride <= 0x100000)//(vm::check_addr(real_offset_address, vm::page_readable, (_max_index + 1) * attribute_stride))
			{
				break;
			}

			_max_index = 0;

			const auto re_evaluate = [&] <typename T> (const std::byte* ptr, T)
			{
				const u64 restart = rsx::method_registers.restart_index_enabled() ? rsx::method_registers.restart_index() : u64{umax};

				for (u32 _index = first; _index < first + count; _index++)
				{
					const auto value = read_from_ptr_unsafe<be_t<T>>(ptr, _index * sizeof(T));

					if (value == restart)
					{
						continue;
					}

					for (u32 freq_it = 0; freq_it < freq_count; freq_it++)
					{
						const auto res = value % frequencies[freq_it];

						if (res > _max_index)
						{
							_max_index = res;
						}
					}
				}
			};

			const auto element_push_buffer = render->draw_processor()->element_push_buffer();
			if (index_size == 4)
			{
				if (!element_push_buffer.empty()) [[unlikely]]
				{
					// Indices provided via immediate mode
					re_evaluate(reinterpret_cast<const std::byte*>(element_push_buffer.data()), u32{});
				}
				else
				{
					const u32 address = (0 - index_size) & get_address(rsx::method_registers.index_array_address(), rsx::method_registers.index_array_location());
					re_evaluate(vm::get_super_ptr<std::byte>(address), u32{});
				}
			}
			else
			{
				if (!element_push_buffer.empty()) [[unlikely]]
				{
					// Indices provided via immediate mode
					re_evaluate(reinterpret_cast<const std::byte*>(element_push_buffer.data()), u16{});
				}
				else
				{
					const u32 address = (0 - index_size) & get_address(rsx::method_registers.index_array_address(), rsx::method_registers.index_array_location());
					re_evaluate(vm::get_super_ptr<std::byte>(address), u16{});
				}
			}

			break;
		}

		ensure(_max_index >= _min_index);
		vertex_range = { _min_index, (_max_index - _min_index) + 1 };
		return vertex_range;
	}

	u32 get_vertex_type_size_on_host(vertex_base_type type, u32 size)
	{
		switch (type)
		{
		case vertex_base_type::s1:
		case vertex_base_type::s32k:
			switch (size)
			{
			case 1:
			case 2:
			case 4:
				return sizeof(u16) * size;
			case 3:
				return sizeof(u16) * 4;
			default:
				break;
			}
			fmt::throw_exception("Wrong vector size");
		case vertex_base_type::f: return sizeof(f32) * size;
		case vertex_base_type::sf:
			switch (size)
			{
			case 1:
			case 2:
			case 4:
				return sizeof(f16) * size;
			case 3:
				return sizeof(f16) * 4;
			default:
				break;
			}
			fmt::throw_exception("Wrong vector size");
		case vertex_base_type::ub:
			switch (size)
			{
			case 1:
			case 2:
			case 4:
				return sizeof(u8) * size;
			case 3:
				return sizeof(u8) * 4;
			default:
				break;
			}
			fmt::throw_exception("Wrong vector size");
		case vertex_base_type::cmp: return 4;
		case vertex_base_type::ub256: ensure(size == 4); return sizeof(u8) * 4;
		default:
			break;
		}
		fmt::throw_exception("Bad vertex data type (%d)!", static_cast<u8>(type));
	}

	void tiled_region::write(const void *src, u32 width, u32 height, u32 pitch)
	{
		if (!tile)
		{
			memcpy(ptr, src, height * pitch);
			return;
		}

		const u32 offset_x = base % tile->pitch;
		const u32 offset_y = base / tile->pitch;

		switch (tile->comp)
		{
		case CELL_GCM_COMPMODE_C32_2X1:
		case CELL_GCM_COMPMODE_DISABLED:
			for (u32 y = 0; y < height; ++y)
			{
				memcpy(ptr + (offset_y + y) * tile->pitch + offset_x, static_cast<const u8*>(src) + pitch * y, pitch);
			}
			break;
			/*
		case CELL_GCM_COMPMODE_C32_2X1:
			for (u32 y = 0; y < height; ++y)
			{
				const u32* src_line = reinterpret_cast<const u32*>(static_cast<const u8*>(src) + pitch * y);
				u32* dst_line = reinterpret_cast<u32*>(ptr + (offset_y + y) * tile->pitch + offset_x);

				for (u32 x = 0; x < width; ++x)
				{
					u32 value = src_line[x];

					dst_line[x * 2 + 0] = value;
					dst_line[x * 2 + 1] = value;
				}
			}
			break;
			*/
		case CELL_GCM_COMPMODE_C32_2X2:
			for (u32 y = 0; y < height; ++y)
			{
				const u32* src_line = reinterpret_cast<const u32*>(static_cast<const u8*>(src) + pitch * y);
				u32* line_0 = reinterpret_cast<u32*>(ptr + (offset_y + y * 2 + 0) * tile->pitch + offset_x);
				u32* line_1 = reinterpret_cast<u32*>(ptr + (offset_y + y * 2 + 1) * tile->pitch + offset_x);

				for (u32 x = 0; x < width; ++x)
				{
					u32 value = src_line[x];

					line_0[x * 2 + 0] = value;
					line_0[x * 2 + 1] = value;
					line_1[x * 2 + 0] = value;
					line_1[x * 2 + 1] = value;
				}
			}
			break;
		default:
			fmt::throw_exception("Bad tile compression type (%d)!", tile->comp);
		}
	}

	void tiled_region::read(void *dst, u32 width, u32 height, u32 pitch)
	{
		if (!tile)
		{
			memcpy(dst, ptr, height * pitch);
			return;
		}

		u32 offset_x = base % tile->pitch;
		u32 offset_y = base / tile->pitch;

		switch (tile->comp)
		{
		case CELL_GCM_COMPMODE_C32_2X1:
		case CELL_GCM_COMPMODE_DISABLED:
			for (u32 y = 0; y < height; ++y)
			{
				memcpy(static_cast<u8*>(dst) + pitch * y, ptr + (offset_y + y) * tile->pitch + offset_x, pitch);
			}
			break;
			/*
		case CELL_GCM_COMPMODE_C32_2X1:
			for (u32 y = 0; y < height; ++y)
			{
				const u32* src_line = reinterpret_cast<const u32*>(ptr + (offset_y + y) * tile->pitch + offset_x);
				u32* dst_line = reinterpret_cast<u32*>(static_cast<u8*>(dst) + pitch * y);

				for (u32 x = 0; x < width; ++x)
				{
					dst_line[x] = src_line[x * 2 + 0];
				}
			}
			break;
			*/
		case CELL_GCM_COMPMODE_C32_2X2:
			for (u32 y = 0; y < height; ++y)
			{
				const u32* src_line = reinterpret_cast<const u32*>(ptr + (offset_y + y * 2 + 0) * tile->pitch + offset_x);
				u32* dst_line = reinterpret_cast<u32*>(static_cast<u8*>(dst) + pitch * y);

				for (u32 x = 0; x < width; ++x)
				{
					dst_line[x] = src_line[x * 2 + 0];
				}
			}
			break;
		default:
			fmt::throw_exception("Bad tile compression type (%d)!", tile->comp);
		}
	}

	thread::~thread()
	{
		g_access_violation_handler = nullptr;
	}

	void thread::save(utils::serial& ar)
	{
		[[maybe_unused]] const s32 version = GET_OR_USE_SERIALIZATION_VERSION(ar.is_writing(), rsx);

		ar(rsx::method_registers);

		for (auto& v : m_draw_processor.m_vertex_push_buffers)
		{
			ar(v.attr, v.size, v.type, v.vertex_count, v.dword_count, v.data);
		}

		ar(m_draw_processor.m_element_push_buffer, fifo_ret_addr, saved_fifo_ret, zcull_surface_active, m_surface_info, m_depth_surface_info, m_framebuffer_layout);
		ar(dma_address, iomap_table, restore_point, tiles, zculls, display_buffers, display_buffers_count, current_display_buffer);
		ar(enable_second_vhandler, requested_vsync);
		ar(device_addr, label_addr, main_mem_size, local_mem_size, rsx_event_port, driver_info);
		ar(in_begin_end);
		ar(display_buffers, display_buffers_count, current_display_buffer);
		ar(unsent_gcm_events, rsx::method_registers.current_draw_clause);

		if (ar.is_writing() || version >= 2)
		{
			ar(vblank_count);

			b8 flip_pending{};

			if (ar.is_writing())
			{
				flip_pending = !!(async_flip_requested & flip_request::emu_requested);
			}

			ar(flip_pending);

			if (flip_pending)
			{
				ar(vblank_at_flip);
				ar(async_flip_buffer);

				if (!ar.is_writing())
				{
					async_flip_requested |= flip_request::emu_requested;
					flip_notification_count = 1;
				}
			}
		}

		if (ar.is_writing())
		{
			if (fifo_ctrl && state & cpu_flag::again)
			{
				ar(fifo_ctrl->get_remaining_args_count() + 1);
				ar(fifo_ctrl->last_cmd());
			}
			else
			{
				ar(u32{0});
			}
		}
		else if (u32 count{ar})
		{
			restore_fifo_count = count;
			ar(restore_fifo_cmd);
		}
	}

	thread::thread(utils::serial* _ar)
		: cpu_thread(0x5555'5555)
	{
		g_access_violation_handler = [this](u32 address, bool is_writing)
		{
			return on_access_violation(address, is_writing);
		};

		m_textures_dirty.fill(true);
		m_vertex_textures_dirty.fill(true);

		m_graphics_state |= pipeline_state::all_dirty;

		g_user_asked_for_frame_capture = false;

		// TODO: Proper context management in the driver
		s_ctx.rsxthr = this;
		m_ctx = &s_ctx;

		m_draw_processor.init(m_ctx);

		if (g_cfg.misc.use_native_interface && (g_cfg.video.renderer == video_renderer::opengl || g_cfg.video.renderer == video_renderer::vulkan))
		{
			m_overlay_manager = g_fxo->init<rsx::overlays::display_manager>(0);

			if (g_cfg.misc.play_music_during_boot)
			{
				if (const std::string audio_path = Emu.GetSfoDir(true) + "/SND0.AT3"; fs::is_file(audio_path))
				{
					m_overlay_manager->start_audio(audio_path);
				}
			}
		}

		if (!_ar)
		{
			add_remove_flags({}, cpu_flag::stop); // TODO: Remove workaround
			return;
		}

		add_remove_flags(cpu_flag::suspend, cpu_flag::stop);

		serialized = true;
		save(*_ar);

		if (dma_address)
		{
			ctrl = vm::_ptr<RsxDmaControl>(dma_address);
			rsx_thread_running = true;
		}

		if (g_cfg.savestate.start_paused)
		{
			// Allow to render a whole frame within this emulation session so there won't be missing graphics
			m_pause_after_x_flips = 2;
		}
	}

	avconf::avconf(utils::serial& ar)
	{
		save(ar);
	}

	void avconf::save(utils::serial& ar)
	{
		[[maybe_unused]] const s32 version = GET_OR_USE_SERIALIZATION_VERSION(ar.is_writing(), rsx);

		if (!ar.is_writing() && version < 3)
		{
			// Be compatible with previous bitwise serialization
			ar(std::span<u8>(reinterpret_cast<u8*>(this), ::offset32(&avconf::scan_mode)));
			ar.pos += utils::align<usz>(::offset32(&avconf::scan_mode), alignof(avconf)) - ::offset32(&avconf::scan_mode);
			return;
		}

		ar(stereo_enabled, format, aspect, resolution_id, scanline_pitch, gamma, resolution_x, resolution_y, state, scan_mode);
	}

	void thread::capture_frame(const std::string& name) const
	{
		frame_trace_data::draw_state draw_state{};

		draw_state.programs = get_programs();
		draw_state.name = name;
		frame_debug.draw_calls.emplace_back(std::move(draw_state));
	}

	void thread::begin()
	{
		// Backend-independent draw prologue. The read_barrier below is a hard sync on the
		// software conditional render path, so this is not always the trivial flag work it
		// looks like, and unscoped it was billed to draw_setup along with everything else.
		RSX_PROF_SCOPE(draw_prologue);

		if (cond_render_ctrl.hw_cond_active)
		{
			if (!cond_render_ctrl.eval_pending())
			{
				// End conditional rendering if still active
				end_conditional_rendering();
			}

			// If hw cond render is enabled and evalutation is still pending, do nothing
		}
		else if (cond_render_ctrl.eval_pending())
		{
			// Evaluate conditional rendering test or enable hw cond render until results are available
			if (backend_config.supports_hw_conditional_render)
			{
				// In this mode, it is possible to skip the cond render while the backend is still processing data.
				// The backend guarantees that any draw calls emitted during this time will NOT generate any ROP writes
				ensure(!cond_render_ctrl.hw_cond_active);

				// Pending evaluation, use hardware test
				begin_conditional_rendering(cond_render_ctrl.eval_sources);
			}
			else
			{
				// NOTE: eval_sources list is reversed with newest query first
				zcull_ctrl->read_barrier(this, cond_render_ctrl.eval_address, cond_render_ctrl.eval_sources.front());
				ensure(!cond_render_ctrl.eval_pending());
			}
		}

		if (!backend_config.supports_normalized_barycentrics)
		{
			// Check for mode change between rasterized polys vs lines and points
			// Luckily this almost never happens in real games
			const auto current_mode = rsx::method_registers.current_draw_clause.classify_mode();
			if (current_mode != m_current_draw_mode)
			{
				m_graphics_state |= (rsx::vertex_program_state_dirty | rsx::fragment_program_state_dirty);
				m_current_draw_mode = current_mode;
			}
		}

		in_begin_end = true;
	}

	void thread::end()
	{
		// Backend-independent draw epilogue: clause cleanup, push buffer teardown and the
		// ZCULL draw hook, all of which scale with draw count rather than with frame count.
		RSX_PROF_SCOPE(draw_epilogue);

		if (capture_current_frame)
		{
			capture::capture_draw_memory(this);
		}

		in_begin_end = false;
		m_frame_stats.draw_calls++;

		// Counted here rather than at the backend call sites so every early return in
		// VKGSRender::end is included; all of them route through this function.
		if (rsx::prof::enabled()) [[unlikely]]
		{
			rsx::prof::g_draw_calls++;

			if (rsx::prof::g_pass_ordinal < rsx::prof::pass_slot_count)
			{
				rsx::prof::g_pass_draws[rsx::prof::g_pass_ordinal]++;
				rsx::prof::g_pass_vertices[rsx::prof::g_pass_ordinal] +=
					method_registers.current_draw_clause.get_elements_count();
				rsx::prof::g_pass_vp_words[rsx::prof::g_pass_ordinal] +=
					::size32(current_vertex_program.data);
				rsx::prof::g_pass_fp_words[rsx::prof::g_pass_ordinal] +=
					current_fragment_program.ucode_length;
			}
		}

		method_registers.current_draw_clause.post_execute_cleanup(m_ctx);

		m_graphics_state |= rsx::pipeline_state::framebuffer_reads_dirty;
		m_eng_interrupt_mask |= rsx::backend_interrupt;
		ROP_sync_timestamp = rsx::get_shared_tag();

		m_draw_processor.clear_push_buffers();

		zcull_ctrl->on_draw();

		if (capture_current_frame)
		{
			const u32 element_count = rsx::method_registers.current_draw_clause.get_elements_count();
			capture_frame(fmt::format("Draw %s %d", rsx::method_registers.current_draw_clause.primitive, element_count));
		}
	}

	void thread::execute_nop_draw()
	{
		method_registers.current_draw_clause.begin();
		do
		{
			method_registers.current_draw_clause.execute_pipeline_dependencies(m_ctx);
		}
		while (method_registers.current_draw_clause.next());
	}

	void thread::cpu_task()
	{
		while (Emu.IsReady())
		{
			Emu.WaitReady();
		}

		do
		{
			on_task();

			state -= cpu_flag::ret;
		}
		while (!is_stopped());

		on_exit();
	}

	void thread::cpu_wait(bs_t<cpu_flag> old)
	{
		if (external_interrupt_lock)
		{
			wait_pause();
		}

		if ((state & (cpu_flag::dbg_global_pause + cpu_flag::exit)) == cpu_flag::dbg_global_pause)
		{
			// Wait 16ms during emulation pause. This reduces cpu load while still giving us the chance to render overlays.
			do_local_task(rsx::FIFO::state::paused);
			thread_ctrl::wait_on(state, old, 16000);
		}
		else
		{
			on_semaphore_acquire_wait();
			std::this_thread::yield();
		}
	}

	void thread::post_vblank_event(u64 post_event_time)
	{
		vblank_count++;

		if (isHLE)
		{
			if (auto ptr = vblank_handler)
			{
				intr_thread->cmd_list
				({
					{ ppu_cmd::set_args, 1 }, u64{1},
					{ ppu_cmd::lle_call, ptr },
					{ ppu_cmd::sleep, 0 }
				});

				intr_thread->cmd_notify.store(1);
				intr_thread->cmd_notify.notify_one();
			}
		}
		else
		{
			sys_rsx_context_attribute(0x55555555, 0xFED, 1, get_guest_system_time(post_event_time), 0, 0);
		}
	}

	namespace nv4097
	{
		void set_render_mode(context* rsx, u32, u32 arg);
	}

	void thread::on_task()
	{
		g_tls_log_prefix = []
		{
			const auto rsx = get_current_renderer();
			return fmt::format("RSX [0x%07x]", rsx->ctrl ? +rsx->ctrl->get : 0);
		};

		if (!serialized) method_registers.init();

		rsx::overlays::reset_performance_overlay();
		rsx::overlays::reset_debug_overlay();

		if (!is_initialized)
		{
			g_fxo->get<rsx::dma_manager>().init();
			on_init_thread();

			if (in_begin_end)
			{
				// on_init_thread should have prepared the backend resources
				// Run draw call warmup again if the savestate happened mid-draw
				ensure(serialized);
				begin();
			}
		}

		is_initialized = true;
		is_initialized.notify_all();

		if (!zcull_ctrl)
		{
			// Backend did not provide an implementation, provide NULL object
			zcull_ctrl = std::make_unique<::rsx::reports::ZCULL_control>();
		}

		check_zcull_status(false);
		nv4097::set_render_mode(m_ctx, 0, method_registers.registers[NV4097_SET_RENDER_ENABLE]);

		performance_counters.state = FIFO::state::empty;

		const u64 event_flags = unsent_gcm_events.exchange(0);

		if (Emu.IsStarting())
		{
			Emu.CallFromMainThread([]
			{
				Emu.RunPPU();
			});
		}

		// Wait for startup (TODO)
		while (!rsx_thread_running || Emu.IsPausedOrReady())
		{
			// Execute backend-local tasks first
			do_local_task(performance_counters.state);

			// Update sub-units
			zcull_ctrl->update(this);

			if (is_stopped())
			{
				return;
			}

			thread_ctrl::wait_for(1000);
		}

		performance_counters.state = FIFO::state::running;

		fifo_ctrl = std::make_unique<::rsx::FIFO::FIFO_control>(this);
		fifo_ctrl->set_get(ctrl->get);

		resolution_scaling_config =
		{
			.scale_percent = static_cast<u16>(g_cfg.video.resolution_scale_percent),
			.min_scalable_dimension = static_cast<u16>(g_cfg.video.min_scalable_dimension),
		};

		last_guest_flip_timestamp = get_system_time() - 1000000;

		vblank_count = 0;

		if (restore_fifo_count)
		{
			fifo_ctrl->restore_state(restore_fifo_cmd, restore_fifo_count);
		}

		if (!send_event(0, event_flags, 0))
		{
			return;
		}

		g_fxo->get<vblank_thread>().set_thread(std::shared_ptr<named_thread<std::function<void()>>>(new named_thread<std::function<void()>>("VBlank Thread"sv, [this]() -> void
		{
#ifdef __linux__
			constexpr u32 host_min_quantum = 10;
#else
			constexpr u32 host_min_quantum = 500;
#endif
			u64 start_time = get_system_time();

			u64 vblank_rate = g_cfg.video.vblank_rate;
			u64 vblank_period = 1'000'000 + u64{g_cfg.video.vblank_ntsc.get()} * 1000;

			u64 local_vblank_count = 0;

			// TODO: exit condition
			u64 last_heartbeat = 0;
			u64 iterations = 0;

			while (!is_stopped() && !unsent_gcm_events && thread_ctrl::state() != thread_state::aborting)
			{
				// Get current time
				const u64 current = get_system_time();

				// Heartbeat. This thread is the only source of the interrupt gcm waits on, so
				// when it stops the guest hangs after gcm init with everything else looking
				// idle -- and it said nothing either way. Distinguishes "still looping" from
				// "blocked inside post_vblank_event" from "left the loop", which need
				// different fixes and are indistinguishable from outside.
				if (current - last_heartbeat >= 5'000'000)
				{
					last_heartbeat = current;
					rsx_log.notice("VBlank: alive, iterations=%u, vblank_count=%u", iterations, local_vblank_count);
				}

				iterations++;

				// Calculate the time at which we need to send a new VBLANK signal
				const u64 post_event_time = start_time + (local_vblank_count + 1) * vblank_period / vblank_rate;

				// Calculate time remaining to that time (0 if we passed it)
				const u64 wait_for = current >= post_event_time ? 0 : post_event_time - current;

#ifdef __linux__
				const u64 wait_sleep = wait_for;
#else
				// Substract host operating system min sleep quantom to get sleep time
				const u64 wait_sleep = wait_for - u64{wait_for >= host_min_quantum} * host_min_quantum;
#endif

				if (!wait_for)
				{
					{
						local_vblank_count++;

						if (local_vblank_count == vblank_rate)
						{
							// Advance start_time to the moment of the current VBLANK
							// Which is the last VBLANK event in this period
							// This is in order for multiplication by ratio above to use only small numbers
							start_time += vblank_period;
							local_vblank_count = 0;

							// We have a rare chance to update settings without losing precision whenever local_vblank_count is 0
							vblank_rate = g_cfg.video.vblank_rate;
							vblank_period = 1'000'000 + u64{g_cfg.video.vblank_ntsc.get()} * 1000;
						}

						post_vblank_event(post_event_time);
					}
				}
				else if (wait_sleep)
				{
					thread_ctrl::wait_for(wait_sleep);
				}
				else if (wait_for >= host_min_quantum / 3 * 2)
				{
					std::this_thread::yield();
				}

				if (Emu.IsPaused())
				{
					// Save the difference before pause
					start_time = get_system_time() - start_time;

					while (Emu.IsPaused() && !is_stopped())
					{
						thread_ctrl::wait_for(5'000);
					}

					// Restore difference
					start_time = get_system_time() - start_time;
				}
			}

			// Which condition ended it. unsent_gcm_events is the savestate hand-off, so seeing
			// it here outside a savestate means a live send failed and took the vblank source
			// down with it -- permanently, since nothing restarts this thread.
			rsx_log.error("VBlank: loop exited after %u iterations (stopped=%d, unsent_gcm_events=0x%x, aborting=%d)",
				iterations, is_stopped() ? 1 : 0, unsent_gcm_events.load(),
				thread_ctrl::state() == thread_state::aborting ? 1 : 0);
		})));

		struct join_vblank
		{
			~join_vblank() noexcept
			{
				g_fxo->get<vblank_thread>() = thread_state::finished;
			}

		} join_vblank_obj{};

		// Raise priority above other threads
		thread_ctrl::scoped_priority high_prio(+1);

		if (g_cfg.core.thread_scheduler != thread_scheduler_mode::os)
		{
			thread_ctrl::set_thread_affinity_mask(thread_ctrl::get_affinity_mask(thread_class::rsx));
		}

		if (auto manager = g_fxo->try_get<rsx::overlays::display_manager>())
		{
			manager->stop_audio();
		}

		// Scoped around the whole loop, not around run_FIFO.
		//
		// run_FIFO executes a single FIFO command, so a scope inside it ran tens of thousands
		// of times per frame, each reading the counter-timer twice. On ARM that is an mrs
		// cntvct_el0: expensive and partially serialising. run_FIFO's share of RSX thread
		// samples went from about 3% uninstrumented to 35% with the scope inside it, so the
		// bucket had become mostly a measurement of itself.
		//
		// Entered once here instead. Attribution is exclusive, so every nested scope still
		// carves out its own time and returns here afterwards, leaving this bucket meaning
		// "RSX loop time nothing more specific claimed", which is what was wanted from it,
		// at no per-command cost.
		RSX_PROF_SCOPE(fifo_decode);

		while (!test_stopped())
		{
			// Wait for external pause events
			if (external_interrupt_lock)
			{
				wait_pause();

				if (!rsx_thread_running)
				{
					return;
				}
			}

			// Note a possible rollback address
			if (sync_point_request && !in_begin_end)
			{
				restore_point = ctrl->get;
				saved_fifo_ret = fifo_ret_addr;
				sync_point_request.release(false);
			}

			// Update sub-units every 64 cycles. The local handler is invoked for other functions externally on-demand anyway.
			// This avoids expensive calls to check timestamps which involves reading some values from TLS storage on windows.
			// If something is going on in the backend that requires an update, set the interrupt bit explicitly.
			if (rsx::prof::enabled()) [[unlikely]] rsx::prof::g_fifo_commands++;

			if ((m_cycles_counter++ & 63) == 0 || m_eng_interrupt_mask)
			{
				// Execute backend-local tasks first
				//
				// These two run once per 64 FIFO commands rather than per command, so
				// scoping them costs a counter read every 64th iteration instead of the
				// per-command cost that made an earlier attempt measure mostly itself.
				//
				// Split out because fifo_decode holds 38ms of a 54ms frame in Arkham City
				// and nothing else accounts for it: the whole draw path is under 5ms and
				// page protection turned out to be 0.09ms. ZCULL is a live suspect here in
				// particular, since this title trips "Reports area at location
				// CELL_GCM_LOCATION_MAIN was accessed, ZCULL optimizations will be
				// disabled" and then runs the unoptimised path for the entire session.
				{
					RSX_PROF_SCOPE(local_task);
					do_local_task(performance_counters.state);
				}

				// Update other sub-units
				{
					RSX_PROF_SCOPE(zcull);
					zcull_ctrl->update(this);
				}

				if (m_host_dma_ctrl)
				{
					m_host_dma_ctrl->update();
				}
			}

			// Execute FIFO queue
			run_FIFO();
		}
	}

	void thread::on_exit()
	{
		if (zcull_ctrl)
		{
			zcull_ctrl->sync(this);
		}

		// Deregister violation handler
		g_access_violation_handler = nullptr;

		// Clear any pending flush requests to release threads
		std::this_thread::sleep_for(10ms);
		do_local_task(rsx::FIFO::state::lock_wait);

		g_fxo->get<rsx::dma_manager>().join();
		g_fxo->get<vblank_thread>() = thread_state::finished;
		state += cpu_flag::exit;
	}

	u64 thread::timestamp()
	{
		const u64 freq = sys_time_get_timebase_frequency();

		auto get_time_ns = [freq]()
		{
			const u64 t = get_timebased_time();
			return (t / freq * 1'000'000'000 + t % freq * 1'000'000'000 / freq);
		};

		const u64 t = get_time_ns();
		if (t != timestamp_ctrl)
		{
			timestamp_ctrl = t;
			timestamp_subvalue = 0;
			return t;
		}

		// Check if we passed the limit of what fixed increments is legal for
		// Wait for the next time value reported if we passed the limit
		if ((1'000'000'000 / freq) - timestamp_subvalue <= 2)
		{
			u64 now = get_time_ns();

			for (; t == now; now = get_time_ns())
			{
				utils::pause();
			}

			timestamp_ctrl = now;
			timestamp_subvalue = 0;
			return now;
		}

		timestamp_subvalue += 2;
		return t + timestamp_subvalue;
	}

	// Frame-stall notice. See check_frame_stall.
	static atomic_t<u64> g_last_frame_time{0};
	static atomic_t<bool> g_frame_stall_reported{false};

	// How many guest-thread dumps this stall has produced. See check_frame_stall.
	static atomic_t<u32> g_frame_stall_dumps{0};

	// Defined below; check_frame_stall is what decides a stall has happened.
	static void dump_guest_threads_stalled();

	// Say when the picture has stopped, instead of leaving the last frame standing.
	//
	// A guest that stops progressing presents nothing further, so whatever was last drawn stays
	// on screen indefinitely. When that frame happens to contain the boot progress bar, it reads
	// as "stuck compiling at 1s remaining" -- and it looked exactly the same across five
	// unrelated faults, sending every report of them to the wrong place. Nothing contradicts it
	// either: the emulator has not crashed, so there is no error to be found.
	//
	// Only fires while nothing is legitimately in progress. A shader or PPU compile presents no
	// frames for minutes at a time, and it holds a progress dialog that says as much, so an
	// empty progress text is what separates "working, quietly" from "stopped".
	// What the RSX thread is doing while it fails to present.
	//
	// The profiler can say the thread is in 'Local task' and that it has been in it for 0.00s,
	// which together mean it is not stuck in there at all -- do_local_task has no loop and
	// returns immediately. The FIFO loop is calling it over and over, and Borderlands 2 has the
	// RSX thread at ~113% of a core doing that while no frame lands for 45s+. Which FIFO state
	// it is called with separates the cases and is recorded nowhere else: 'empty' means the
	// guest is not submitting (a guest-side stall the RSX only reflects), 'spinning' means the
	// puller is jumping to itself waiting on a semaphore, 'lock_wait' means it is parked on a
	// lock acquire. Counters only, dumped from the existing 5s stall report -- logging per call
	// would be thousands of lines a second, which on Android is itself a stall.
	static constexpr u32 s_ltask_states = 6;
	static constexpr u32 s_ltask_branches = 4;

	static u64 g_ltask_state_calls[s_ltask_states]{};
	static u64 g_ltask_state_prev[s_ltask_states]{};
	static u64 g_ltask_branch[s_ltask_branches]{};
	static u64 g_ltask_branch_prev[s_ltask_branches]{};
	static u64 g_ltask_last_report = 0;

	enum ltask_branch : u32
	{
		ltask_emu_flip = 0,
		ltask_invalidate = 1,
		ltask_dma_control = 2,
		ltask_pipe_flush = 3,
	};

	static void check_frame_stall()
	{
		const u64 now = get_system_time();

		// Something is reporting progress, or no frame has ever landed yet: not a stall.
		if (g_progr_text || !g_last_frame_time)
		{
			g_last_frame_time = now;
			g_frame_stall_reported = false;
			g_frame_stall_dumps = 0;
			return;
		}

		const u64 since = now - g_last_frame_time;

		if (since < 30'000'000)
		{
			return;
		}

		if (!g_frame_stall_reported)
		{
			g_frame_stall_reported = true;

			rsx_log.error("No frame presented in %us with nothing in progress: the game has stopped.",
				since / 1'000'000);

			// Draw it, rather than logging into a file nobody has when they file the report. The
			// native UI flip is what gets it on screen at all -- the guest is not flipping, which is
			// the whole point.
			rsx::overlays::queue_message(
				std::string("Game has stopped responding - it is no longer drawing frames"),
				10'000'000);

			set_native_ui_flip();
		}

		// Say WHERE the guest is parked, not merely that it is.
		//
		// dump_guest_threads_stalled() already existed and is thorough, but the only thing that
		// called it was the RSX profiler's poll_stall() -- which returns false immediately unless
		// the profiler is switched on, and testers do not switch it on. So every freeze report
		// arrived with this line and nothing behind it: the emulator detected the hang, told the
		// user, and recorded none of what it could see. Confirmed against a Kane & Lynch capture
		// where the guest stopped at 0:04:15 and this fired at 0:04:45 with no dump.
		//
		// Twice, ~15s apart, because one sample cannot distinguish a thread spinning from one
		// making very slow progress -- a cia that has not moved between two samples is itself the
		// finding. Twice and no more: this runs to hundreds of lines per thread, and log volume
		// alone is enough to stall the emulator on Android.
		if (const u32 taken = g_frame_stall_dumps; taken < 2 && since >= 30'000'000 + u64{taken} * 15'000'000)
		{
			g_frame_stall_dumps = taken + 1;
			dump_guest_threads_stalled();
		}
	}

	// Say where every guest thread is parked once frames have stopped arriving.
	//
	// A hang with the RSX idle is a guest-side wait, and nothing named the thread or the place.
	// The syscall stats report sys_timer_usleep without saying who called it, /proc shows a
	// thread that never started as indistinguishable from one that is blocked, and the RSX
	// profiler only covers this side of the boundary. Name, state, PC and the function each
	// PPU is in separate all of those.
	//
	// idm::unlocked deliberately: this runs on the RSX thread, and taking the id lock here to
	// diagnose a hang would add exactly the kind of dependency being diagnosed. A torn read of
	// a diagnostic line costs nothing.
	static void dump_guest_threads_stalled()
	{
		std::string out;

		idm::select<named_thread<ppu_thread>>([&out](u32 id, ppu_thread& ppu)
		{
			const auto func = ppu.current_function ? ppu.current_function : ppu.last_function;

			fmt::append(out, "\n  PPU 0x%07x '%s': state=%s cia=0x%08x %s func='%s'",
				id, *ppu.ppu_tname.load(), ppu.state.load(), ppu.cia,
				ppu.current_function ? "in" : "last", func ? func : "");

			// Who called the wait, not just where it is parked.
			//
			// Borderlands 2's main_thread blocks in sys_event_queue_receive at cia=0x01b85e5c,
			// and an instrumented x86 build blocks at the SAME libsre address -- but on a
			// different queue: x86 gets 0x8d00c200 (CompPatch group, spup 17, which the SPUs
			// signal constantly) and ARM gets 0x8d021700 (PhysWISE group, spup 20, which nothing
			// ever signals). Same code, different queue handle, so the handle was chosen further
			// up. cia cannot say by whom; the call stack can, and it names the cellSpurs* entry
			// point that picked the instance.
			if (const std::string trace = ppu.dump_callstack(); !trace.empty())
			{
				fmt::append(out, "%s", trace);
			}
		}, idm::unlocked);

		rsx_log.error("Guest PPU threads while no frame has completed:%s", out);

		// Once per session, follow the summary with everything each PPU can say about itself:
		// registers, the guest call stack, and -- when "PPU Calling History" is enabled -- the
		// last guest calls and HLE/LV2 calls it made.
		//
		// The summary above repeats every few seconds deliberately, because a cia that does not
		// move between samples is itself the finding. This part must NOT repeat: it runs to
		// hundreds of lines per thread, and log volume alone is enough to stall the emulator on
		// Android, which is a failure mode we have already shipped once.
		//
		// The case it exists for is a thread reported as 'state=00[]' with a static cia and a
		// core pegged at 100%: that is a guest busy-loop, and the one-line summary cannot say
		// what the loop is waiting on. The call stack names the caller chain that entered it,
		// and the registers hold whatever it keeps re-testing -- which together identify the
		// loop in the executable. Reported against Saint Seiya: The Sanctuary (BLES01421,
		// issue #25), which parks its main thread at cia=0x000bc7d0 forever, right after
		// _sys_lwmutex_create and before it creates a single thread or submits a single frame.
		//
		// Wait for PPU/SPU compilation to finish before spending the one shot.
		//
		// No frame is presented while modules are compiling either, so the very first stall of
		// every session is the compile itself -- six minutes of it on a cold cache. Dumping there
		// burns the one-shot on a thread that has not run a single guest instruction: every GPR
		// reads zero and the call stack is empty, which is exactly what happened the first time
		// this shipped. Neither "has a frame ever been presented" nor a plain time threshold
		// separates the two cases, because the hang being chased also never presents a frame and
		// also lasts forever. Outstanding progress work does.
		//
		// dump_callstack_list validates the stack pointer and every frame with vm::check_addr and
		// gives up rather than walking garbage, so this is safe against a thread that is running
		// and modifying its own stack underneath us. Torn values are acceptable here for the same
		// reason the summary takes them: an approximate answer now beats an exact one never.
		static atomic_t<bool> s_dumped_detail{false};

		const bool compiling = g_progr_ptotal.load() != g_progr_pdone.load();

		if (!compiling && !s_dumped_detail.exchange(true))
		{
			std::string detail;

			// The guest instructions around the stuck cia -- the loop itself.
			//
			// Registers and a call stack say where the thread is and what it holds, but not what
			// the code DOES, and from the outside a two-instruction compare-and-branch-to-self is
			// indistinguishable from a long computation that simply has not finished. Printing the
			// window settles it, and cpu_disasm_mode::dump emits address, opcode bytes and mnemonic
			// per line, so the branch target is readable straight out of the log and can be matched
			// against the guest binary without the debugger UI, which Android does not build.
			//
			// A fixed window rather than the whole function because function bounds are not known
			// on this side, and every address is checked first: cia is read from a thread that is
			// still running and can be stale or outright garbage, and faulting inside the diagnostic
			// that explains a hang would be the worst possible trade.
			PPUDisAsm dis_asm(cpu_disasm_mode::dump, vm::g_sudo_addr);

			idm::select<named_thread<ppu_thread>>([&detail, &dis_asm](u32 id, ppu_thread& ppu)
			{
				fmt::append(detail, "\n=== PPU 0x%07x '%s' ===\n", id, *ppu.ppu_tname.load());
				ppu.dump_all(detail);

				const u32 pc = ppu.cia;
				const u32 from = pc >= 0x20 ? pc - 0x20 : 0;

				// A wide window for a thread that is spinning, a narrow one for a thread that is
				// merely parked in a syscall.
				//
				// Under the recompiler cia is only written at block boundaries, so on a spinning
				// thread it names the ENTRY of the function that never returned, not the loop
				// inside it -- and a handful of instructions from the entry is just the prologue.
				// Reading the whole body is the point: the question being answered is which PPU
				// instructions the function uses, because the ARM64 backend only diverges from the
				// portable path on a few of them (VCFUX, VMAXFP, VMINFP, VPERM) and seeing one of
				// those in a function that hangs is what turns a guess into a candidate.
				//
				// Threads blocked in sys_* are not the suspects and there can be a dozen of them,
				// so they keep the short window. Their cia is in liblv2 anyway.
				const bool spinning = ppu.state.none_of(cpu_flag::wait);
				const u32 span = spinning ? 0x600 : 0x40;

				// What the registers POINT AT, for a thread spinning in guest code.
				//
				// dump_all prints 8 bytes behind each GPR, which is enough to recognise a
				// pointer and not enough to read the structure it points to. Assassin's Creed
				// needs byte 0x74 of the SPURS job chain -- the workloadId the guest tests
				// before deciding a chain is usable -- and that is 0x74 bytes past a value
				// sitting in r5. Every fact this hunt has turned on so far came from a struct
				// field just out of reach of the 8-byte preview.
				//
				// Spinning threads only, deduplicated, capped: a dozen parked threads each
				// dragging 0x80 bytes per register would bury the dump that explains the hang.
				if (spinning)
				{
					std::vector<u32> seen;

					for (u32 i = 3; i < 32 && seen.size() < 6; i++)
					{
						const u32 ptr = static_cast<u32>(ppu.gpr[i]);

						// Aligned, mapped, and not already printed. The alignment test is what
						// keeps counters and small integers out of it.
						if (!ptr || (ptr & 0xf) || !vm::check_addr(ptr, vm::page_readable, 0x80))
						{
							continue;
						}

						if (std::find(seen.begin(), seen.end(), ptr) != seen.end())
						{
							continue;
						}

						seen.push_back(ptr);

						fmt::append(detail, "\n[r%u] 0x%08x:", i, ptr);

						for (u32 off = 0; off < 0x80; off += 16)
						{
							fmt::append(detail, "\n  +0x%02x ", off);
							for (u32 b = 0; b < 16; b++)
							{
								fmt::append(detail, "%02x ", vm::read8(ptr + off + b));
							}
						}
					}

					detail += "\n";
				}

				fmt::append(detail, "\nCode around cia=0x%08x (%s):\n", pc,
					spinning ? "spinning, wide window" : "waiting, short window");

				for (u32 addr = from; addr <= from + span; addr += 4)
				{
					if (!vm::check_addr(addr))
					{
						continue;
					}

					dis_asm.disasm(addr);

					// Mark the instruction the thread is actually parked on, so the loop can be
					// read off without counting lines.
					detail += (addr == pc ? "  >>" : "    ");
					detail += dis_asm.last_opcode;
				}

				// The callers, which is where a control-flow divergence actually lives.
				//
				// cia only says where a thread is parked, and for anything blocked in an lv2 wait
				// that is an address inside liblv2 or libsre -- the same address on every host, so
				// it can never show a divergence. Borderlands 2 proves the point: ARM and an
				// instrumented x86 build both park main_thread at 0x01b85e5c in libsre, both reach
				// it through the cellSpursEventFlagWait import thunk at 0x011e785c, and both share
				// an identical outer stack down to 0x001f59a4 -- then ARM calls straight into the
				// wait via 0x000c6958 while x86 descends six further frames via 0x000c4b8c. The
				// branch that picks between those two paths is the bug, and it is only visible by
				// disassembling around the RETURN ADDRESSES, not around cia.
				//
				// Four frames: enough to cross the import thunk and reach guest code on either
				// path, short enough that a dozen parked threads do not bury the log.
				const auto frames = ppu.dump_callstack_list();

				for (u32 i = 0; i < 4 && i < frames.size(); i++)
				{
					const u32 ret = frames[i].first;

					if (!ret || !vm::check_addr(ret))
					{
						continue;
					}

					// The call is the instruction BEFORE the return address, so start behind it.
					const u32 caller_from = ret >= 0x18 ? ret - 0x18 : 0;

					fmt::append(detail, "\nCaller frame %u, code around return 0x%08x:\n", i, ret);

					for (u32 addr = caller_from; addr <= ret + 0x8; addr += 4)
					{
						if (!vm::check_addr(addr))
						{
							continue;
						}

						dis_asm.disasm(addr);
						detail += (addr == ret ? "  >>" : "    ");
						detail += dis_asm.last_opcode;
					}
				}
			}, idm::unlocked);

			rsx_log.error("Stalled guest thread detail (reported once per session):%s", detail);
		}

		// The SPU half. A hang where every PPU is asleep and the SPUs are burning user time is
		// the SPUs spinning in guest code, and nothing said WHICH code: /proc gives a tick count,
		// a CPU profile gives a JIT address that resolves to nothing. The PC plus the block hash
		// name the guest block, which is the only thing that identifies the loop.
		std::string spus;

		// One detailed kernel dump per report, not per SPU -- see the note at the use site.
		bool spu_detail_done = false;

		// WHICH SPU gets that dump, decided before walking them.
		//
		// It used to be whichever came first with raddr == spurs_addr -- a kernel waiting on its
		// own control block, i.e. an IDLE one -- on the assumption that every kernel parks at the
		// same pc so any of them would do. Tales of Xillia is the counter-example: one graphics
		// kernel executed a guest HALT and sits stopped at pc=0x00f00 while the other four idle
		// normally at 0x011a8. The old rule picked an idle one, so the only thread in the process
		// that had anything to say printed no registers at all, and the capture could name the
		// failing assertion but none of the values it tested.
		//
		// A stopped or halted SPU wins outright. Nothing else in a SPURS group stops on its own,
		// so if one has, it is the reason the group never joined and the rest are just waiting on
		// it.
		u32 spu_detail_id = 0;
		bool spu_detail_pinned = false;

		idm::select<named_thread<spu_thread>>([&](u32 id, spu_thread& spu)
		{
			const auto st = spu.state.load();

			if (!spu_detail_pinned && !st.none_of(cpu_flag::stop + cpu_flag::dbg_pause + cpu_flag::exit))
			{
				spu_detail_id = id;
				spu_detail_pinned = true;
			}
		}, idm::unlocked);

		idm::select<named_thread<spu_thread>>([&spus, &spu_detail_done, spu_detail_id, spu_detail_pinned](u32 id, spu_thread& spu)
		{
			const auto func = spu.current_func;

			// Event state as well as position, because position alone cannot tell a lost wakeup
			// from an idle wait. Both look like a thread parked in 'MFC Events read'.
			//
			// events is what has fired, mask is what this SPU asked to be woken for, waiting is
			// whether it is parked in the channel read. events & mask non-zero while waiting is
			// set means the wakeup it needs has ALREADY happened and was not delivered, which is
			// a lost notification and our bug. events & mask == 0 means it is genuinely idle and
			// whoever should signal it never did, which is a bug on the other side.
			//
			// Sonic Unleashed used to deadlock at the SEGA logo with all six SPURS kernels parked
			// here, in a different arrangement on different boots, so it is a race in this
			// handshake. That title has run correctly for several releases now; Borderlands 2
			// reaches the same state after its logo, with one kernel still running and the rest
			// parked, and which kernel keeps running changes between boots.
			const auto ev = spu.ch_events.load();

			fmt::append(spus, "\n  SPU 0x%07x '%s': state=%s pc=0x%05x block=0x%016llx func='%s' events=0x%04x mask=0x%08x waiting=%u pending=0x%04x",
				spu.lv2_id, *spu.spu_tname.load(), spu.state.load(), spu.pc,
				static_cast<u64>(spu.block_hash), func ? func : "",
				static_cast<u32>(ev.events), static_cast<u32>(ev.mask), static_cast<u32>(ev.waiting),
				static_cast<u32>(ev.events) & static_cast<u32>(ev.mask));

			// MFC state too, because an SPU can be stuck with no flag set at all.
			//
			// Sonic Unleashed hangs with RsdxPrimaryCellSpursKernel4 frozen at pc=0x07350 across
			// every sample of a session, while its neighbours move through the kernel normally.
			// state is 00, so it is not parked in a channel read -- it is spinning in guest code,
			// which an SPU does while waiting for a transfer to land in local store. If a queued
			// MFC command never retires, that spin never ends.
			//
			// mfc_size is the queue depth: non-zero and unchanging on the frozen thread means a
			// transfer went in and never came out. tag_mask/stall are what it would be waiting on.
			// interp_fallback distinguishes "the fallback never engaged" from "it engaged and the
			// thread is stuck anyway". Sonic Unleashed marks block 0x07350 uncompilable and stays
			// frozen on that pc; if this reads 1 there, the interpreter is looping too and the
			// block is not miscompiled -- the SPU is genuinely waiting on something.
			// intr/srr0 last, because they are what is left. Sonic Unleashed's stuck kernel holds
			// an unmasked pending LR event, is not blocked on a channel or a transfer, and does
			// not advance its pc even under the interpreter -- which is a branch-to-self idle
			// loop. SPURS kernels leave that loop on an SPU interrupt, so either interrupts are
			// disabled while an event is pending, or they are enabled and never delivered.
			fmt::append(spus, " mfc_q=%u tag_mask=0x%08x stall_mask=0x%08x interp_fb=%u intr_en=%u srr0=0x%05x",
				spu.mfc_size, spu.ch_tag_mask, spu.ch_stall_mask, spu.interp_fallback ? 1u : 0u,
				spu.interrupts_enabled ? 1u : 0u, spu.srr0);

			// Conditional-store activity. block_counter already says whether an SPU is executing
			// guest code, but a thread livelocked retrying PUTLLC and a thread that is genuinely
			// idle both report zero blocks per second and are otherwise indistinguishable here.
			// 'suppressed' is the SPURS heuristic in do_putllc choosing not to wake the waiters:
			// a store that succeeds and notifies nobody is the one way this hang can be nobody's
			// fault locally and still never end.
			static constexpr const char* where_names[]{ "?", "loop_top", "check_state", "stop_signal", "gateway_enter", "gateway_exit" };
			const u32 where = spu.dbg_where < std::size(where_names) ? spu.dbg_where : 0u;

			// raddr is the 128-byte line this SPU currently holds a reservation on, and spurs_addr
			// is the control block the kernel schedules through. Borderlands 2 hangs with every
			// PhysWISE kernel alive in guest code and ~39% of their conditional stores failing,
			// which is contention rather than a stall -- but contention on WHAT is the thing none
			// of the counters can say. If they are all hammering one line, that line is the bug's
			// address; if they are spread out, this is not the contention story it looks like.
			fmt::append(spus, " putllc={calls=%u fails=%u notify=%u suppressed=%u} blocks=%u loops=%u where=%s raddr=0x%x spurs=0x%x rtime=%u",
				spu.putllc_calls, spu.putllc_fails, spu.putllc_notify, spu.putllc_suppressed,
				spu.block_counter, spu.dbg_loops, where_names[where],
				spu.raddr, spu.spurs_addr, spu.rtime);

			fmt::append(spus, " events_sent=%u", spu.events_sent);

			// Is the line this SPU is parked on being written at all?
			//
			// The wait loop wakes on either of two things: the reservation counter moving, or
			// the 128 bytes themselves changing under an unchanged counter. So an SPU that
			// stays asleep is not evidence of a lost notification -- it is evidence that
			// NOTHING TOUCHED THE LINE. Assassin's Creed parks all six SPURS kernels on the
			// control block forever while the PPU spins adding urgent commands to the job
			// chain, which lives ~4KB away on a different line, so nothing the PPU does there
			// can wake them. Printing the counter and the first bytes each time this dump runs
			// turns "asleep" into "asleep and the line is provably static", which is a
			// different bug with a different fix.
			if (spu.raddr && vm::check_addr(spu.raddr))
			{
				const u64 res_now = vm::reservation_acquire(spu.raddr);
				fmt::append(spus, " res_now=%u res_moved=%u", res_now, res_now != spu.rtime ? 1 : 0);

				fmt::append(spus, " line=");
				for (u32 i = 0; i < 16; i++)
				{
					fmt::append(spus, "%02x", vm::read8(spu.raddr + i));
				}
			}

			// What the kernel actually looked at before deciding to sleep.
			//
			// Everything above says the SPU is parked and that the line it waits on is static.
			// Neither says WHY it chose to wait, and that decision is guest code: the SPURS
			// kernel reads the control block into local store, tests it, and either takes work
			// or arms an LR wait. The registers hold the values it tested and the local store
			// around pc holds the test itself, which is the same pairing the PPU half of this
			// dump has always printed and the SPU half never did.
			//
			// One SPU only. All six kernels park at the same pc running the same code, so six
			// copies is six times the log for no extra fact. Picked by raddr == spurs_addr,
			// which is what identifies a SPURS kernel waiting on its own control block.
			const bool is_detail_target = spu_detail_pinned
				? id == spu_detail_id
				: spu.raddr && spu.raddr == spu.spurs_addr;

			if (!spu_detail_done && is_detail_target)
			{
				spu_detail_done = true;

				fmt::append(spus, "\n    --- kernel detail (%s) ---",
					spu_detail_pinned ? "the stopped SPU -- the rest are waiting on it" : "one SPU; the others are idle at the same pc");

				// All 128, not the first 16. Xillia's assertion is a validity check over
				// r12/r16/r17/r19/r33/r34, so the sixteen that used to be printed did not include
				// a single operand of the test the log had just finished disassembling.
				for (u32 i = 0; i < 128; i++)
				{
					const auto& r = spu.gpr[i];
					fmt::append(spus, "\n    r%-3u = %08x %08x %08x %08x", i,
						r._u32[3], r._u32[2], r._u32[1], r._u32[0]);
				}

				// Local store around pc. Narrow: this is a decision, not a function body, and
				// the branch that armed the wait is within a few instructions of the channel read.
				SPUDisAsm spu_dis(cpu_disasm_mode::dump, spu.ls);
				const u32 from = spu.pc >= 0x40 ? spu.pc - 0x40 : 0;

				fmt::append(spus, "\n    Local store around pc=0x%05x:\n", spu.pc);

				for (u32 addr = from; addr <= from + 0x90 && addr < SPU_LS_SIZE; addr += 4)
				{
					spu_dis.disasm(addr);
					spus += (addr == spu.pc ? "    >>" : "      ");
					spus += spu_dis.last_opcode;
				}
			}

			// What the polling worker is actually looking at, once per process.
			//
			// Everything measured so far says this SPU is not miscomputing -- spu_alu is
			// byte-exact and spu_fpu matches x86 on every line -- and that the event it should
			// send is never sent rather than lost, since no queue holds a pending event with a
			// waiter attached. So it is spinning on data it does not like, and these are the
			// three views of that data:
			//
			//   rdata  the 128-byte snapshot GETLLAR took, which is what the guest compares
			//   live   the same line in main memory right now
			//   ls     local store 0x100..0x180, the SPURS control mirror -- RPCS3's own
			//          do_putllc heuristic reads the idle bitmap at 0x100 + 0x73, so this is
			//          the region it treats as the kernel's control area
			//
			// rdata differing from live while the guest keeps re-reserving means it is polling a
			// stale view; identical means the data is fine and the loop's exit condition is not
			// about this line at all. Once per process: three 128-byte blocks per SPU is a lot of
			// hex, and a per-dump version of this is exactly the mistake the code window made.
			static atomic_t<bool> s_ls_dumped{false};

			if (spu.raddr && !s_ls_dumped && !s_ls_dumped.exchange(true))
			{
				const auto hex128 = [](const void* src)
				{
					const auto p = static_cast<const u8*>(src);
					std::string out;

					for (u32 i = 0; i < 128; i += 16)
					{
						fmt::append(out, "\n      +0x%02x  %02x %02x %02x %02x %02x %02x %02x %02x  %02x %02x %02x %02x %02x %02x %02x %02x",
							i, p[i + 0], p[i + 1], p[i + 2], p[i + 3], p[i + 4], p[i + 5], p[i + 6], p[i + 7],
							p[i + 8], p[i + 9], p[i + 10], p[i + 11], p[i + 12], p[i + 13], p[i + 14], p[i + 15]);
					}

					return out;
				};

				fmt::append(spus, "\n    reservation snapshot (rdata) for raddr=0x%x:%s", spu.raddr, hex128(spu.rdata));

				if (vm::check_addr(spu.raddr, vm::page_readable, 128))
				{
					fmt::append(spus, "\n    live main memory at 0x%x:%s", spu.raddr, hex128(vm::base(spu.raddr)));
					fmt::append(spus, "\n    -> snapshot %s live memory",
						std::memcmp(spu.rdata, vm::base(spu.raddr), 128) == 0 ? "MATCHES" : "DIFFERS FROM");
				}

				if (spu.ls)
				{
					fmt::append(spus, "\n    local store 0x100 (SPURS control mirror):%s", hex128(spu.ls + 0x100));
				}
			}

			if (spu.mfc_size)
			{
				const auto& cmd = spu.mfc_queue[0];
				fmt::append(spus, " head={cmd=0x%02x tag=%u lsa=0x%05x eal=0x%08x size=0x%x}",
					+cmd.cmd, +cmd.tag, +cmd.lsa, +cmd.eal, +cmd.size);
			}

			// The loop itself, for an SPU that is RUNNING rather than parked.
			//
			// The state line above says where it is and what it holds; it cannot say what the code
			// does, and that is the remaining question whenever every other guest thread is
			// legitimately asleep. If pending == 0 on the sleepers (see above) then the fault is on
			// the signalling side, which is whichever SPU is still running -- so print what that one
			// is executing, and nothing for the parked ones, whose pc is just the kernel's idle
			// loop and is identical on all of them.
			//
			// Borderlands 2 hangs after its logo with exactly one SPURS kernel at pc=0x25da8 and
			// the rest at 0x011a8, and WHICH kernel spins changes between boots. Local store is a
			// flat 256KiB buffer, so the window only needs bounding against SPU_LS_SIZE -- there is
			// no address to validate the way the PPU side has to.
			if (spu.state.load().none_of(cpu_flag::wait) && spu.ls)
			{
				// Registers first: the loop is a comparison, and which values it compares is the
				// half the disassembly cannot supply.
				spu.dump_all(spus);

				// ONE window per process, because this dump repeats for as long as the stall
				// lasts and the window is a whole SPU function. Unguarded it cost ~2400 lines
				// every few seconds: measured 538 lines/sec over 31 dumps, ~74k of the 114k
				// lines in a single Borderlands 2 capture, with a 690 MiB log left behind by the
				// run before it. Log volume that heavy is itself a stall on Android, so this was
				// slowing down the very hang it was meant to describe -- and it buried the state
				// lines that actually answered the question. The code only needs printing once;
				// the state line above still prints on every dump and is what shows change.
				static atomic_t<bool> s_window_done{false};

				if (s_window_done.exchange(true))
				{
					return;
				}

				const u32 pc = spu.pc;
				const u32 from = pc >= 0x40 ? pc - 0x40 : 0;

				// Big enough for a whole SPU function, which took two captures to establish.
				//
				// The recompiler only writes pc at block boundaries, so a thread that never leaves
				// its block reports the block's ENTRY, not the loop. 0x120 bytes got a prologue --
				// twenty-odd stqd of callee-saved registers. 0x600 got 385 instructions with no
				// back-edge and no channel op in them, which reads like "not looping here" and is
				// simply the window ending first: the log says this block is 2401 instructions
				// ("Building function 0x25da8... (size 2401)"), i.e. 0x2584 bytes. Anything shorter
				// than the function cannot answer "where does it loop", so the window covers one.
				const u32 to = std::min<u32>(from + 0x2600, SPU_LS_SIZE - 4);

				SPUDisAsm dis_asm(cpu_disasm_mode::dump, spu.ls);

				fmt::append(spus, "\n    running -- code from pc=0x%05x (block entry; loop is inside):\n", pc);

				for (u32 addr = from; addr <= to; addr += 4)
				{
					dis_asm.disasm(addr);
					spus += (addr == pc ? "  >>" : "    ");
					spus += dis_asm.last_opcode;
				}
			}
		}, idm::unlocked);

		if (!spus.empty())
		{
			rsx_log.error("Guest SPU threads at the same moment:%s", spus);
		}

		// The groups the threads above belong to.
		//
		// Borderlands 2 does not fail at a boundary: the game runs thousands of
		// start/join cycles on a group and loses one of them. The syscall histogram showed one
		// PPU thread stopped at 2532 start/join pairs while its siblings passed 8000 and an x86
		// host passed 7426, so the interesting number is which cycle this is and what the group
		// believes about it. stop_count is that cycle counter, and 'running' is the count the
		// join is waiting on -- if it stays above zero while every thread is spinning in the
		// SPURS scheduler then the group never delivered the exit request, and if it reaches
		// zero while a join still waits then the accounting is ours to fix. Neither is visible
		// from the thread states alone.
		std::string groups;

		idm::select<lv2_spu_group>([&groups](u32 id, lv2_spu_group& group)
		{
			fmt::append(groups, "\n  group 0x%07x '%s': state=%s running=%u/%u stop_count=%u join_state=0x%x exit_status=0x%x",
				id, group.name, group.run_state.load(), group.running.load(), group.max_num,
				group.stop_count.load(), group.join_state.load(), group.exit_status.load());
		}, idm::unlocked);

		if (!groups.empty())
		{
			rsx_log.error("Guest SPU thread groups:%s", groups);
		}

		// The queues those threads are blocked on -- the half never looked at.
		//
		// Every dump so far has said which threads are asleep and which SPU is spinning, and none
		// has said whether the thing they are waiting for was ever posted. Borderlands 2 leaves
		// main_thread in sys_event_queue_receive on 0x8d021700 while one SPURS worker polls for
		// work at ~720k block-entries a second, and the two possibilities need opposite fixes:
		//
		//   pending > 0 with a waiter attached -> the event WAS delivered and the wakeup was
		//     lost, which is ours, in lv2.
		//   pending == 0 with a waiter attached -> nothing was ever posted, so the worker is not
		//     signalling and the fault is upstream of the queue.
		//
		// Read without taking the queue mutex, deliberately: this runs on the RSX thread during a
		// hang, and blocking on a lock held by a thread being diagnosed is how a diagnostic turns
		// into a second deadlock. A torn size costs nothing here.
		std::string queues;

		idm::select<lv2_obj, lv2_event_queue>([&queues](u32 id, lv2_event_queue& eq)
		{
			const usz pending = eq.events.size();
			const auto ppu_waiter = eq.pq;
			const auto spu_waiter = eq.sq;

			// Only the interesting ones: something queued, or somebody asleep on it.
			if (!pending && !ppu_waiter && !spu_waiter)
			{
				return;
			}

			fmt::append(queues, "\n  queue 0x%07x: type=%u size=%u pending=%u ppu_waiter=0x%x spu_waiter=0x%x",
				id, eq.type, eq.size, static_cast<u32>(pending),
				ppu_waiter ? ppu_waiter->id : 0u,
				spu_waiter ? spu_waiter->id : 0u);
		});

		if (!queues.empty())
		{
			rsx_log.error("Guest event queues with a waiter or a pending event:%s", queues);
		}
	}

	void thread::do_local_task(FIFO::state state)
	{
		if (const u32 state_idx = static_cast<u32>(state); state_idx < s_ltask_states)
		{
			g_ltask_state_calls[state_idx]++;
		}

		// Arm and poll from here as well as on_frame_end. Both of those run only once a frame
		// has completed, so a boot that hangs before presenting left the profiler switched off
		// and silent -- and that is the case where what the RSX thread is looping in is the
		// whole question. Both calls return immediately once armed and are rate-limited.
		prof::set_enabled(g_cfg.video.rsx_profiler.get());

		// Always on, unlike the profiler-gated reports below: the whole point is that this
		// reaches a user who has not enabled anything.
		check_frame_stall();

		// Both halves on the same condition. Every hang chased so far has been the guest
		// waiting while the RSX idles, and only the RSX half was ever visible.
		if (prof::poll_stall()) [[unlikely]]
		{
			dump_guest_threads_stalled();

			const u64 report_now = get_system_time();
			const u64 span = g_ltask_last_report ? report_now - g_ltask_last_report : 0;
			g_ltask_last_report = report_now;

			static constexpr const char* state_names[s_ltask_states]{ "running", "empty", "spinning", "nop", "lock_wait", "paused" };
			static constexpr const char* branch_names[s_ltask_branches]{ "emu_flip", "invalidate", "dma_ctrl", "pipe_sync" };

			std::string act;

			for (u32 i = 0; i < s_ltask_states; i++)
			{
				const u64 delta = g_ltask_state_calls[i] - g_ltask_state_prev[i];
				g_ltask_state_prev[i] = g_ltask_state_calls[i];

				if (delta)
				{
					fmt::append(act, " %s=%u", state_names[i], delta);
				}
			}

			for (u32 i = 0; i < s_ltask_branches; i++)
			{
				const u64 delta = g_ltask_branch[i] - g_ltask_branch_prev[i];
				g_ltask_branch_prev[i] = g_ltask_branch[i];

				if (delta)
				{
					fmt::append(act, " %s=%u", branch_names[i], delta);
				}
			}

			// get/put straight out of the guest DMA control block rather than our mirror of it.
			// get == put with state 'empty' is the guest not submitting; get != put while no
			// frame lands is the RSX failing to drain what it already has, and those want
			// opposite fixes.
			u32 fifo_get = 0, fifo_put = 0, fifo_ref = 0;

			if (dma_address && vm::check_addr(dma_address))
			{
				const auto ctrl = vm::_ptr<RsxDmaControl>(dma_address);
				fifo_put = ctrl->put;
				fifo_get = ctrl->get;
				fifo_ref = ctrl->ref;
			}

			rsx_log.error("RSX local task over %.1fs:%s | fifo get=0x%x put=0x%x ref=0x%x internal_get=0x%x last_cmd=0x%08x args_left=%u | dma_ctrl_pending=%u pipe_flush_pending=%u in_begin_end=%u",
				span / 1'000'000.,
				act.empty() ? " (no calls)" : act.c_str(),
				fifo_get, fifo_put, fifo_ref,
				fifo_ctrl ? fifo_ctrl->get_pos() : 0u,
				fifo_ctrl ? fifo_ctrl->last_cmd() : 0u,
				fifo_ctrl ? fifo_ctrl->get_remaining_args_count() : 0u,
				(m_eng_interrupt_mask & rsx::dma_control_interrupt) ? 1 : 0,
				(m_eng_interrupt_mask & rsx::pipe_flush_interrupt) ? 1 : 0,
				in_begin_end ? 1 : 0);
		}

		m_eng_interrupt_mask.clear(rsx::backend_interrupt);

		if (async_flip_requested & flip_request::emu_requested)
		{
			g_ltask_branch[ltask_emu_flip]++;

			// NOTE: This has to be executed immediately
			// Delaying this operation can cause desync due to the delay in firing the flip event
			handle_emu_flip(async_flip_buffer);
		}

		if (state != FIFO::state::lock_wait)
		{
			if (!in_begin_end && atomic_storage<u32>::load(m_invalidated_memory_range.end) != 0)
			{
				std::lock_guard lock(m_mtx_task);

				if (m_invalidated_memory_range.valid())
				{
					g_ltask_branch[ltask_invalidate]++;
					handle_invalidated_memory_range();
				}
			}

			if (m_eng_interrupt_mask & rsx::dma_control_interrupt && !is_stopped())
			{
				g_ltask_branch[ltask_dma_control]++;

				if (const u64 get_put = new_get_put.exchange(u64{umax});
					get_put != umax)
				{
					vm::_ptr<atomic_be_t<u64>>(dma_address + ::offset32(&RsxDmaControl::put))->release(get_put);
					fifo_ctrl->set_get(static_cast<u32>(get_put));
					fifo_ctrl->abort();
					fifo_ret_addr = RSX_CALL_STACK_EMPTY;
					last_known_code_start = static_cast<u32>(get_put);
					sync_point_request.release(true);
				}

				m_eng_interrupt_mask.clear(rsx::dma_control_interrupt);
			}
		}

		if (m_eng_interrupt_mask & rsx::pipe_flush_interrupt)
		{
			g_ltask_branch[ltask_pipe_flush]++;
			sync();
		}

		if (is_stopped())
		{
			std::lock_guard lock(m_mtx_task);

			m_invalidated_memory_range = utils::address_range32::start_end(0x2 << 28, constants::local_mem_base + local_mem_size - 1);
			handle_invalidated_memory_range();
		}
	}

	std::array<u32, 4> thread::get_color_surface_addresses() const
	{
		u32 offset_color[] =
		{
			rsx::method_registers.surface_offset(0),
			rsx::method_registers.surface_offset(1),
			rsx::method_registers.surface_offset(2),
			rsx::method_registers.surface_offset(3),
		};
		u32 context_dma_color[] =
		{
			rsx::method_registers.surface_dma(0),
			rsx::method_registers.surface_dma(1),
			rsx::method_registers.surface_dma(2),
			rsx::method_registers.surface_dma(3),
		};
		return
		{
			rsx::get_address(offset_color[0], context_dma_color[0]),
			rsx::get_address(offset_color[1], context_dma_color[1]),
			rsx::get_address(offset_color[2], context_dma_color[2]),
			rsx::get_address(offset_color[3], context_dma_color[3]),
		};
	}

	u32 thread::get_zeta_surface_address() const
	{
		u32 m_context_dma_z = rsx::method_registers.surface_z_dma();
		u32 offset_zeta = rsx::method_registers.surface_z_offset();
		return rsx::get_address(offset_zeta, m_context_dma_z);
	}

	void thread::get_framebuffer_layout(rsx::framebuffer_creation_context context, framebuffer_layout &layout)
	{
		layout = {};

		layout.ignore_change = true;
		layout.width = rsx::method_registers.surface_clip_width();
		layout.height = rsx::method_registers.surface_clip_height();

		m_graphics_state.clear(rsx::rtt_config_contested | rsx::rtt_config_valid);
		m_current_framebuffer_context = context;

		if (layout.width == 0 || layout.height == 0)
		{
			rsx_log.trace("Invalid framebuffer setup, w=%d, h=%d", layout.width, layout.height);
			return;
		}

		//const u16 clip_x = rsx::method_registers.surface_clip_origin_x();
		//const u16 clip_y = rsx::method_registers.surface_clip_origin_y();

		layout.color_addresses = get_color_surface_addresses();
		layout.zeta_address = get_zeta_surface_address();
		layout.zeta_pitch = rsx::method_registers.surface_z_pitch();
		layout.color_pitch =
		{
			rsx::method_registers.surface_pitch(0),
			rsx::method_registers.surface_pitch(1),
			rsx::method_registers.surface_pitch(2),
			rsx::method_registers.surface_pitch(3),
		};

		layout.color_format = rsx::method_registers.surface_color();
		layout.depth_format = rsx::method_registers.surface_depth_fmt();
		layout.target = rsx::method_registers.surface_color_target();

		const auto mrt_buffers = rsx::utility::get_rtt_indexes(layout.target);
		const auto aa_mode = rsx::method_registers.surface_antialias();
		const u32 aa_factor_u = (aa_mode == rsx::surface_antialiasing::center_1_sample) ? 1 : 2;
		const u32 aa_factor_v = (aa_mode == rsx::surface_antialiasing::center_1_sample || aa_mode == rsx::surface_antialiasing::diagonal_centered_2_samples) ? 1 : 2;
		const u8 sample_count = get_format_sample_count(aa_mode);

		const auto depth_texel_size = get_format_block_size_in_bytes(layout.depth_format) * aa_factor_u;
		const auto color_texel_size = get_format_block_size_in_bytes(layout.color_format) * aa_factor_u;
		const bool stencil_test_enabled = is_depth_stencil_format(layout.depth_format) && rsx::method_registers.stencil_test_enabled();
		const bool depth_test_enabled = rsx::method_registers.depth_test_enabled();

		// Check write masks
		layout.zeta_write_enabled = (depth_test_enabled && rsx::method_registers.depth_write_enabled());
		if (!layout.zeta_write_enabled && stencil_test_enabled)
		{
			// Check if stencil data is modified
			auto mask = rsx::method_registers.stencil_mask();
			bool active_write_op = (rsx::method_registers.stencil_op_zpass() != rsx::stencil_op::keep ||
				rsx::method_registers.stencil_op_fail() != rsx::stencil_op::keep ||
				rsx::method_registers.stencil_op_zfail() != rsx::stencil_op::keep);

			if ((!mask || !active_write_op) && rsx::method_registers.two_sided_stencil_test_enabled())
			{
				mask |= rsx::method_registers.back_stencil_mask();
				active_write_op |= (rsx::method_registers.back_stencil_op_zpass() != rsx::stencil_op::keep ||
					rsx::method_registers.back_stencil_op_fail() != rsx::stencil_op::keep ||
					rsx::method_registers.back_stencil_op_zfail() != rsx::stencil_op::keep);
			}

			layout.zeta_write_enabled = (mask && active_write_op);
		}

		// NOTE: surface_target_a is index 1 but is not MRT since only one surface is active
		bool color_write_enabled = false;
		for (uint i = 0; i < mrt_buffers.size(); ++i)
		{
			if (rsx::method_registers.color_write_enabled(i))
			{
				const auto real_index = mrt_buffers[i];
				layout.color_write_enabled[real_index] = true;
				color_write_enabled = true;
			}
		}

		bool depth_buffer_unused = false, color_buffer_unused = false;

		switch (context)
		{
		case rsx::framebuffer_creation_context::context_clear_all:
			break;
		case rsx::framebuffer_creation_context::context_clear_depth:
			color_buffer_unused = true;
			break;
		case rsx::framebuffer_creation_context::context_clear_color:
			depth_buffer_unused = true;
			break;
		case rsx::framebuffer_creation_context::context_draw:
			// NOTE: As with all other hw, depth/stencil writes involve the corresponding depth/stencil test, i.e No test = No write
			// NOTE: Depth test is not really using the memory if its set to always or never
			// TODO: Perform similar checks for stencil test
			if (!stencil_test_enabled)
			{
				if (!depth_test_enabled)
				{
					depth_buffer_unused = true;
				}
				else if (!rsx::method_registers.depth_write_enabled())
				{
					// Depth test is enabled but depth write is disabled
					switch (rsx::method_registers.depth_func())
					{
					default:
						break;
					case rsx::comparison_function::never:
					case rsx::comparison_function::always:
						// No access to depth buffer memory
						depth_buffer_unused = true;
						break;
					}
				}

				if (depth_buffer_unused) [[unlikely]]
				{
					// Check if depth bounds is active. Depth bounds test does NOT need depth test to be enabled to access the Z buffer
					// Bind Z buffer in read mode for bounds check in this case
					if (rsx::method_registers.depth_bounds_test_enabled() &&
						(rsx::method_registers.depth_bounds_min() > 0.f || rsx::method_registers.depth_bounds_max() < 1.f))
					{
						depth_buffer_unused = false;
					}
				}
			}

			color_buffer_unused = !color_write_enabled || layout.target == rsx::surface_target::none;
			if (color_buffer_unused || depth_buffer_unused)
			{
				m_graphics_state.set(rsx::rtt_config_contested);
			}
			break;
		default:
			fmt::throw_exception("Unknown framebuffer context 0x%x", static_cast<u32>(context));
		}

		// Swizzled render does tight packing of bytes
		bool packed_render = false;
		u32 minimum_color_pitch = 64u;
		u32 minimum_zeta_pitch = 64u;

		switch (layout.raster_type = rsx::method_registers.surface_type())
		{
		default:
			rsx_log.error("Unknown raster mode 0x%x", static_cast<u32>(layout.raster_type));
			[[fallthrough]];
		case rsx::surface_raster_type::linear:
			break;
		case rsx::surface_raster_type::swizzle:
			packed_render = true;
			break;
		}

		if (!packed_render)
		{
			// Well, this is a write operation either way (clearing or drawing)
			// We can deduce a minimum pitch for which this operation is guaranteed to require by checking for the lesser of scissor or clip
			const u32 write_limit_x = std::min<u32>(layout.width, rsx::method_registers.scissor_origin_x() + rsx::method_registers.scissor_width());

			minimum_color_pitch = color_texel_size * write_limit_x;
			minimum_zeta_pitch = depth_texel_size * write_limit_x;

			// Check for size fit and attempt to correct incorrect inputs.
			// BLUS30072 is misconfigured here and renders fine on PS3. The width fails to account for AA being active in that engine.
			u16 corrected_width = umax;
			std::vector<u32*> pitch_fixups;

			if (!depth_buffer_unused)
			{
				if (layout.zeta_pitch < minimum_zeta_pitch)
				{
					// Observed in CoD3 where the depth buffer is clearly misconfigured.
					if (layout.zeta_pitch > 64)
					{
						corrected_width = layout.zeta_pitch / depth_texel_size;
						layout.zeta_pitch = depth_texel_size;
						pitch_fixups.push_back(&layout.zeta_pitch);
					}
					else
					{
						rsx_log.warning("Misconfigured surface could not fit a depth buffer. Dropping.");
						layout.zeta_address = 0;
					}
				}
				else if (layout.width * depth_texel_size > layout.zeta_pitch)
				{
					// This is ok, misconfigured raster dimensions, but we're only writing the pitch as determined by the scissor
					corrected_width = layout.zeta_pitch / depth_texel_size;
				}
			}

			if (!color_buffer_unused)
			{
				for (const auto& index : rsx::utility::get_rtt_indexes(layout.target))
				{
					if (layout.color_pitch[index] < minimum_color_pitch)
					{
						if (layout.color_pitch[index] > 64)
						{
							corrected_width = std::min<u16>(corrected_width, layout.color_pitch[index] / color_texel_size);
							layout.color_pitch[index] = color_texel_size;
							pitch_fixups.push_back(&layout.color_pitch[index]);
						}
						else
						{
							rsx_log.warning("Misconfigured surface could not fit color buffer %d. Dropping.", index);
							layout.color_addresses[index] = 0;
						}

						continue;
					}

					if (layout.width * color_texel_size > layout.color_pitch[index])
					{
						// This is ok, misconfigured raster dimensions, but we're only writing the pitch as determined by the scissor
						corrected_width = std::min<u16>(corrected_width, layout.color_pitch[index] / color_texel_size);
					}
				}
			}

			if (corrected_width != umax)
			{
				layout.width = corrected_width;

				for (auto& value : pitch_fixups)
				{
					*value = *value * layout.width;
				}
			}
		}

		if (depth_buffer_unused)
		{
			layout.zeta_address = 0;
		}
		else if (packed_render)
		{
			layout.actual_zeta_pitch = (layout.width * depth_texel_size);
		}
		else
		{
			layout.actual_zeta_pitch = layout.zeta_pitch;
		}

		for (const auto &index : rsx::utility::get_rtt_indexes(layout.target))
		{
			if (color_buffer_unused)
			{
				layout.color_addresses[index] = 0;
				continue;
			}

			if (layout.color_pitch[index] < minimum_color_pitch)
			{
				// Unlike the depth buffer, when given a color target we know it is intended to be rendered to
				rsx_log.warning("Framebuffer setup error: Color target failed pitch check, Pitch=[%d, %d, %d, %d] + %d, target=%d, context=%d",
					layout.color_pitch[0], layout.color_pitch[1], layout.color_pitch[2], layout.color_pitch[3],
					layout.zeta_pitch, static_cast<u32>(layout.target), static_cast<u32>(context));

				// Some games (COD4) are buggy and set incorrect width + AA + pitch combo. Force fit in such scenarios.
				if (layout.color_pitch[index] > 64)
				{
					layout.width = layout.color_pitch[index] / color_texel_size;
				}
				else
				{
					layout.color_addresses[index] = 0;
					continue;
				}
			}

			if (layout.color_addresses[index] == layout.zeta_address)
			{
				rsx_log.warning("Framebuffer at 0x%X has aliasing color/depth targets, color_index=%d, zeta_pitch = %d, color_pitch=%d, context=%d",
					layout.zeta_address, index, layout.zeta_pitch, layout.color_pitch[index], static_cast<u32>(context));

				m_graphics_state.set(rsx::rtt_config_contested);

				if (g_cfg.video.fb_aliasing_bias == framebuffer_aliasing_bias::prefer_color
					&& layout.color_write_enabled[index]
					&& !layout.zeta_write_enabled)
				{
					// Use address for color data
					layout.zeta_address = 0;
				}
				else
				{
					// TODO: Research clearing both depth AND color
					// TODO: If context is creation_draw, deal with possibility of a lost buffer clear
					if (depth_test_enabled || stencil_test_enabled || (!layout.color_write_enabled[index] && layout.zeta_write_enabled))
					{
						// Use address for depth data
						layout.color_addresses[index] = 0;
						continue;
					}
					else
					{
						// Use address for color data
						layout.zeta_address = 0;
					}
				}
			}

			ensure(layout.color_addresses[index]);

			const auto packed_pitch = (layout.width * color_texel_size);
			if (packed_render)
			{
				layout.actual_color_pitch[index] = packed_pitch;
			}
			else
			{
				layout.actual_color_pitch[index] = layout.color_pitch[index];
			}

			m_graphics_state.set(rsx::rtt_config_valid);
		}

		if (!m_graphics_state.test(rsx::rtt_config_valid) && !layout.zeta_address)
		{
			rsx_log.warning("Framebuffer setup failed. Draw calls may have been lost");
			return;
		}

		// At least one attachment exists
		m_graphics_state.set(rsx::rtt_config_valid);

		// Window (raster) offsets
		const auto window_offset_x = rsx::method_registers.window_offset_x();
		const auto window_offset_y = rsx::method_registers.window_offset_y();
		const auto window_clip_width = rsx::method_registers.window_clip_horizontal();
		const auto window_clip_height = rsx::method_registers.window_clip_vertical();

		if (window_offset_x || window_offset_y)
		{
			// Window offset is what affects the raster position!
			// Tested with Turbo: Super stunt squad that only changes the window offset to declare new framebuffers
			// Sampling behavior clearly indicates the addresses are expected to have changed
			if (auto clip_type = rsx::method_registers.window_clip_type())
				rsx_log.error("Unknown window clip type 0x%X", clip_type);

			for (const auto &index : rsx::utility::get_rtt_indexes(layout.target))
			{
				if (layout.color_addresses[index])
				{
					const u32 window_offset_bytes = (layout.actual_color_pitch[index] * window_offset_y) + (color_texel_size * window_offset_x);
					layout.color_addresses[index] += window_offset_bytes;
				}
			}

			if (layout.zeta_address)
			{
				layout.zeta_address += (layout.actual_zeta_pitch * window_offset_y) + (depth_texel_size * window_offset_x);
			}
		}

		if ((window_clip_width && window_clip_width < layout.width) ||
			(window_clip_height && window_clip_height < layout.height))
		{
			rsx_log.error("Unexpected window clip dimensions: window_clip=%dx%d, surface_clip=%dx%d",
				window_clip_width, window_clip_height, layout.width, layout.height);
		}

		layout.aa_mode = aa_mode;
		layout.aa_factors[0] = aa_factor_u;
		layout.aa_factors[1] = aa_factor_v;

		// Log this to frame stats
		if (layout.target != rsx::surface_target::none)
		{
			m_frame_stats.framebuffer_stats.add(layout.width, layout.height, aa_mode);
		}

		// Check if anything has changed
		bool really_changed = false;

		for (u8 i = 0; i < rsx::limits::color_buffers_count; ++i)
		{
			if (m_surface_info[i].address != layout.color_addresses[i])
			{
				really_changed = true;
				break;
			}

			if (layout.color_addresses[i])
			{
				if (m_surface_info[i].width != layout.width ||
					m_surface_info[i].height != layout.height ||
					m_surface_info[i].color_format != layout.color_format ||
					m_surface_info[i].samples != sample_count)
				{
					really_changed = true;
					break;
				}
			}
		}

		if (!really_changed)
		{
			if (layout.zeta_address == m_depth_surface_info.address &&
				layout.depth_format == m_depth_surface_info.depth_format &&
				sample_count == m_depth_surface_info.samples)
			{
				// Same target is reused
				return;
			}
		}

		layout.ignore_change = false;
	}

	void thread::on_framebuffer_options_changed(u32 opt)
	{
		if (m_graphics_state & rsx::rtt_config_dirty)
		{
			// Nothing to do
			return;
		}

		auto set_zeta_write_enabled = [&](bool state)
		{
			if (state == m_framebuffer_layout.zeta_write_enabled)
			{
				return;
			}

			if (m_graphics_state & rsx::zeta_address_is_cyclic)
			{
				m_graphics_state |= rsx::fragment_program_state_dirty;
			}
			m_framebuffer_layout.zeta_write_enabled = state;
		};

		auto evaluate_depth_buffer_state = [&]()
		{
			const bool zeta_write_en = (rsx::method_registers.depth_test_enabled() && rsx::method_registers.depth_write_enabled());
			set_zeta_write_enabled(zeta_write_en);
		};

		auto evaluate_stencil_buffer_state = [&]()
		{
			if (!m_framebuffer_layout.zeta_write_enabled &&
				rsx::method_registers.stencil_test_enabled() &&
				is_depth_stencil_format(m_framebuffer_layout.depth_format))
			{
				// Check if stencil data is modified
				auto mask = rsx::method_registers.stencil_mask();
				bool active_write_op = (rsx::method_registers.stencil_op_zpass() != rsx::stencil_op::keep ||
					rsx::method_registers.stencil_op_fail() != rsx::stencil_op::keep ||
					rsx::method_registers.stencil_op_zfail() != rsx::stencil_op::keep);

				if ((!mask || !active_write_op) && rsx::method_registers.two_sided_stencil_test_enabled())
				{
					mask |= rsx::method_registers.back_stencil_mask();
					active_write_op |= (rsx::method_registers.back_stencil_op_zpass() != rsx::stencil_op::keep ||
						rsx::method_registers.back_stencil_op_fail() != rsx::stencil_op::keep ||
						rsx::method_registers.back_stencil_op_zfail() != rsx::stencil_op::keep);
				}

				set_zeta_write_enabled(mask && active_write_op);
			}
		};

		auto evaluate_color_buffer_state = [&]() -> bool
		{
			const auto mrt_buffers = rsx::utility::get_rtt_indexes(m_framebuffer_layout.target);
			bool any_found = false;

			for (uint i = 0; i < mrt_buffers.size(); ++i)
			{
				if (m_ctx->register_state->color_write_enabled(i))
				{
					const auto real_index = mrt_buffers[i];
					m_framebuffer_layout.color_write_enabled[real_index] = true;
					any_found = true;
				}
			}

			on_framebuffer_layout_updated();
			return any_found;
		};

		auto evaluate_depth_buffer_contested = [&]()
		{
			if (m_framebuffer_layout.zeta_address) [[likely]]
			{
				// Nothing to do, depth buffer already exists
				return false;
			}

			// Check if depth read/write is enabled
			if (m_framebuffer_layout.zeta_write_enabled ||
				rsx::method_registers.depth_test_enabled())
			{
				return true;
			}

			// Check if stencil read is enabled
			if (is_depth_stencil_format(m_framebuffer_layout.depth_format) &&
				rsx::method_registers.stencil_test_enabled())
			{
				return true;
			}

			return false;
		};

		switch (opt)
		{
		case NV4097_SET_DEPTH_TEST_ENABLE:
		case NV4097_SET_DEPTH_MASK:
		case NV4097_SET_DEPTH_FUNC:
		{
			evaluate_depth_buffer_state();

			if (m_graphics_state.test(rsx::rtt_config_contested) && evaluate_depth_buffer_contested())
			{
				m_graphics_state.set(rsx::rtt_config_dirty);
			}
			break;
		}
		case NV4097_SET_TWO_SIDED_STENCIL_TEST_ENABLE:
		case NV4097_SET_STENCIL_TEST_ENABLE:
		case NV4097_SET_STENCIL_MASK:
		case NV4097_SET_STENCIL_OP_ZPASS:
		case NV4097_SET_STENCIL_OP_FAIL:
		case NV4097_SET_STENCIL_OP_ZFAIL:
		case NV4097_SET_BACK_STENCIL_MASK:
		case NV4097_SET_BACK_STENCIL_OP_ZPASS:
		case NV4097_SET_BACK_STENCIL_OP_FAIL:
		case NV4097_SET_BACK_STENCIL_OP_ZFAIL:
		{
			// Stencil takes a back seat to depth buffer stuff
			evaluate_depth_buffer_state();

			if (!m_framebuffer_layout.zeta_write_enabled)
			{
				evaluate_stencil_buffer_state();
			}

			if (m_graphics_state.test(rsx::rtt_config_contested) && evaluate_depth_buffer_contested())
			{
				m_graphics_state.set(rsx::rtt_config_dirty);
			}
			break;
		}
		case NV4097_SET_COLOR_MASK:
		case NV4097_SET_COLOR_MASK_MRT:
		{
			if (!m_graphics_state.test(rsx::rtt_config_contested)) [[likely]]
			{
				// Update write masks and continue
				evaluate_color_buffer_state();
			}
			else
			{
				bool old_state = false;
				for (const auto& enabled : m_framebuffer_layout.color_write_enabled)
				{
					if (old_state = enabled; old_state) break;
				}

				const auto new_state = evaluate_color_buffer_state();
				if (!old_state && new_state)
				{
					// Color buffers now in use
					m_graphics_state.set(rsx::rtt_config_dirty);
				}
			}
			break;
		}
		default:
			rsx_log.fatal("Unhandled framebuffer option changed 0x%x", opt);
			break;
		}
	}

	void thread::on_framebuffer_layout_updated()
	{
		if (m_graphics_state.test(rsx::fragment_program_state_dirty))
		{
			return;
		}

		const auto target = m_ctx->register_state->surface_color_target();
		if (rsx::utility::get_mrt_buffers_count(target) == current_fragment_program.mrt_buffers_count)
		{
			return;
		}

		m_graphics_state |= rsx::fragment_program_state_dirty;
	}

	bool thread::get_scissor(areau& region, bool clip_viewport)
	{
		if (!m_graphics_state.test(rsx::pipeline_state::scissor_config_state_dirty))
		{
			if (clip_viewport == m_graphics_state.test(rsx::pipeline_state::scissor_setup_clipped))
			{
				// Nothing to do
				return false;
			}
		}

		m_graphics_state.clear(rsx::pipeline_state::scissor_config_state_dirty | rsx::pipeline_state::scissor_setup_clipped);

		u16 x1, x2, y1, y2;

		u16 scissor_x = rsx::method_registers.scissor_origin_x();
		u16 scissor_w = rsx::method_registers.scissor_width();
		u16 scissor_y = rsx::method_registers.scissor_origin_y();
		u16 scissor_h = rsx::method_registers.scissor_height();

		if (clip_viewport)
		{
			u16 raster_x = rsx::method_registers.viewport_origin_x();
			u16 raster_w = rsx::method_registers.viewport_width();
			u16 raster_y = rsx::method_registers.viewport_origin_y();
			u16 raster_h = rsx::method_registers.viewport_height();

			// Get the minimum area between these two
			x1 = std::max(scissor_x, raster_x);
			y1 = std::max(scissor_y, raster_y);
			x2 = std::min(scissor_x + scissor_w, raster_x + raster_w);
			y2 = std::min(scissor_y + scissor_h, raster_y + raster_h);

			m_graphics_state |= rsx::pipeline_state::scissor_setup_clipped;
		}
		else
		{
			x1 = scissor_x;
			x2 = scissor_x + scissor_w;
			y1 = scissor_y;
			y2 = scissor_y + scissor_h;
		}

		if (x2 <= x1 ||
			y2 <= y1 ||
			x1 >= rsx::method_registers.window_clip_horizontal() ||
			y1 >= rsx::method_registers.window_clip_vertical())
		{
			m_graphics_state |= rsx::pipeline_state::scissor_setup_invalid;
			m_graphics_state.clear(rsx::rtt_config_valid);
			return false;
		}

		if (m_graphics_state & rsx::pipeline_state::scissor_setup_invalid)
		{
			m_graphics_state.clear(rsx::pipeline_state::scissor_setup_invalid);
			m_graphics_state.set(rsx::rtt_config_valid);
		}

		std::tie(region.x1, region.y1) = rsx::apply_resolution_scale<false>(resolution_scaling_config, x1, y1, m_framebuffer_layout.width, m_framebuffer_layout.height);
		std::tie(region.x2, region.y2) = rsx::apply_resolution_scale<true>(resolution_scaling_config, x2, y2, m_framebuffer_layout.width, m_framebuffer_layout.height);

		return true;
	}

	rsx::flags32_t thread::get_fragment_program_export_config()
	{
		if (!g_cfg.video.emulate_depth_compare) [[ likely ]]
		{
			return 0;
		}

		if (m_ctx->register_state->current_draw_clause.classify_mode() != primitive_class::polygon)
		{
			return 0;
		}

		u32 expected_ctrl = 0;

		if (m_framebuffer_layout.zeta_address &&
			m_ctx->register_state->depth_test_enabled() &&
			m_ctx->register_state->depth_func() == rsx::comparison_function::equal)
		{
			expected_ctrl |= RSX_SHADER_CONTROL_EMULATE_DEPTH_COMPARE;

			if (backend_config.supports_hw_msaa &&
				m_ctx->register_state->surface_antialias() != rsx::surface_antialiasing::center_1_sample)
			{
				expected_ctrl |= RSX_SHADER_CONTROL_MULTISAMPLED_ZBUFFER;
			}
		}

		return expected_ctrl;
	}

	void thread::prefetch_fragment_program()
	{
		if (!m_graphics_state.test(rsx::pipeline_state::fragment_program_ucode_dirty))
		{
			return;
		}

		m_graphics_state.clear(rsx::pipeline_state::fragment_program_ucode_dirty);

		// Request for update of fragment constants if the program block is invalidated
		m_graphics_state |= rsx::pipeline_state::fragment_constants_dirty;

		const auto [program_offset, program_location] = method_registers.shader_program_address();
		const auto prev_textures_reference_mask = current_fp_metadata.referenced_textures_mask;

		auto data_ptr = vm::base(rsx::get_address(program_offset, program_location));
		current_fp_metadata = program_hash_util::fragment_program_utils::analyse_fragment_program(data_ptr);

		current_fragment_program.data = (static_cast<u8*>(data_ptr) + current_fp_metadata.program_start_offset);
		current_fragment_program.offset = program_offset + current_fp_metadata.program_start_offset;
		current_fragment_program.ucode_length = current_fp_metadata.program_ucode_length;
		current_fragment_program.total_length = current_fp_metadata.program_ucode_length + current_fp_metadata.program_start_offset;
		current_fragment_program.texture_state.import(current_fp_texture_state, current_fp_metadata.referenced_textures_mask);
		current_fragment_program.valid = true;

		if (!m_graphics_state.test(rsx::pipeline_state::fragment_program_state_dirty))
		{
			// Verify current texture state is valid
			for (u32 textures_ref = current_fp_metadata.referenced_textures_mask, i = 0; textures_ref; textures_ref >>= 1, ++i)
			{
				if (!(textures_ref & 1)) continue;

				if (m_textures_dirty[i])
				{
					m_graphics_state |= rsx::pipeline_state::fragment_program_state_dirty;
					break;
				}
			}
		}

		if (!m_graphics_state.test(rsx::pipeline_state::fragment_program_state_dirty) &&
			(prev_textures_reference_mask != current_fp_metadata.referenced_textures_mask))
		{
			// If different textures are used, upload their coefficients.
			// The texture parameters transfer routine is optimized and only writes data for textures consumed by the ucode.
			m_graphics_state |= rsx::pipeline_state::fragment_texture_state_dirty;
		}
	}

	void thread::prefetch_vertex_program()
	{
		if (!m_graphics_state.test(rsx::pipeline_state::vertex_program_ucode_dirty))
		{
			return;
		}

		m_graphics_state.clear(rsx::pipeline_state::vertex_program_ucode_dirty);

		// Reload transform constants unconditionally for now
		m_graphics_state |= rsx::pipeline_state::transform_constants_dirty;

		const u32 transform_program_start = rsx::method_registers.transform_program_start();
		current_vertex_program.data.reserve(512 * 4);
		current_vertex_program.jump_table.clear();

		current_vp_metadata = program_hash_util::vertex_program_utils::analyse_vertex_program
		(
			method_registers.transform_program.data(),  // Input raw block
			transform_program_start,                    // Address of entry point
			current_vertex_program                      // [out] Program object
		);

		current_vertex_program.texture_state.import(current_vp_texture_state, current_vp_metadata.referenced_textures_mask);

		if (!m_graphics_state.test(rsx::pipeline_state::vertex_program_state_dirty))
		{
			// Verify current texture state is valid
			for (u32 textures_ref = current_vp_metadata.referenced_textures_mask, i = 0; textures_ref; textures_ref >>= 1, ++i)
			{
				if (!(textures_ref & 1)) continue;

				if (m_vertex_textures_dirty[i])
				{
					m_graphics_state |= rsx::pipeline_state::vertex_program_state_dirty;
					break;
				}
			}
		}
	}

	void thread::analyse_current_rsx_pipeline()
	{
		// Vertex and fragment ucode analysis: a full program walk with recursive visitors
		// and per-call allocations, run before load_program, so the pipeline scope never
		// saw it and shader_translate had no site at all.
		RSX_PROF_SCOPE(shader_translate);

		m_program_cache_hint.invalidate(m_graphics_state.load());

		constexpr u32 fs_export_config_mask = (RSX_SHADER_CONTROL_EMULATE_DEPTH_COMPARE | RSX_SHADER_CONTROL_MULTISAMPLED_ZBUFFER);
		if (u32 export_ctrl = get_fragment_program_export_config();
			(current_fragment_program.ctrl & fs_export_config_mask) != export_ctrl)
		{
			// Update control bits for immediate consumers
			current_fragment_program.ctrl &= ~fs_export_config_mask;
			current_fragment_program.ctrl |= export_ctrl;

			// Signal backend to reload pipeline
			m_graphics_state.set(rsx::pipeline_state::fragment_program_state_dirty);
		}

		prefetch_vertex_program();
		prefetch_fragment_program();
	}

	void thread::get_current_vertex_program(const std::array<std::unique_ptr<rsx::sampled_image_descriptor_base>, rsx::limits::vertex_textures_count>& sampler_descriptors)
	{
		if (m_graphics_state.test(rsx::pipeline_state::xform_instancing_state_dirty))
		{
			current_vertex_program.ctrl = 0;
			if (rsx::method_registers.current_draw_clause.is_trivial_instanced_draw)
			{
				current_vertex_program.ctrl |= RSX_SHADER_CONTROL_INSTANCED_CONSTANTS;
			}

			m_graphics_state.clear(rsx::pipeline_state::xform_instancing_state_dirty);

			// Emit invalidate here in case ucode is actually clean
			m_program_cache_hint.invalidate_vertex_program(current_vertex_program);
		}

		if (!m_graphics_state.test(rsx::pipeline_state::vertex_program_dirty))
		{
			return;
		}

		ensure(!m_graphics_state.test(rsx::pipeline_state::vertex_program_ucode_dirty));
		current_vertex_program.output_mask = rsx::method_registers.vertex_attrib_output_mask();

		for (u32 textures_ref = current_vp_metadata.referenced_textures_mask, i = 0; textures_ref; textures_ref >>= 1, ++i)
		{
			if (!(textures_ref & 1)) continue;

			const auto &tex = rsx::method_registers.vertex_textures[i];
			if (tex.enabled() && (current_vp_metadata.referenced_textures_mask & (1 << i)))
			{
				current_vp_texture_state.clear(i);
				current_vp_texture_state.set_dimension(sampler_descriptors[i]->image_type, i);

				if (backend_config.supports_hw_msaa &&
					sampler_descriptors[i]->samples > 1)
				{
					current_vp_texture_state.multisampled_textures |= (1 << i);
				}
			}
		}

		current_vertex_program.texture_state.import(current_vp_texture_state, current_vp_metadata.referenced_textures_mask);

		m_program_cache_hint.invalidate_vertex_program(current_vertex_program);
	}

	void thread::get_current_fragment_program(const std::array<std::unique_ptr<rsx::sampled_image_descriptor_base>, rsx::limits::fragment_textures_count>& sampler_descriptors)
	{
		if (!m_graphics_state.test(rsx::pipeline_state::fragment_program_dirty))
		{
			return;
		}

		ensure(!m_graphics_state.test(rsx::pipeline_state::fragment_program_ucode_dirty));

		m_graphics_state.clear(rsx::pipeline_state::fragment_program_dirty);

		current_fragment_program.ctrl = m_ctx->register_state->shader_control() & (CELL_GCM_SHADER_CONTROL_32_BITS_EXPORTS | CELL_GCM_SHADER_CONTROL_DEPTH_EXPORT | RSX_SHADER_CONTROL_USES_KIL);
		current_fragment_program.texcoord_control_mask = m_ctx->register_state->texcoord_control_mask();
		current_fragment_program.two_sided_lighting = m_ctx->register_state->two_side_light_en();
		current_fragment_program.mrt_buffers_count = rsx::utility::get_mrt_buffers_count(m_ctx->register_state->surface_color_target());

		if (m_ctx->register_state->current_draw_clause.classify_mode() == primitive_class::polygon)
		{
			if (!backend_config.supports_normalized_barycentrics)
			{
				current_fragment_program.ctrl |= RSX_SHADER_CONTROL_ATTRIBUTE_INTERPOLATION;
			}

			if (m_ctx->register_state->polygon_stipple_enabled())
			{
				current_fragment_program.ctrl |= RSX_SHADER_CONTROL_POLYGON_STIPPLE;
			}

			current_fragment_program.ctrl |= get_fragment_program_export_config();
		}
		else if (m_ctx->register_state->point_sprite_enabled() &&
			m_ctx->register_state->current_draw_clause.primitive == primitive_type::points)
		{
			// Set high word of the control mask to store point sprite control
			current_fragment_program.texcoord_control_mask |= u32(m_ctx->register_state->point_sprite_control_mask()) << 16;
		}

		if (m_ctx->register_state->alpha_test_enabled())
		{
			current_fragment_program.ctrl |= RSX_SHADER_CONTROL_ALPHA_TEST;
		}

		if (m_ctx->register_state->msaa_alpha_to_coverage_enabled())
		{
			const bool is_multiple_samples = m_ctx->register_state->surface_antialias() != rsx::surface_antialiasing::center_1_sample;
			if (!backend_config.supports_hw_a2c || (!is_multiple_samples && !backend_config.supports_hw_a2c_1spp))
			{
				// Emulation required
				current_fragment_program.ctrl |= RSX_SHADER_CONTROL_ALPHA_TO_COVERAGE;
			}
		}

		// Check if framebuffer is actually an XRGB format and not a WZYX format
		switch (m_ctx->register_state->surface_color())
		{
		case rsx::surface_color_format::w16z16y16x16:
		case rsx::surface_color_format::w32z32y32x32:
		case rsx::surface_color_format::x32:
			// These behave very differently from "normal" formats.
			break;
		default:
			// Integer framebuffer formats. These can support sRGB output as well as some special rules for output quantization.
			current_fragment_program.ctrl |= RSX_SHADER_CONTROL_8BIT_FRAMEBUFFER;
			if (!(current_fragment_program.ctrl & CELL_GCM_SHADER_CONTROL_32_BITS_EXPORTS) && // Cannot output sRGB from 32-bit registers
				m_ctx->register_state->framebuffer_srgb_enabled())
			{
				current_fragment_program.ctrl |= RSX_SHADER_CONTROL_SRGB_FRAMEBUFFER;
			}
			if (m_ctx->register_state->surface_is_swizzle_remapped())
			{
				current_fragment_program.ctrl |= RSX_SHADER_CONTROL_ROP_OUTPUT_REMAP;
			}
			break;
		}

		const bool zeta_was_cyclic = m_graphics_state & rsx::zeta_address_is_cyclic;
		m_graphics_state.clear(rsx::zeta_address_is_cyclic);

		for (u32 textures_ref = current_fp_metadata.referenced_textures_mask, i = 0; textures_ref; textures_ref >>= 1, ++i)
		{
			if (!(textures_ref & 1)) continue;

			auto &tex = m_ctx->register_state->fragment_textures[i];
			current_fp_texture_state.clear(i);

			if (!tex.enabled() || sampler_descriptors[i]->format_class == RSX_FORMAT_CLASS_UNDEFINED)
			{
				continue;
			}

			std::memcpy(
				current_fragment_program.texture_params[i].scale,
				sampler_descriptors[i]->texcoord_xform.scale,
				sizeof(sampler_descriptors[i]->texcoord_xform.scale) * 2); // Copy scale and bias together

			current_fragment_program.texture_params[i].remap = tex.remap();

			m_graphics_state |= rsx::pipeline_state::fragment_texture_state_dirty;

			u32 texture_control = 0;
			current_fp_texture_state.set_dimension(sampler_descriptors[i]->image_type, i);

			if (sampler_descriptors[i]->texcoord_xform.clamp)
			{
				std::memcpy(
					current_fragment_program.texture_params[i].clamp_min,
					sampler_descriptors[i]->texcoord_xform.clamp_min,
					sizeof(sampler_descriptors[i]->texcoord_xform.clamp_min) * 2); // Copy clamp_min and clamp_max together

				texture_control |= (1 << rsx::texture_control_bits::CLAMP_TEXCOORDS_BIT);
			}

			if (tex.alpha_kill_enabled())
			{
				//alphakill can be ignored unless a valid comparison function is set
				texture_control |= (1 << texture_control_bits::ALPHAKILL);
				current_fragment_program.ctrl |= RSX_SHADER_CONTROL_TEXTURE_ALPHA_KILL;
			}

			//const u32 texaddr = rsx::get_address(tex.offset(), tex.location());
			const u32 raw_format = tex.format();
			const u32 format = raw_format & ~(CELL_GCM_TEXTURE_LN | CELL_GCM_TEXTURE_UN);

			if (raw_format & CELL_GCM_TEXTURE_UN)
			{
				if (tex.min_filter() == rsx::texture_minify_filter::nearest ||
					tex.mag_filter() == rsx::texture_magnify_filter::nearest)
				{
					// Subpixel offset so that (X + bias) * scale will round correctly.
					// This is done to work around fdiv precision issues in some GPUs (NVIDIA)
					// We apply the simplification where (x + bias) * z = xz + zbias here.
					constexpr auto subpixel_bias = 0.01f;
					current_fragment_program.texture_params[i].bias[0] += (subpixel_bias * current_fragment_program.texture_params[i].scale[0]);
					current_fragment_program.texture_params[i].bias[1] += (subpixel_bias * current_fragment_program.texture_params[i].scale[1]);
					current_fragment_program.texture_params[i].bias[2] += (subpixel_bias * current_fragment_program.texture_params[i].scale[2]);
				}
			}

			if (backend_config.supports_hw_msaa && sampler_descriptors[i]->samples > 1)
			{
				current_fp_texture_state.multisampled_textures |= (1 << i);
				texture_control |= (static_cast<u32>(tex.zfunc()) << texture_control_bits::DEPTH_COMPARE_OP);
				texture_control |= (static_cast<u32>(tex.mag_filter() != rsx::texture_magnify_filter::nearest) << texture_control_bits::FILTERED_MAG);
				texture_control |= (static_cast<u32>(tex.min_filter() != rsx::texture_minify_filter::nearest) << texture_control_bits::FILTERED_MIN);
				texture_control |= (((tex.format() & CELL_GCM_TEXTURE_UN) >> 6) << texture_control_bits::UNNORMALIZED_COORDS);

				if (rsx::is_texcoord_wrapping_mode(tex.wrap_s()))
				{
					texture_control |= (1 << texture_control_bits::WRAP_S);
				}

				if (rsx::is_texcoord_wrapping_mode(tex.wrap_t()))
				{
					texture_control |= (1 << texture_control_bits::WRAP_T);
				}

				if (rsx::is_texcoord_wrapping_mode(tex.wrap_r()))
				{
					texture_control |= (1 << texture_control_bits::WRAP_R);
				}
			}

			if (sampler_descriptors[i]->format_class != RSX_FORMAT_CLASS_COLOR)
			{
				switch (sampler_descriptors[i]->format_class)
				{
				case RSX_FORMAT_CLASS_DEPTH16_FLOAT:
				case RSX_FORMAT_CLASS_DEPTH24_FLOAT_X8_PACK32:
					texture_control |= (1 << texture_control_bits::DEPTH_FLOAT);
					break;
				default:
					break;
				}

				switch (format)
				{
				case CELL_GCM_TEXTURE_A8R8G8B8:
				case CELL_GCM_TEXTURE_D8R8G8B8:
				{
					// Emulate bitcast in shader
					current_fp_texture_state.redirected_textures |= (1 << i);
					const auto float_en = (sampler_descriptors[i]->format_class == RSX_FORMAT_CLASS_DEPTH24_FLOAT_X8_PACK32)? 1 : 0;
					texture_control |= (float_en << texture_control_bits::DEPTH_FLOAT);
					break;
				}
				case CELL_GCM_TEXTURE_X16:
				{
					// A simple way to quickly read DEPTH16 data without shadow comparison
					break;
				}
				case CELL_GCM_TEXTURE_DEPTH16:
				case CELL_GCM_TEXTURE_DEPTH24_D8:
				case CELL_GCM_TEXTURE_DEPTH16_FLOAT:
				case CELL_GCM_TEXTURE_DEPTH24_D8_FLOAT:
				{
					// Natively supported Z formats with shadow comparison feature
					const auto compare_mode = tex.zfunc();
					if (!tex.alpha_kill_enabled() &&
						compare_mode < rsx::comparison_function::always &&
						compare_mode > rsx::comparison_function::never)
					{
						current_fp_texture_state.shadow_textures |= (1 << i);
					}
					break;
				}
				default:
					rsx_log.error("Depth texture bound to pipeline with unexpected format 0x%X", format);
				}

				if (sampler_descriptors[i]->is_cyclic_reference &&
					m_framebuffer_layout.zeta_address != 0 &&
					!g_cfg.video.strict_rendering_mode &&
					g_cfg.video.shader_precision != gpu_preset_level::low)
				{
					m_graphics_state |= rsx::zeta_address_is_cyclic;

					if (!(current_fragment_program.ctrl & (CELL_GCM_SHADER_CONTROL_DEPTH_EXPORT | RSX_SHADER_CONTROL_META_USES_DISCARD | RSX_SHADER_CONTROL_EMULATE_DEPTH_COMPARE)) &&
						m_framebuffer_layout.zeta_write_enabled)
					{
						current_fragment_program.ctrl |= RSX_SHADER_CONTROL_DISABLE_EARLY_Z;
					}
				}
			}
			else if (!backend_config.supports_hw_renormalization /* &&
				tex.min_filter() == rsx::texture_minify_filter::nearest &&
				tex.mag_filter() == rsx::texture_magnify_filter::nearest*/)
			{
				// FIXME: This check should only apply to point-sampled textures. However, it severely regresses some games (id tech 5).
				// This is because even when filtering is active, the error from the PS3 texture expansion still applies.
				// A proper fix is to expand these formats into BGRA8 when high texture precision is required. That requires different GUI settings and inflation shaders, so it will be handled separately.

				switch (format)
				{
				case CELL_GCM_TEXTURE_A1R5G5B5:
				case CELL_GCM_TEXTURE_A4R4G4B4:
				case CELL_GCM_TEXTURE_D1R5G5B5:
				case CELL_GCM_TEXTURE_R5G5B5A1:
				case CELL_GCM_TEXTURE_R5G6B5:
				case CELL_GCM_TEXTURE_R6G5B5:
					texture_control |= (1 << texture_control_bits::RENORMALIZE);
					current_fragment_program.ctrl |= RSX_SHADER_CONTROL_TEXTURE_FORMAT_CONVERT;
					break;
				default:
					break;
				}
			}

			if (const auto& format_ex = sampler_descriptors[i]->format_ex; format_ex.features != 0)
			{
				texture_control |= format_ex.texel_remap_control;
				texture_control |= format_ex.features << texture_control_bits::FORMAT_FEATURES_OFFSET;

				if (format_ex.texel_remap_control)
				{
					current_fragment_program.ctrl |= RSX_SHADER_CONTROL_TEXTURE_FORMAT_CONVERT;
				}

				if (current_fp_metadata.bx2_texture_reads_mask)
				{
					current_fragment_program.ctrl |= RSX_SHADER_CONTROL_TEXTURE_FORMAT_CONVERT;

					const u32 remap_hi = tex.decoded_remap().shuffle_mask_bits(0xFu);
					current_fragment_program.texture_params[i].remap &= ~(0xFu << 16u);
					current_fragment_program.texture_params[i].remap |= (remap_hi << 16u);
				}
			}

			current_fragment_program.texture_params[i].control = texture_control;
		}

		// Update texture configuration
		current_fragment_program.texture_state.import(current_fp_texture_state, current_fp_metadata.referenced_textures_mask);

		//Sanity checks
		if (current_fragment_program.ctrl & CELL_GCM_SHADER_CONTROL_DEPTH_EXPORT)
		{
			//Check that the depth stage is not disabled
			if (!m_ctx->register_state->depth_test_enabled())
			{
				rsx_log.trace("FS exports depth component but depth test is disabled (INVALID_OPERATION)");
			}
		}

		m_program_cache_hint.invalidate_fragment_program(current_fragment_program);

		if (zeta_was_cyclic && zeta_was_cyclic != m_graphics_state.test(rsx::zeta_address_is_cyclic))
		{
			// Forced "fall-out" barrier. This is a special case for Z buffers because they can be cyclic without writes.
			// That condition can cause early-Z in a later call to introduce data hazard in previous cyclic draws.
			m_graphics_state |= rsx::zeta_address_cyclic_barrier;
		}
	}

	bool thread::invalidate_fragment_program(u32 dst_dma, u32 dst_offset, u32 size)
	{
		if (!current_fragment_program.total_length)
		{
			// No shader loaded
			return false;
		}

		const auto [shader_offset, shader_dma] = rsx::method_registers.shader_program_address();
		if ((dst_dma & CELL_GCM_LOCATION_MAIN) != shader_dma)
		{
			// Shader not loaded in XDR memory
			return false;
		}

		const auto current_fragment_shader_range = address_range32::start_length(shader_offset, current_fragment_program.total_length);
		if (!current_fragment_shader_range.overlaps(address_range32::start_length(dst_offset, size)))
		{
			// No range overlap
			return false;
		}

		// Data overlaps. Force ucode reload.
		m_graphics_state |= rsx::pipeline_state::fragment_program_ucode_dirty;
		return true;
	}

	void thread::reset()
	{
		rsx::method_registers.reset();
		check_zcull_status(false);
		nv4097::set_render_mode(m_ctx, 0, method_registers.registers[NV4097_SET_RENDER_ENABLE]);
		m_graphics_state |= pipeline_state::all_dirty;
	}

	void thread::init(u32 ctrlAddress)
	{
		dma_address = ctrlAddress;
		ctrl = vm::_ptr<RsxDmaControl>(ctrlAddress);
		flip_status = CELL_GCM_DISPLAY_FLIP_STATUS_DONE;
		fifo_ret_addr = RSX_CALL_STACK_EMPTY;

		vm::write32(device_addr + 0x30, 1);
		std::memset(display_buffers, 0, sizeof(display_buffers));

		rsx_thread_running = true;
	}

	std::pair<u32, u32> thread::calculate_memory_requirements(const vertex_input_layout& layout, u32 first_vertex, u32 vertex_count)
	{
		u32 persistent_memory_size = 0;
		u32 volatile_memory_size = 0;

		volatile_memory_size += ::size32(layout.referenced_registers) * 16u;

		if (rsx::method_registers.current_draw_clause.command == rsx::draw_command::inlined_array)
		{
			for (const auto &block : layout.interleaved_blocks)
			{
				volatile_memory_size += block->attribute_stride * vertex_count;
			}
		}
		else
		{
			//NOTE: Immediate commands can be index array only or both index array and vertex data
			//Check both - but only check volatile blocks if immediate_draw flag is set
			if (rsx::method_registers.current_draw_clause.is_immediate_draw)
			{
				for (const auto &info : layout.volatile_blocks)
				{
					volatile_memory_size += info.second;
				}
			}

			persistent_memory_size = layout.calculate_interleaved_memory_requirements(first_vertex, vertex_count);
		}

		return std::make_pair(persistent_memory_size, volatile_memory_size);
	}

	void thread::flip(const display_flip_info_t& info)
	{
		m_eng_interrupt_mask.clear(rsx::display_interrupt);

#ifdef __ANDROID__
		// ADPF feed: publish this frame's real CPU cost and the presenting thread's OS tid so
		// the app can drive PerformanceHintManager. Measured at a fixed point each iteration, so
		// the previous iteration's frame-limiter sleep lands in the idle delta and is excluded.
		// Advisory only: these go to atomics nothing in the core reads back.
		// Ported from ouroboros420/rpcsx (3d4ba6060).
		{
			static thread_local u64 s_last_now = 0;
			static thread_local u64 s_last_idle = 0;
			static thread_local s32 s_tid = 0;

			if (s_tid == 0)
			{
				s_tid = static_cast<s32>(::gettid());
			}

			// Republished every flip so a recreated RSX thread overwrites a stale tid, rather
			// than leaving the app's hint session pointed at a dead thread after a restart.
			rpcs3::utils::set_rsx_thread_tid(s_tid);

			const u64 now_us = get_system_time();
			const u64 idle_us = performance_counters.idle_time.load();

			if (s_last_now != 0 && now_us > s_last_now)
			{
				const u64 wall = now_us - s_last_now;

				// The flip-to-flip deadline. Without it the hint judges a 30fps game against a
				// 60fps target and over-boosts, which is pure heat.
				rpcs3::utils::report_frame_period_ns(wall * 1000);

				// idle_time is reset periodically by get_load(), so a delta that went backwards is
				// a reset, not a real frame. Idle can also exceed the wall window (it accrues from
				// FIFO/semaphore paths). Reporting work == wall in either case would feed a bogus
				// fully-busy sample and over-boost; skipping leaves the last good one in place.
				if (idle_us >= s_last_idle)
				{
					if (const u64 idle_delta = idle_us - s_last_idle; idle_delta < wall)
					{
						rpcs3::utils::report_frame_work_ns((wall - idle_delta) * 1000);
					}
				}
			}

			s_last_now = now_us;
			s_last_idle = idle_us;
		}
#endif

		if (async_flip_requested & flip_request::any)
		{
			// Deferred flip
			if (info.emu_flip)
			{
				async_flip_requested.clear(flip_request::emu_requested);
			}
			else
			{
				async_flip_requested.clear(flip_request::native_ui);
			}
		}

		if (info.emu_flip)
		{
			performance_counters.sampled_frames++;

			if (m_pause_after_x_flips && m_pause_after_x_flips-- == 1)
			{
				Emu.Pause();
			}
		}

		last_host_flip_timestamp = get_system_time();
	}

	void thread::check_zcull_status(bool framebuffer_swap)
	{
		const bool zcull_rendering_enabled = !!method_registers.registers[NV4097_SET_ZCULL_EN];
		const bool zcull_stats_enabled = !!method_registers.registers[NV4097_SET_ZCULL_STATS_ENABLE];
		const bool zcull_pixel_cnt_enabled = !!method_registers.registers[NV4097_SET_ZPASS_PIXEL_COUNT_ENABLE];

		if (framebuffer_swap)
		{
			zcull_surface_active = false;
			const u32 zeta_address = m_depth_surface_info.address;

			if (zeta_address)
			{
				//Find zeta address in bound zculls
				for (const auto& zcull : zculls)
				{
					if (zcull.bound &&
						rsx::to_surface_depth_format(zcull.zFormat) == m_depth_surface_info.depth_format &&
						rsx::to_surface_antialiasing(zcull.aaFormat) == rsx::method_registers.surface_antialias())
					{
						const u32 rsx_address = rsx::get_address(zcull.offset, CELL_GCM_LOCATION_LOCAL);
						if (rsx_address == zeta_address)
						{
							zcull_surface_active = true;
							break;
						}
					}
				}
			}
		}

		zcull_ctrl->set_enabled(this, zcull_rendering_enabled);
		zcull_ctrl->set_status(this, zcull_surface_active, zcull_pixel_cnt_enabled, zcull_stats_enabled);
	}

	void thread::clear_zcull_stats(u32 type)
	{
		zcull_ctrl->clear(this, type);
	}

	void thread::get_zcull_stats(u32 type, vm::addr_t sink)
	{
		u32 value = 0;
		if (!g_cfg.video.disable_zcull_queries)
		{
			switch (type)
			{
			case CELL_GCM_ZPASS_PIXEL_CNT:
			case CELL_GCM_ZCULL_STATS:
			case CELL_GCM_ZCULL_STATS1:
			case CELL_GCM_ZCULL_STATS2:
			case CELL_GCM_ZCULL_STATS3:
			{
				zcull_ctrl->read_report(this, sink, type);
				return;
			}
			default:
				rsx_log.error("Unknown zcull stat type %d", type);
				break;
			}
		}

		rsx::reservation_lock<true> lock(sink, 16);
		vm::_ptr<atomic_t<CellGcmReportData>>(sink)->store({timestamp(), value, 0});
	}

	u32 thread::copy_zcull_stats(u32 memory_range_start, u32 memory_range, u32 destination)
	{
		return zcull_ctrl->copy_reports_to(memory_range_start, memory_range, destination);
	}

	void thread::enable_conditional_rendering(vm::addr_t ref)
	{
		cond_render_ctrl.enable_conditional_render(this, ref);

		auto result = zcull_ctrl->find_query(ref, true);
		if (result.found)
		{
			if (!result.queries.empty())
			{
				cond_render_ctrl.set_eval_sources(result.queries);
				sync_hint(FIFO::interrupt_hint::conditional_render_eval, { .query = cond_render_ctrl.eval_sources.front(), .address = ref });
			}
			else
			{
				bool failed = (result.raw_zpass_result == 0);
				cond_render_ctrl.set_eval_result(this, failed);
			}
		}
		else
		{
			cond_render_ctrl.eval_result(this);
		}
	}

	void thread::disable_conditional_rendering()
	{
		cond_render_ctrl.disable_conditional_render(this);
	}

	void thread::begin_conditional_rendering(const std::vector<reports::occlusion_query_info*>& /*sources*/)
	{
		cond_render_ctrl.hw_cond_active = true;
		cond_render_ctrl.eval_sources.clear();
	}

	void thread::end_conditional_rendering()
	{
		cond_render_ctrl.hw_cond_active = false;
	}

	void thread::sync()
	{
		m_eng_interrupt_mask.clear(rsx::pipe_flush_interrupt);

		mm_flush();

		if (zcull_ctrl->has_pending())
		{
			zcull_ctrl->sync(this);
		}

		// Fragment constants may have been updated
		m_graphics_state |= rsx::pipeline_state::fragment_constants_dirty;

		// DMA sync; if you need this, don't use MTRSX
		// g_fxo->get<rsx::dma_manager>().sync();

		//TODO: On sync every sub-unit should finish any pending tasks
		//Might cause zcull lockup due to zombie 'unclaimed reports' which are not forcefully removed currently
		//ensure(async_tasks_pending.load() == 0);
	}

	void thread::sync_hint(FIFO::interrupt_hint /*hint*/, rsx::reports::sync_hint_payload_t payload)
	{
		zcull_ctrl->on_sync_hint(payload);
	}

	bool thread::is_fifo_idle() const
	{
		return ctrl == nullptr || ctrl->get == (ctrl->put & ~3);
	}

	void thread::flush_fifo()
	{
		// Make sure GET value is exposed before sync points
		fifo_ctrl->sync_get();
		fifo_ctrl->invalidate_cache();
	}

	std::pair<u32, u32> thread::try_get_pc_of_x_cmds_backwards(s32 count, u32 get) const
	{
		if (!ctrl || state & cpu_flag::exit)
		{
			return {0, umax};
		}

		if (!count)
		{
			return {0, get};
		}

		u32 true_get = ctrl->get;
		u32 start = last_known_code_start;

		RSXDisAsm disasm(cpu_disasm_mode::survey_cmd_size, vm::g_sudo_addr, 0, this);

		std::vector<u32> pcs_of_valid_cmds;

		if (get > start)
		{
			pcs_of_valid_cmds.reserve(std::min<u32>((get - start) / 16, 0x4000)); // Rough estimation of final array size
		}

		auto probe_code_region = [&](u32 probe_start) -> std::pair<u32, u32>
		{
			if (probe_start > get)
			{
				return {0, get};
			}

			pcs_of_valid_cmds.clear();
			pcs_of_valid_cmds.push_back(probe_start);

			usz index_of_get = umax;
			usz until = umax;

			while (pcs_of_valid_cmds.size() < until)
			{
				if (u32 advance = disasm.disasm(pcs_of_valid_cmds.back()))
				{
					pcs_of_valid_cmds.push_back(utils::add_saturate<u32>(pcs_of_valid_cmds.back(), advance));
				}
				else
				{
					break;
				}

				if (index_of_get == umax && pcs_of_valid_cmds.back() >= get)
				{
					index_of_get = pcs_of_valid_cmds.size() - 1;
					until = index_of_get + 1;

					if (count < 0 && pcs_of_valid_cmds.back() == get)
					{
						until -= count;
					}
				}
			}

			if (index_of_get == umax || pcs_of_valid_cmds[index_of_get] != get)
			{
				return {0, get};
			}

			if (count < 0)
			{
				const u32 found_cmds_count = static_cast<u32>(std::min<s64>(-count, pcs_of_valid_cmds.size() - 1LL - index_of_get));

				return {found_cmds_count, pcs_of_valid_cmds[index_of_get + found_cmds_count]};
			}

			const u32 found_cmds_count = std::min<u32>(count, ::size32(pcs_of_valid_cmds) - 1);

			return {found_cmds_count, *(pcs_of_valid_cmds.end() - 1 - found_cmds_count)};
		};

		auto pair = probe_code_region(start);

		if (!pair.first)
		{
			pair = probe_code_region(true_get);
		}

		return pair;
	}

	void thread::recover_fifo(std::source_location src_loc)
	{
		bool kill_itself = g_cfg.core.rsx_fifo_accuracy == rsx_fifo_mode::as_ps3;

		const u64 current_time = get_system_time();

		if (recovered_fifo_cmds_history.size() == 20u)
		{
			const auto cmd_info = recovered_fifo_cmds_history.front();

			// Check timestamp of last tracked cmd
			// Shorten the range of forbidden difference if driver wake-up delay is used
			if (current_time - cmd_info.timestamp < 2'000'000u - std::min<u32>(g_cfg.video.driver_wakeup_delay * 700, 1'400'000))
			{
				// Probably hopeless
				kill_itself = true;
			}

			// Erase the last command from history, keep the size of the queue the same
			recovered_fifo_cmds_history.pop();
		}

		if (kill_itself)
		{
			fmt::throw_exception("Dead FIFO commands queue state has been detected!"
				"\nTry increasing \"Driver Wake-Up Delay\" setting or setting \"RSX FIFO Accuracy\" to \"%s\", both in Advanced settings. Called from %s", std::min<rsx_fifo_mode>(rsx_fifo_mode{static_cast<u32>(g_cfg.core.rsx_fifo_accuracy.get()) + 1}, rsx_fifo_mode::atomic_ordered), src_loc);
		}

		// Error. Should reset the queue
		fifo_ctrl->set_get(restore_point);
		fifo_ret_addr = saved_fifo_ret;
		std::this_thread::sleep_for(2ms);
		fifo_ctrl->abort();

		if (std::exchange(in_begin_end, false) && !rsx::method_registers.current_draw_clause.empty())
		{
			execute_nop_draw();
			rsx::thread::end();
		}

		recovered_fifo_cmds_history.push({fifo_ctrl->last_cmd(), current_time});
	}

	void thread::dump_misc(std::string& ret, std::any& custom_data) const
	{
		cpu_thread::dump_misc(ret, custom_data);

		const auto flags = +state;

		if (is_paused(flags) && flags & cpu_flag::wait)
		{
			fmt::append(ret, "\nFragment Program Hash: %X.fp", current_fragment_program.get_data() ? program_hash_util::fragment_program_utils::get_fragment_program_ucode_hash(current_fragment_program) : 0);
			fmt::append(ret, "\nVertex Program Hash: %X.vp", current_vertex_program.data.empty() ? 0 : program_hash_util::vertex_program_utils::get_vertex_program_ucode_hash(current_vertex_program));
		}
		else
		{
			fmt::append(ret, "\n");
		}
	}

	std::vector<std::pair<u32, u32>> thread::dump_callstack_list() const
	{
		std::vector<std::pair<u32, u32>> result;

		if (u32 addr = fifo_ret_addr; addr != RSX_CALL_STACK_EMPTY)
		{
			result.emplace_back(addr, 0);
		}

		return result;
	}

	void thread::fifo_wake_delay(u64 div)
	{
		// TODO: Nanoseconds accuracy
		u64 remaining = g_cfg.video.driver_wakeup_delay;

		if (!remaining)
		{
			return;
		}

		// Some cases do not need full delay
		remaining = utils::aligned_div(remaining, div);
		const u64 until = get_system_time() + remaining;

		while (true)
		{
#ifdef __linux__
			// NOTE: Assumption that timer initialization has succeeded
			constexpr u64 host_min_quantum = 10;
#else
			// Host scheduler quantum for windows (worst case)
			// NOTE: On ps3 this function has very high accuracy
			constexpr u64 host_min_quantum = 500;
#endif
			if (remaining >= host_min_quantum)
			{
#ifdef __linux__
				thread_ctrl::wait_for(remaining, false);
#else
				// Wait on multiple of min quantum for large durations to avoid overloading low thread cpus
				thread_ctrl::wait_for(remaining - (remaining % host_min_quantum), false);
#endif
			}
			// TODO: Determine best value for yield delay
			else if (remaining >= host_min_quantum / 2)
			{
				std::this_thread::yield();
			}
			else
			{
				busy_wait(100);
			}

			const u64 current = get_system_time();

			if (current >= until)
			{
				break;
			}

			remaining = until - current;
		}
	}

	u32 thread::get_fifo_cmd() const
	{
		// Last fifo cmd for logging and utility
		return fifo_ctrl->last_cmd();
	}

	void invalid_method(context*, u32, u32);

	void thread::dump_regs(std::string& result, std::any& /*custom_data*/) const
	{
		if (ctrl)
		{
			fmt::append(result, "FIFO: GET=0x%07x, PUT=0x%07x, REF=0x%08x\n", +ctrl->get, +ctrl->put, +ctrl->ref);
		}

		for (u32 i = 0; i < 1 << 14; i++)
		{
			if (rsx::methods[i] == &invalid_method)
			{
				continue;
			}

			switch (i)
			{
			case NV4097_NO_OPERATION:
			case NV4097_INVALIDATE_L2:
			case NV4097_INVALIDATE_VERTEX_FILE:
			case NV4097_INVALIDATE_VERTEX_CACHE_FILE:
			case NV4097_INVALIDATE_ZCULL:
			case NV4097_WAIT_FOR_IDLE:
			case NV4097_PM_TRIGGER:
			case NV4097_ZCULL_SYNC:
				continue;

			case NV308A_COLOR:
			{
				i = NV3089_SET_OBJECT;
				continue;
			}
			default:
			{
				break;
			}
			}

			fmt::append(result, "[%04x] ", i);
			ensure(rsx::get_pretty_printing_function(i))(result, i, method_registers.registers[i]);
			result += '\n';
		}
	}

	flags32_t thread::read_barrier(u32 memory_address, u32 memory_range, bool unconditional)
	{
		flags32_t zcull_flags = (unconditional)? reports::sync_none : reports::sync_defer_copy;
		return zcull_ctrl->read_barrier(this, memory_address, memory_range, zcull_flags);
	}

	void thread::notify_zcull_info_changed()
	{
		check_zcull_status(false);
	}

	void thread::on_notify_memory_mapped(u32 address, u32 size)
	{
		// In the case where an unmap is followed shortly after by a remap of the same address space
		// we must block until RSX has invalidated the memory
		// or lock m_mtx_task and do it ourselves

		if (!rsx_thread_running)
			return;

		reader_lock lock(m_mtx_task);

		const auto map_range = address_range32::start_length(address, size);

		if (!m_invalidated_memory_range.valid())
			return;

		if (m_invalidated_memory_range.overlaps(map_range))
		{
			lock.upgrade();
			handle_invalidated_memory_range();
		}
	}

	void thread::on_notify_pre_memory_unmapped(u32 address, u32 size, std::vector<std::pair<u64, u64>>& event_data)
	{
		// Always flush MM if memory mapping is going to change.
		rsx::mm_flush();

		if (rsx_thread_running && address < rsx::constants::local_mem_base)
		{
			// Each bit represents io entry to be unmapped
			u64 unmap_status[512 / 64]{};

			for (u32 ea = address >> 20, end = ea + (size >> 20); ea < end; ea++)
			{
				const u32 io = std::rotl<u32>(iomap_table.io[ea], 32 - 20);

				if (io + 1)
				{
					unmap_status[io / 64] |= 1ull << (io & 63);
					iomap_table.io[ea].release(-1);
					iomap_table.ea[io].release(-1);
				}
			}

			auto& cfg = g_fxo->get<gcm_config>();

			std::optional<std::unique_lock<shared_mutex>> hle_lock;

			for (u32 i = 0; i < std::size(unmap_status); i++)
			{
				// TODO: Check order when sending multiple events
				if (u64 to_unmap = unmap_status[i])
				{
					if (isHLE)
					{
						if (!hle_lock)
						{
							hle_lock = std::unique_lock{cfg.gcmio_mutex};
						}

						int bit = 0;

						while (to_unmap)
						{
							bit = (std::countr_zero<u64>(std::rotl<u64>(to_unmap, 0 - bit)) + bit);
							to_unmap &= ~(1ull << bit);

							constexpr u16 null_entry = 0xFFFF;
							const u32 ea = std::exchange(cfg.offsetTable.eaAddress[(i * 64 + bit)], null_entry);

							if (ea < (rsx::constants::local_mem_base >> 20))
							{
								cfg.offsetTable.eaAddress[ea] = null_entry;
							}
						}

						continue;
					}

					// Each 64 entries are grouped by a bit
					const u64 io_event = SYS_RSX_EVENT_UNMAPPED_BASE << i;
					event_data.emplace_back(io_event, to_unmap);
				}
			}

			if (hle_lock)
			{
				hle_lock->unlock();
			}

			// Pause RSX thread momentarily to handle unmapping
			eng_lock elock(this);

			// Queue up memory invalidation
			std::lock_guard lock(m_mtx_task);
			const bool existing_range_valid = m_invalidated_memory_range.valid();
			const auto unmap_range = address_range32::start_length(address, size);

			if (existing_range_valid && m_invalidated_memory_range.touches(unmap_range))
			{
				// Merge range-to-invalidate in case of consecutive unmaps
				m_invalidated_memory_range.set_min_max(unmap_range);
			}
			else
			{
				if (existing_range_valid)
				{
					// We can only delay consecutive unmaps.
					// Otherwise, to avoid VirtualProtect failures, we need to do the invalidation here
					handle_invalidated_memory_range();
				}

				m_invalidated_memory_range = unmap_range;
			}

			m_eng_interrupt_mask |= rsx::memory_config_interrupt;
		}
	}

	void thread::on_notify_post_memory_unmapped(u64 event_data1, u64 event_data2)
	{
		if (!isHLE)
		{
			send_event(0, event_data1, event_data2);
		}
	}

	// NOTE: m_mtx_task lock must be acquired before calling this method
	void thread::handle_invalidated_memory_range()
	{
		AUDIT(!m_mtx_task.is_free());
		m_eng_interrupt_mask.clear(rsx::memory_config_interrupt);

		if (!m_invalidated_memory_range.valid())
		{
			return;
		}

		if (is_stopped())
		{
			// We only need to commit host-resident memory to the guest in case of savestates or captures.
			on_invalidate_memory_range(m_invalidated_memory_range, rsx::invalidation_cause::read);
		}

		// Host sync
		rsx::mm_flush();

		on_invalidate_memory_range(m_invalidated_memory_range, rsx::invalidation_cause::unmap);
		m_invalidated_memory_range.invalidate();
	}

	void thread::renderctl(u32 request_code, void* /*args*/)
	{
		switch (request_code)
		{
		case rsx::mm_backend_ctrl::cmd_mm_flush:
			rsx::mm_flush();
			break;
		default:
			fmt::throw_exception("Unknown backend request: 0x%x", request_code);
		}
	}

	//Pause/cont wrappers for FIFO ctrl. Never call this from rsx thread itself!
	void thread::pause()
	{
		external_interrupt_lock++;

		while (!external_interrupt_ack && !is_stopped())
		{
			utils::pause();
		}
	}

	void thread::unpause()
	{
		// TODO: Clean this shit up
		external_interrupt_lock--;
	}

	void thread::wait_pause()
	{
		do
		{
			if (g_cfg.video.multithreaded_rsx)
			{
				g_fxo->get<rsx::dma_manager>().sync();
			}

			external_interrupt_ack.store(true);

			while (external_interrupt_lock && (cpu_flag::ret - state))
			{
				// TODO: Investigate non busy-spinning method
				utils::pause();
			}

			external_interrupt_ack.store(false);
		}
		while (external_interrupt_lock && (cpu_flag::ret - state));
	}

	u32 thread::get_load()
	{
		// Average load over around 30 frames
		if (!performance_counters.last_update_timestamp || performance_counters.sampled_frames > 30)
		{
			const auto timestamp = get_system_time();
			const auto idle = performance_counters.idle_time.load();
			const auto elapsed = timestamp - performance_counters.last_update_timestamp;

			if (elapsed > idle)
				performance_counters.approximate_load = static_cast<u32>((elapsed - idle) * 100 / elapsed);
			else
				performance_counters.approximate_load = 0u;

			performance_counters.idle_time = 0;
			performance_counters.sampled_frames = 0;
			performance_counters.last_update_timestamp = timestamp;
		}

		return performance_counters.approximate_load;
	}

	void thread::on_frame_end(u32 buffer, bool forced)
	{
		// Cheap enough to re-read every frame, and being able to arm the profiler while a
		// slowdown is already happening matters more here than saving a config lookup.
		g_last_frame_time = get_system_time();
		g_frame_stall_reported = false;
		// Re-arm the dumps: a frame landed, so any later stall is a new one worth capturing.
		g_frame_stall_dumps = 0;

		prof::set_enabled(g_cfg.video.rsx_profiler.get());
		prof::tick_frame();

		bool pause_emulator = false;

		// MM sync. This is a pre-emptive operation, so we can use a deferred request.
		rsx::mm_flush_lazy();

		// Marks the end of a frame scope GPU-side
		if (g_user_asked_for_frame_capture.exchange(false) && !capture_current_frame)
		{
			capture_current_frame = true;
			frame_debug.reset();
			frame_capture.reset();

			// random number just to jumpstart the size
			frame_capture.replay_commands.reserve(8000);

			// capture first tile state with nop cmd
			rsx::frame_capture_data::replay_command replay_cmd;
			replay_cmd.rsx_command = std::make_pair(NV4097_NO_OPERATION, 0);
			frame_capture.replay_commands.push_back(std::move(replay_cmd));
			capture::capture_display_tile_state(this, frame_capture.replay_commands.back());
		}
		else if (capture_current_frame)
		{
			capture_current_frame = false;

			const std::string file_path = fs::get_config_dir() + "captures/" + (Emu.GetTitleID().empty() ? Emu.GetTitle() : Emu.GetTitleID()) + "_" + date_time::current_time_narrow() + "_capture.rrc.gz";

			fs::pending_file temp(file_path);

			utils::serial save_manager;

			if (temp.file)
			{
				save_manager.m_file_handler = make_compressed_serialization_file_handler(temp.file);
				save_manager(frame_capture);

				save_manager.m_file_handler->finalize(save_manager);

				if (temp.commit(false))
				{
					rsx_log.success("Capture successful: %s", file_path);
					frame_capture.reset();
					pause_emulator = true;
				}
				else
				{
					rsx_log.error("Capture failed: %s (%s)", file_path, fs::g_tls_error);
				}
			}
			else
			{
				rsx_log.fatal("Capture failed: %s (%s)", file_path, fs::g_tls_error);
			}
		}

		if (zcull_ctrl->has_pending())
		{
			// NOTE: This is a workaround for buggy games.
			// Some applications leave the zpass/stats gathering active but don't use the information.
			// This can lead to the zcull unit using up all the memory queueing up operations that never get consumed.
			// Seen in Diablo III and Yakuza 5
			zcull_ctrl->clear(this, CELL_GCM_ZPASS_PIXEL_CNT | CELL_GCM_ZCULL_STATS);
		}

		// Save current state
		m_queued_flip.stats = m_frame_stats;
		m_queued_flip.push(buffer);
		m_queued_flip.skip_frame = skip_current_frame;

		if (!forced) [[likely]]
		{
			if (!g_cfg.video.disable_FIFO_reordering)
			{
				// Try to enable FIFO optimizations
				// Only rarely useful for some games like RE4
				m_flattener.evaluate_performance(m_frame_stats.draw_calls);
			}

			if (g_cfg.video.frame_skip_enabled)
			{
				m_skip_frame_ctr++;

				if (m_skip_frame_ctr >= g_cfg.video.consecutive_frames_to_draw)
					m_skip_frame_ctr = -g_cfg.video.consecutive_frames_to_skip;

				skip_current_frame = (m_skip_frame_ctr < 0);
			}
		}
		else
		{
			if (!g_cfg.video.disable_FIFO_reordering)
			{
				// Flattener is unusable due to forced random flips
				m_flattener.force_disable();
			}

			if (g_cfg.video.frame_skip_enabled)
			{
				rsx_log.error("Frame skip is not compatible with this application");
			}
		}

		if (pause_emulator)
		{
			Emu.Pause();
			thread_ctrl::wait_for(30'000);
		}

		// Reset current stats
		m_frame_stats = {};
		m_profiler.enabled = !!g_cfg.video.debug_overlay;
	}

	f64 thread::get_cached_display_refresh_rate()
	{
		constexpr u64 uses_per_query = 512;

		f64 result = m_cached_display_rate;
		u64 count = m_display_rate_fetch_count++;

		while (true)
		{
			if (count % 512 == 0)
			{
				result = get_display_refresh_rate();
				m_cached_display_rate.store(result);
				m_display_rate_fetch_count += uses_per_query; // Notify users of the new value
				break;
			}

			const u64 new_count = m_display_rate_fetch_count;
			const f64 new_cached = m_cached_display_rate;

			if (result == new_cached && count / uses_per_query == new_count / uses_per_query)
			{
				break;
			}

			// An update might have gone through
			count = new_count;
			result = new_cached;
		}

		return result;
	}

	bool thread::request_emu_flip(u32 buffer)
	{
		if (is_current_thread()) // requested through command buffer
		{
			// NOTE: The flip will clear any queued flip requests
			handle_emu_flip(buffer);
		}
		else // requested 'manually' through ppu syscall
		{
			if (async_flip_requested & flip_request::emu_requested)
			{
				// ignore multiple requests until previous happens
				return true;
			}

			async_flip_buffer = buffer;
			async_flip_requested |= flip_request::emu_requested;

			m_eng_interrupt_mask |= rsx::display_interrupt;

			if (state & cpu_flag::exit)
			{
				// Resubmit possibly-ignored flip on savestate load
				return false;
			}
		}

		return true;
	}

	void thread::handle_emu_flip(u32 buffer)
	{
		if (m_queued_flip.in_progress)
		{
			// Rescursion not allowed!
			return;
		}

		if (!m_queued_flip.pop(buffer))
		{
			// Frame was not queued before flipping
			on_frame_end(buffer, true);
			ensure(m_queued_flip.pop(buffer));
		}

		double limit = 0.;
		const auto frame_limit = g_disable_frame_limit ? frame_limit_type::none : g_cfg.video.frame_limit;

		switch (frame_limit)
		{
		case frame_limit_type::none: limit = g_cfg.core.max_cpu_preempt_count_per_frame ? static_cast<double>(g_cfg.video.vblank_rate) : 0.; break;
		case frame_limit_type::_30: limit = 30.; break;
		case frame_limit_type::_50: limit = 50.; break;
		case frame_limit_type::_60: limit = 60.; break;
 		case frame_limit_type::_120: limit = 120.; break;
		case frame_limit_type::display_rate: limit = get_cached_display_refresh_rate(); break;
		case frame_limit_type::_auto: limit = static_cast<double>(g_cfg.video.vblank_rate); break;
		case frame_limit_type::_ps3: limit = 0.; break;
		case frame_limit_type::infinite: limit = 0.; break;
		default:
			break;
		}

		if (double limit2 = g_cfg.video.second_frame_limit; limit2 >= 0.1 && (limit2 < limit || !limit))
		{
			// Apply a second limit
			limit = limit2;
		}

		if (limit)
		{
			const u64 needed_us = static_cast<u64>(1000000 / limit);
			const u64 time = std::max<u64>(get_system_time(), target_rsx_flip_time > needed_us ? target_rsx_flip_time - needed_us : 0);

			if (int_flip_index)
			{
				if (target_rsx_flip_time > time + 1000)
				{
					const auto delay_us = target_rsx_flip_time - time;
					{
						RSX_PROF_SCOPE(idle);
						lv2_obj::wait_timeout(delay_us, nullptr, false);
					}
					performance_counters.idle_time += delay_us;
				}
			}

			target_rsx_flip_time = std::max(time, target_rsx_flip_time) + needed_us;
			flip_notification_count = 1;
		}
		else if (frame_limit == frame_limit_type::_ps3)
		{
			bool exit = false;

			if (vblank_at_flip == umax)
			{
				vblank_at_flip = +vblank_count;
				flip_notification_count = 1;
				exit = true;
			}

			if (requested_vsync && (exit || vblank_at_flip == vblank_count))
			{
				// Not yet signaled, handle it later
				async_flip_requested |= flip_request::emu_requested;
				async_flip_buffer = buffer;
				return;
			}

			vblank_at_flip = umax;
		}
		else
		{
			flip_notification_count = 1;
		}

		int_flip_index += flip_notification_count;

		current_display_buffer = buffer;
		m_queued_flip.emu_flip = true;
		m_queued_flip.in_progress = true;
		m_queued_flip.skip_frame |= g_cfg.video.disable_video_output && !g_cfg.video.perf_overlay.enabled;

		flip(m_queued_flip);

		last_guest_flip_timestamp = get_system_time() - 1000000;
		flip_status = CELL_GCM_DISPLAY_FLIP_STATUS_DONE;
		m_queued_flip.in_progress = false;

		while (flip_notification_count--)
		{
			if (!isHLE)
			{
				sys_rsx_context_attribute(0x55555555, 0xFEC, buffer, 0, 0, 0);

				if (unsent_gcm_events)
				{
					// TODO: A proper fix
					return;
				}

				continue;
			}

			if (auto ptr = flip_handler)
			{
				intr_thread->cmd_list
				({
					{ ppu_cmd::set_args, 1 }, u64{ 1 },
					{ ppu_cmd::lle_call, ptr },
					{ ppu_cmd::sleep, 0 }
				});

				intr_thread->cmd_notify.store(1);
				intr_thread->cmd_notify.notify_one();
			}
		}
	}

	void thread::evaluate_cpu_usage_reduction_limits()
	{
		const u64 max_preempt_count = g_cfg.core.max_cpu_preempt_count_per_frame;

		if (!max_preempt_count)
		{
			frame_times.clear();
			lv2_obj::set_yield_frequency(0, 0);
			return;
		}

		const u64 current_time = get_system_time();
		const u64 current_tsc = utils::get_tsc();
		u64 preempt_count = 0;

		if (frame_times.size() >= 60)
		{
			u64 diffs = 0;

			for (usz i = 1; i < frame_times.size(); i++)
			{
				const u64 cur_diff = frame_times[i].timestamp - frame_times[i - 1].timestamp;
				diffs += cur_diff;
			}

			const usz avg_frame_time = diffs / 59;

			u32 lowered_delay = 0;
			u32 raised_delay = 0;
			bool can_reevaluate = true;
			u64 prev_preempt_count = umax;

			for (usz i = frame_times.size() - 30; i < frame_times.size(); i++)
			{
				if (prev_preempt_count == umax)
				{
					prev_preempt_count = frame_times[i].preempt_count;
					continue;
				}

				if (prev_preempt_count != frame_times[i].preempt_count)
				{
					if (prev_preempt_count > frame_times[i].preempt_count)
					{
						lowered_delay++;
					}
					else if (prev_preempt_count < frame_times[i].preempt_count)
					{
						raised_delay++;
					}

					if (i > frame_times.size() - 30)
					{
						// Slow preemption count increase
						can_reevaluate = false;
					}
				}

				prev_preempt_count = frame_times[i].preempt_count;
			}

			preempt_count = std::min<u64>(frame_times.back().preempt_count, max_preempt_count);

			u32 fails = 0;
			u32 hard_fails = 0;
			bool is_last_frame_a_fail = false;

			auto abs_dst = [](u64 a, u64 b)
			{
				return a >= b ? a - b : b - a;
			};

			for (u32 i = 1; i <= frame_times.size(); i++)
			{
				const u64 cur_diff = (i == frame_times.size() ? current_time : frame_times[i].timestamp) - frame_times[i - 1].timestamp;

				if (const u64 diff_of_diff = abs_dst(cur_diff, avg_frame_time);
					diff_of_diff >= avg_frame_time / 7)
				{
					if (diff_of_diff >= avg_frame_time / 3)
					{
						raised_delay++;
						hard_fails++;

						if (i == frame_times.size())
						{
							is_last_frame_a_fail = true;
						}
					}

					if (fails != umax)
					{
						fails++;
					}
				}
			}

			bool hard_measures_taken = false;
			const usz fps_10 = 10'000'000 / avg_frame_time;

			auto lower_preemption_count = [&]()
			{
				if (preempt_count >= 10)
				{
					preempt_count -= 10;
				}
				else
				{
					preempt_count = 0;
				}

				if ((hard_fails > 2 || fails > 20) && is_last_frame_a_fail)
				{
					hard_measures_taken = preempt_count > 1;
					preempt_count = preempt_count * 7 / 8;
					prevent_preempt_increase_tickets = 10;
				}
				else
				{
					prevent_preempt_increase_tickets = std::max<u32>(7, prevent_preempt_increase_tickets);
				}
			};

			const u64 vblank_rate_10 = g_cfg.video.vblank_rate * 10;

			if (can_reevaluate)
			{
				const bool is_avg_fps_ok = (abs_dst(fps_10, 300) < 3 || abs_dst(fps_10, 600) < 4 || abs_dst(fps_10, vblank_rate_10) < 4 || abs_dst(fps_10, vblank_rate_10 / 2) < 3);

				if (!hard_fails && fails < 6 && is_avg_fps_ok)
				{
					if (prevent_preempt_increase_tickets)
					{
						prevent_preempt_increase_tickets--;
					}
					else
					{
						preempt_count = std::min<u64>(preempt_count + 4, max_preempt_count);
					}
				}
				else
				{
					lower_preemption_count();
				}
			}
			// Sudden FPS drop detection
			else if ((fails > 13 || hard_fails > 2 || !(abs_dst(fps_10, 300) < 20 || abs_dst(fps_10, 600) < 30 || abs_dst(fps_10, g_cfg.video.vblank_rate * 10) < 30 || abs_dst(fps_10, g_cfg.video.vblank_rate * 10 / 2) < 20)) && lowered_delay < raised_delay && is_last_frame_a_fail)
			{
				lower_preemption_count();
			}

			perf_log.trace("CPU preemption control: reeval=%d, preempt_count=%llu, fails=%u, hard=%u, avg_frame_time=%llu, highered=%u, lowered=%u, taken=%u", can_reevaluate, preempt_count, fails, hard_fails, avg_frame_time, raised_delay, lowered_delay, ::g_lv2_preempts_taken.load());

			if (hard_measures_taken)
			{
				preempt_fail_old_preempt_count = std::max<u64>(preempt_fail_old_preempt_count, std::min<u64>(frame_times.back().preempt_count, max_preempt_count));
			}
			else if (preempt_fail_old_preempt_count)
			{
				perf_log.error("Lowering current preemption count significantly due to a performance drop, if this issue persists frequently consider lowering max preemptions count to 'new-count' or lower. (old-count=%llu, new-count=%llu)", preempt_fail_old_preempt_count, preempt_count);
				preempt_fail_old_preempt_count = 0;
			}

			const u64 tsc_diff = (current_tsc - frame_times.back().tsc);
			const u64 time_diff = (current_time - frame_times.back().timestamp);
			const u64 preempt_diff = tsc_diff * (1'000'000 / 30) / (time_diff * std::max<u64>(preempt_count, 1ull));

			if (!preempt_count)
			{
				lv2_obj::set_yield_frequency(0, 0);
			}
			else if (abs_dst(fps_10, 300) < 30)
			{
				// Set an upper limit so a backoff technique would be taken if there is a sudden performance drop
				// Allow 4% of no yield to reduce significantly the risk of stutter
				lv2_obj::set_yield_frequency(preempt_diff, current_tsc + (tsc_diff * (1'000'000 * 96 / (30 * 100)) / time_diff));
			}
			else if (abs_dst(fps_10, 600) < 40)
			{
				// 5% for 60fps
				lv2_obj::set_yield_frequency(preempt_diff, current_tsc + (tsc_diff * (1'000'000 * 94 / (60 * 100)) / time_diff));
			}
			else if (abs_dst(fps_10, vblank_rate_10) < 40)
			{
				lv2_obj::set_yield_frequency(preempt_diff, current_tsc + (tsc_diff * (1'000'000 * 94 / (vblank_rate_10 * 10)) / time_diff));
			}
			else if (abs_dst(fps_10, vblank_rate_10 / 2) < 30)
			{
				lv2_obj::set_yield_frequency(preempt_diff, current_tsc + (tsc_diff * (1'000'000 * 96 / ((vblank_rate_10 / 2) * 10)) / time_diff));
			}
			else
			{
				// Undetected case, last 12% is with no yield
				lv2_obj::set_yield_frequency(preempt_diff, current_tsc + (tsc_diff * 88 / 100));
			}

			frame_times.pop_front();
		}
		else
		{
			lv2_obj::set_yield_frequency(0, 0);
		}

		frame_times.push_back(frame_time_t{preempt_count, current_time, current_tsc});
	}

	void vblank_thread::set_thread(std::shared_ptr<named_thread<std::function<void()>>> thread)
	{
		std::swap(m_thread, thread);
	}

	vblank_thread& vblank_thread::operator=(thread_state state)
	{
		if (m_thread)
		{
			*m_thread = state;
		}

		return *this;
	}
} // namespace rsx
