#include "stdafx.h"
#include "VKGSRender.h"
#include "VKRenderPass.h"
#include "vkutils/barriers.h"
#include "vkutils/buffer_object.h"
#include "vkutils/image_helpers.h"

#include "Emu/System.h"
#include "Emu/system_config.h"
#include "Emu/RSX/Overlays/overlay_message.h"
#include "Utilities/date_time.h"

#include <stb_image_write.h>

#include <bit>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>

// Frame dumps: see rsx_frame_dump.h for what they are and how they are asked for.

namespace rsx::frame_dump
{
	static atomic_t<u32> g_request{0};

	bool request(dump_mode mode)
	{
		// Only the Vulkan renderer implements this. Saying so is better than arming a dump that
		// never happens.
		if (mode == dump_mode::none || g_cfg.video.renderer != video_renderer::vulkan)
		{
			return false;
		}

		g_request = static_cast<u32>(mode);
		return true;
	}

	dump_mode take_request()
	{
		return static_cast<dump_mode>(g_request.exchange(0));
	}
}

namespace
{
	using rsx::frame_dump::dump_mode;

	// Limits, so a dump cannot fill the storage it writes to. A handheld's card can be a few GB from
	// full, and the first version of this tool, which had none, wrote 10 GB in ten minutes.
	constexpr u32 max_images = 400;
	constexpr u64 max_bytes = 1536ull << 20; // PNG data per dump
	constexpr u64 min_free = 1024ull << 20;  // left free on the card

	struct env_trigger
	{
		s64 flip = -1;
		dump_mode mode = dump_mode::every_pass;
	};

	// ARMSX3_DUMP_PASSES=<flip> (and ARMSX3_DUMP_PRESENT_ONLY=1), for scripted runs: the same
	// request the in-game menu makes, made at a fixed flip after boot.
	const env_trigger& get_env_trigger()
	{
		static const env_trigger trigger = []
		{
			env_trigger t;

			if (const char* env = std::getenv("ARMSX3_DUMP_PASSES"); env && *env)
			{
				t.flip = std::strtoll(env, nullptr, 10);
			}

			if (const char* env = std::getenv("ARMSX3_DUMP_PRESENT_ONLY"); env && *env == '1')
			{
				t.mode = dump_mode::final_image;
			}

			if (t.flip >= 0)
			{
				rsx_log.warning("Frame dump: ARMSX3_DUMP_PASSES asks for flip %d (%s)", t.flip,
					t.mode == dump_mode::final_image ? "final image" : "every pass");
			}

			return t;
		}();

		return trigger;
	}

	const char* mode_name(dump_mode mode)
	{
		return mode == dump_mode::every_pass ? "every render pass" : "final image";
	}

	// File names: the title ID, or the title, or "replay" for a capture replay, which has neither.
	std::string file_safe(std::string_view text)
	{
		std::string out;

		for (const char c : text)
		{
			const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.';
			out += ok ? c : '_';
		}

		return out;
	}

	f32 half_to_float(u16 h)
	{
		const u32 sign = (h & 0x8000u) << 16;
		const u32 exp = (h >> 10) & 0x1f;
		const u32 mant = h & 0x3ff;

		if (exp == 0)
		{
			const f32 v = std::ldexp(static_cast<f32>(mant), -24);
			return sign ? -v : v;
		}

		if (exp == 31)
		{
			return std::bit_cast<f32>(sign | 0x7f800000u | (mant << 13));
		}

		return std::bit_cast<f32>(sign | ((exp + 112) << 23) | (mant << 13));
	}

	// The unsigned 11- and 10-bit floats of B10G11R11: five exponent bits, no sign.
	f32 small_ufloat(u32 bits, u32 mant_bits)
	{
		const u32 exp = bits >> mant_bits;
		const f32 mant = static_cast<f32>(bits & ((1u << mant_bits) - 1)) / static_cast<f32>(1u << mant_bits);

		if (exp == 31)
		{
			return mant != 0.f ? std::numeric_limits<f32>::quiet_NaN() : std::numeric_limits<f32>::infinity();
		}

		return exp == 0 ? std::ldexp(mant, -14) : std::ldexp(1.f + mant, static_cast<int>(exp) - 15);
	}

	u8 unorm8(f32 v)
	{
		return v <= 0.f ? 0 : (v >= 1.f ? 255 : static_cast<u8>(v * 255.f + 0.5f));
	}

	struct rgb_image
	{
		std::vector<u8> rgb;
		u64 nan_or_inf = 0;
		const char* kind = "";
		bool decoded = true;
	};

	// One render target as something a person can look at: normalized formats as they are, floats
	// clamped to what a screen would show, alpha dropped. A NaN or infinity in any channel is painted
	// magenta and counted, since those are often the bug.
	rgb_image to_rgb8(VkFormat format, u32 bpp, const u8* src, u32 width, u32 height)
	{
		rgb_image out;
		const usz count = usz{width} * height;
		out.rgb.resize(count * 3);
		u8* dst = out.rgb.data();

		const auto put = [&](usz i, f32 r, f32 g, f32 b)
		{
			if (!std::isfinite(r) || !std::isfinite(g) || !std::isfinite(b))
			{
				out.nan_or_inf++;
				dst[i * 3 + 0] = 255;
				dst[i * 3 + 1] = 0;
				dst[i * 3 + 2] = 255;
				return;
			}

			dst[i * 3 + 0] = unorm8(r);
			dst[i * 3 + 1] = unorm8(g);
			dst[i * 3 + 2] = unorm8(b);
		};

		const auto read32 = [&](usz i)
		{
			u32 v;
			std::memcpy(&v, src + i * 4, 4);
			return v;
		};

		switch (format)
		{
		case VK_FORMAT_R8G8B8A8_UNORM:
		case VK_FORMAT_R8G8B8A8_SRGB:
		case VK_FORMAT_A8B8G8R8_UNORM_PACK32:
			out.kind = "RGBA8";
			for (usz i = 0; i < count; i++)
			{
				std::memcpy(dst + i * 3, src + i * 4, 3);
			}
			break;

		case VK_FORMAT_B8G8R8A8_UNORM:
		case VK_FORMAT_B8G8R8A8_SRGB:
			out.kind = "BGRA8";
			for (usz i = 0; i < count; i++)
			{
				dst[i * 3 + 0] = src[i * 4 + 2];
				dst[i * 3 + 1] = src[i * 4 + 1];
				dst[i * 3 + 2] = src[i * 4 + 0];
			}
			break;

		case VK_FORMAT_R16G16B16A16_SFLOAT:
		case VK_FORMAT_R16G16_SFLOAT:
		case VK_FORMAT_R16_SFLOAT:
		{
			const u32 n = format == VK_FORMAT_R16G16B16A16_SFLOAT ? 4 : (format == VK_FORMAT_R16G16_SFLOAT ? 2 : 1);
			out.kind = n == 4 ? "RGBA16F" : (n == 2 ? "RG16F" : "R16F");
			for (usz i = 0; i < count; i++)
			{
				u16 h[4]{};
				std::memcpy(h, src + i * n * 2, n * 2);
				const f32 r = half_to_float(h[0]);
				const f32 g = n > 1 ? half_to_float(h[1]) : r;
				const f32 b = n > 2 ? half_to_float(h[2]) : (n == 1 ? r : 0.f);
				put(i, r, g, b);
			}
			break;
		}

		case VK_FORMAT_R32G32B32A32_SFLOAT:
		case VK_FORMAT_R32_SFLOAT:
		{
			const u32 n = format == VK_FORMAT_R32G32B32A32_SFLOAT ? 4 : 1;
			out.kind = n == 4 ? "RGBA32F" : "R32F";
			for (usz i = 0; i < count; i++)
			{
				f32 f[4]{};
				std::memcpy(f, src + i * n * 4, n * 4);
				put(i, f[0], n > 1 ? f[1] : f[0], n > 1 ? f[2] : f[0]);
			}
			break;
		}

		case VK_FORMAT_B10G11R11_UFLOAT_PACK32:
			out.kind = "B10G11R11F";
			for (usz i = 0; i < count; i++)
			{
				const u32 v = read32(i);
				put(i, small_ufloat(v & 0x7ff, 6), small_ufloat((v >> 11) & 0x7ff, 6), small_ufloat(v >> 22, 5));
			}
			break;

		case VK_FORMAT_A2B10G10R10_UNORM_PACK32:
			out.kind = "A2B10G10R10";
			for (usz i = 0; i < count; i++)
			{
				const u32 v = read32(i);
				put(i, (v & 0x3ff) / 1023.f, ((v >> 10) & 0x3ff) / 1023.f, ((v >> 20) & 0x3ff) / 1023.f);
			}
			break;

		case VK_FORMAT_R5G6B5_UNORM_PACK16:
			out.kind = "R5G6B5";
			for (usz i = 0; i < count; i++)
			{
				u16 v;
				std::memcpy(&v, src + i * 2, 2);
				put(i, ((v >> 11) & 0x1f) / 31.f, ((v >> 5) & 0x3f) / 63.f, (v & 0x1f) / 31.f);
			}
			break;

		case VK_FORMAT_A1R5G5B5_UNORM_PACK16:
			out.kind = "A1R5G5B5";
			for (usz i = 0; i < count; i++)
			{
				u16 v;
				std::memcpy(&v, src + i * 2, 2);
				put(i, ((v >> 10) & 0x1f) / 31.f, ((v >> 5) & 0x1f) / 31.f, (v & 0x1f) / 31.f);
			}
			break;

		case VK_FORMAT_R8_UNORM:
			out.kind = "R8";
			for (usz i = 0; i < count; i++)
			{
				dst[i * 3 + 0] = dst[i * 3 + 1] = dst[i * 3 + 2] = src[i];
			}
			break;

		case VK_FORMAT_R8G8_UNORM:
			out.kind = "RG8";
			for (usz i = 0; i < count; i++)
			{
				dst[i * 3 + 0] = src[i * 2];
				dst[i * 3 + 1] = src[i * 2 + 1];
				dst[i * 3 + 2] = 0;
			}
			break;

		default:
			// Still worth a look: the first byte of each texel, as grey.
			out.kind = "undecoded";
			out.decoded = false;
			for (usz i = 0; i < count; i++)
			{
				dst[i * 3 + 0] = dst[i * 3 + 1] = dst[i * 3 + 2] = bpp ? src[i * bpp] : 0;
			}
			break;
		}

		return out;
	}

	std::vector<u8> encode_png(const rgb_image& image, u32 width, u32 height)
	{
		std::vector<u8> png;

		stbi_write_png_to_func([](void* context, void* data, int size)
		{
			auto& out = *static_cast<std::vector<u8>*>(context);
			const u8* bytes = static_cast<const u8*>(data);
			out.insert(out.end(), bytes, bytes + size);
		}, &png, static_cast<int>(width), static_cast<int>(height), 3, image.rgb.data(), static_cast<int>(width * 3));

		return png;
	}
}

void VKGSRender::frame_dump_on_flip(const rsx::display_flip_info_t& info)
{
	m_frame_dump_flips++;

	if (m_frame_dump_flips == 1)
	{
		// A request left over from before this renderer existed: asked for in a game that was
		// closed before it drew a frame. Not for this one.
		rsx::frame_dump::take_request();
	}

	if (const auto& env = get_env_trigger(); env.flip >= 0 && m_frame_dump_flips == static_cast<u64>(env.flip))
	{
		rsx::frame_dump::request(env.mode);
	}

	auto& d = m_frame_dump;

	// Copies the previous flip recorded and then returned without submitting (a failed swapchain
	// acquire): wait for them here, at the start of a flip, where nothing is half-presented yet.
	if (!m_frame_dump_pending.empty())
	{
		vk::insert_global_memory_barrier(*m_current_command_buffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
			VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT);
		flush_command_queue(true);
		frame_dump_write_pending();
	}

	// The previous flip was to save the final image or read the screen back and never got that far:
	// it skipped the frame, or had nothing to show. Close the dump with what it has.
	if (d.take_final || d.take_screen)
	{
		d.index += "The flip that was to show the dumped frame ended before saving it.\n";
		d.take_final = d.take_screen = false;
		frame_dump_finish();
	}

	// A request is taken at whatever flip comes next, but the dump starts at the game's next flip, so
	// the frame it covers is whole even when the button was pressed mid-frame or while paused.
	if (!d.armed && !d.active)
	{
		if (const dump_mode mode = rsx::frame_dump::take_request(); mode != dump_mode::none)
		{
			d = {};
			d.mode = mode;
			d.armed = true;
		}
	}

	// Only the game's own flips mark frames. The rest are native UI refreshes, which the emulator
	// presents whenever an overlay asks, in the middle of a frame too: a capture replay makes dozens
	// of them per frame while it copies memory between command chunks, and the performance overlay
	// asks for them in game. Taking those for frame ends cut the frame into pieces.
	if (!info.emu_flip)
	{
		return;
	}

	if (d.armed)
	{
		std::string title = Emu.GetTitleID();

		if (title.empty())
		{
			title = Emu.GetTitle().empty() ? "replay" : Emu.GetTitle();
		}

		d.armed = false;
		d.dir = fs::get_config_dir() + "captures/" + file_safe(title) + "_" + date_time::current_time_narrow() + "_frame/";

		if (!fs::create_path(d.dir))
		{
			rsx_log.error("Frame dump: could not create %s (%s)", d.dir, fs::g_tls_error);
			rsx::overlays::queue_message(fmt::format("Frame dump: could not create %s", d.dir), 8'000'000);
			d = {};
			return;
		}

		d.active = true;
		d.fb_desc = "(bound before the frame started)";
		d.index = fmt::format("ARMSX3 frame dump\nGame: %s\nMode: %s\n\n",
			Emu.GetTitleID().empty() && Emu.GetTitle().empty() ? std::string("(capture replay)") : fmt::format("%s %s", Emu.GetTitleID(), Emu.GetTitle()),
			mode_name(d.mode));
		rsx_log.notice("Frame dump: dumping the next frame (%s) to %s", mode_name(d.mode), d.dir);
		return;
	}

	if (!d.active)
	{
		return;
	}

	// The frame ended here. In every_pass mode its last target is still bound, so save it.
	if (d.mode == dump_mode::every_pass && m_draw_fbo && d.draws)
	{
		frame_dump_capture("end of frame");
	}

	d.frame_draws += d.draws;
	d.draws = 0;

	// This flip shows that frame: save what it shows, then what reached the screen.
	fmt::append(d.index, "The frame had %u draws.\n\n", d.frame_draws);
	d.active = false;
	d.take_final = true;
	d.take_screen = true;
}

void VKGSRender::frame_dump_capture(const char* why)
{
	std::vector<vk::image*> images;

	for (const auto& attachment : m_draw_fbo->attachments)
	{
		images.push_back(attachment->image());
	}

	frame_dump_save(images, why, m_frame_dump.fb_desc);
}

void VKGSRender::frame_dump_save(const std::vector<vk::image*>& images, const char* why, const std::string& desc)
{
	// Mid-frame, between passes: record, wait for the GPU, write. The wait is what makes every_pass
	// slow, and why it can hide races: every pass has finished before the next begins.
	frame_dump_record(images, "pass", why, desc);

	if (!m_frame_dump_pending.empty())
	{
		vk::insert_global_memory_barrier(*m_current_command_buffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
			VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT);
		flush_command_queue(true);
		frame_dump_write_pending();
	}

	m_frame_dump.frame_draws += m_frame_dump.draws;
	m_frame_dump.draws = 0;
}

void VKGSRender::frame_dump_record(const std::vector<vk::image*>& images, const char* tag, const char* why, const std::string& desc)
{
	auto& d = m_frame_dump;

	if (!d.stop_reason.empty())
	{
		return;
	}

	auto& cmd = *m_current_command_buffer;

	if (vk::is_renderpass_open(cmd))
	{
		vk::end_renderpass(cmd);
	}

	for (u32 i = 0; i < images.size(); ++i)
	{
		vk::image* img = images[i];
		const VkFormat format = img->format();

		// Colour, single sample: depth and MSAA targets are listed but not saved.
		if (vk::get_aspect_flags(format) != VK_IMAGE_ASPECT_COLOR_BIT || img->samples() > 1)
		{
			fmt::append(d.index, "  (attachment %u: %s, VkFormat %d, not saved)\n", i,
				vk::get_aspect_flags(format) != VK_IMAGE_ASPECT_COLOR_BIT ? "depth" : "multisampled", static_cast<int>(format));
			continue;
		}

		frame_dump_copy copy;
		copy.width = img->width();
		copy.height = img->height();
		copy.bpp = vk::get_format_texel_width(format);
		copy.format = format;
		copy.size = u64{copy.width} * copy.height * copy.bpp;
		copy.why = why;
		copy.desc = desc;
		copy.draws = std::strcmp(tag, "pass") == 0 ? d.draws : umax;
		copy.name = fmt::format("%03u_%s_a%u_%ux%u.png", d.images + static_cast<u32>(m_frame_dump_pending.size()), tag, i, copy.width, copy.height);
		copy.buf = std::make_unique<vk::buffer>(*m_device, copy.size,
			m_device->get_memory_mapping().host_visible_coherent, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
			VK_BUFFER_USAGE_TRANSFER_DST_BIT, 0, VMM_ALLOCATION_POOL_SYSTEM);

		img->push_layout(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

		VkBufferImageCopy region{};
		region.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
		region.imageExtent = { copy.width, copy.height, 1 };
		vkCmdCopyImageToBuffer(cmd, img->value, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, copy.buf->value, 1, &region);

		img->pop_layout(cmd);
		m_frame_dump_pending.push_back(std::move(copy));
	}
}

void VKGSRender::frame_dump_write_pending()
{
	// Caller's job: the GPU has finished the copies, and a TRANSFER -> HOST barrier made them visible.
	for (auto& p : m_frame_dump_pending)
	{
		frame_dump_write(p, p.buf->map(0, p.size));
		p.buf->unmap();
	}

	m_frame_dump_pending.clear();
}

bool VKGSRender::frame_dump_write(const frame_dump_copy& copy, const void* data)
{
	auto& d = m_frame_dump;

	if (!d.stop_reason.empty())
	{
		return false;
	}

	if (d.images >= max_images)
	{
		d.stop_reason = fmt::format("reached the limit of %u images", max_images);
		return false;
	}

	if (fs::device_stat stat{}; fs::statfs(d.dir, stat) && stat.avail_free < min_free)
	{
		d.stop_reason = fmt::format("less than %u MB would have been left free", static_cast<u32>(min_free >> 20));
		return false;
	}

	const rgb_image image = to_rgb8(copy.format, copy.bpp, static_cast<const u8*>(data), copy.width, copy.height);
	const std::vector<u8> png = encode_png(image, copy.width, copy.height);

	if (png.empty())
	{
		fmt::append(d.index, "%s: could not be encoded\n", copy.name);
		return false;
	}

	if (d.bytes + png.size() > max_bytes)
	{
		d.stop_reason = fmt::format("reached the limit of %u MB", static_cast<u32>(max_bytes >> 20));
		return false;
	}

	fs::file out(d.dir + copy.name, fs::rewrite);

	if (!out)
	{
		d.stop_reason = fmt::format("could not write %s (%s)", copy.name, fs::g_tls_error);
		return false;
	}

	out.write(png.data(), png.size());
	d.bytes += png.size();
	d.images++;

	if (copy.draws != umax)
	{
		fmt::append(d.index, "%s  %s: %u draw(s) into %s", copy.name, copy.why, copy.draws, copy.desc);
	}
	else
	{
		fmt::append(d.index, "%s  %s: %s", copy.name, copy.why, copy.desc);
	}

	fmt::append(d.index, "; %ux%u %s (VkFormat %d)", copy.width, copy.height, image.kind, static_cast<int>(copy.format));

	if (image.nan_or_inf)
	{
		fmt::append(d.index, ", %u NaN or infinite texels shown in magenta", image.nan_or_inf);
	}

	if (!image.decoded)
	{
		d.index += ", format not decoded: the first byte of each texel as grey";
	}

	d.index += "\n";
	return true;
}

void VKGSRender::frame_dump_finish()
{
	auto& d = m_frame_dump;

	if (!d.stop_reason.empty())
	{
		fmt::append(d.index, "\nStopped early: %s.\n", d.stop_reason);
	}

	if (!fs::write_file(d.dir + "frame.txt", fs::rewrite, d.index))
	{
		rsx_log.error("Frame dump: could not write %sframe.txt (%s)", d.dir, fs::g_tls_error);
	}

	// Shown relative to the data folder, which is how a user finds it.
	std::string shown = d.dir;

	if (const std::string root = fs::get_config_dir(); shown.starts_with(root))
	{
		shown = "config/" + shown.substr(root.size());
	}

	rsx_log.notice("Frame dump: %u image(s), %u KB in %s%s", d.images, static_cast<u32>(d.bytes >> 10), d.dir,
		d.stop_reason.empty() ? std::string() : fmt::format(" (stopped early: %s)", d.stop_reason));
	rsx::overlays::queue_message(fmt::format("Frame dump saved: %u image(s) in %s", d.images, shown), 8'000'000);

	d = {};
}
