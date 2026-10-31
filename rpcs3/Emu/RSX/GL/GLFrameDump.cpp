#include "stdafx.h"
#include "GLGSRender.h"

#include "Emu/System.h"
#include "Emu/system_config.h"
#include "Emu/RSX/Overlays/overlay_message.h"
#include "Utilities/date_time.h"

#include <stb_image_write.h>

#include <cmath>
#include <cstring>

// Frame dumps on OpenGL: the dumps VKFrameDump.cpp makes (see rsx_frame_dump.h), read back with
// glReadPixels. Every readback waits for the GPU, so a pass-by-pass dump runs that frame slowly.

namespace
{
	using rsx::frame_dump::dump_mode;

	// The Vulkan dumps' limits, for the same reason: a handheld's card can be a few GB from full
	constexpr u32 max_images = 400;
	constexpr u64 max_bytes = 1536ull << 20; // PNG data per dump
	constexpr u64 min_free = 1024ull << 20;  // left free on the card

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

	u8 unorm8(f32 v)
	{
		return v <= 0.f ? 0 : (v >= 1.f ? 255 : static_cast<u8>(v * 255.f + 0.5f));
	}

	bool is_depth(gl::texture::internal_format format)
	{
		switch (format)
		{
		case gl::texture::internal_format::stencil8:
		case gl::texture::internal_format::depth16:
		case gl::texture::internal_format::depth32f:
		case gl::texture::internal_format::depth24_stencil8:
		case gl::texture::internal_format::depth32f_stencil8:
			return true;
		default:
			return false;
		}
	}

	bool is_float(gl::texture::internal_format format)
	{
		switch (format)
		{
		case gl::texture::internal_format::r32f:
		case gl::texture::internal_format::rg16f:
		case gl::texture::internal_format::rgba16f:
		case gl::texture::internal_format::rgba32f:
			return true;
		default:
			return false;
		}
	}

	// The readbacks go to client memory, so no pack buffer may be bound while they run
	struct pack_buffer_unbound
	{
		GLint previous = 0;

		pack_buffer_unbound()
		{
			glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &previous);

			if (previous)
			{
				glBindBuffer(GL_PIXEL_PACK_BUFFER, GL_NONE);
			}
		}

		~pack_buffer_unbound()
		{
			if (previous)
			{
				glBindBuffer(GL_PIXEL_PACK_BUFFER, previous);
			}
		}
	};

	std::vector<u8> encode_png(const std::vector<u8>& rgb, u32 width, u32 height)
	{
		std::vector<u8> png;

		stbi_write_png_to_func([](void* context, void* data, int size)
		{
			auto& out = *static_cast<std::vector<u8>*>(context);
			const u8* bytes = static_cast<const u8*>(data);
			out.insert(out.end(), bytes, bytes + size);
		}, &png, static_cast<int>(width), static_cast<int>(height), 3, rgb.data(), static_cast<int>(width * 3));

		return png;
	}
}

void GLGSRender::frame_dump_on_flip(const rsx::display_flip_info_t& info)
{
	m_frame_dump_flips++;

	if (m_frame_dump_flips == 1)
	{
		// A request left over from a game that was closed before it drew a frame. Not for this one.
		rsx::frame_dump::take_request();
	}

	auto& d = m_frame_dump;

	// The previous flip was to save the final image and the screen and never got that far: it
	// skipped the frame. Close the dump with what it has.
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

	// Only the game's own flips mark frames, as in VKFrameDump.cpp
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
		d.index = fmt::format("ARMSX3 frame dump (OpenGL)\nGame: %s\nMode: %s\n%s\n",
			Emu.GetTitleID().empty() && Emu.GetTitle().empty() ? std::string("(capture replay)") : fmt::format("%s %s", Emu.GetTitleID(), Emu.GetTitle()),
			mode_name(d.mode),
			d.mode == dump_mode::every_pass ? "Each pass is copied on the GPU as it finishes and read back after the frame, so the frame runs without waits.\n" : "");
		rsx_log.notice("Frame dump: dumping the next frame (%s) to %s", mode_name(d.mode), d.dir);
		return;
	}

	if (!d.active)
	{
		return;
	}

	// The frame ended here. In every_pass mode its last targets are still bound, so save them.
	if (d.mode == dump_mode::every_pass && m_draw_fbo && d.draws)
	{
		frame_dump_capture("end of frame");
	}

	frame_dump_write_snapshots();

	d.frame_draws += d.draws;
	d.frame_skipped += d.skipped;
	d.draws = d.skipped = 0;

	// This flip shows that frame: save what it shows, then what reached the screen.
	fmt::append(d.index, "The frame had %u draws, %u of them skipped because their program was not ready.\n\n", d.frame_draws, d.frame_skipped);
	d.active = false;
	d.take_final = true;
	d.take_screen = true;
}

void GLGSRender::frame_dump_capture(const char* why)
{
	auto& d = m_frame_dump;
	const auto& surfaces = res()->surface_cache;
	gl::command_context cmd{ gl_state };
	const usz first_snapshot = m_frame_dump_snapshots.size();

	for (u32 i = 0; i < rsx::limits::color_buffers_count; ++i)
	{
		const auto rtt = std::get<1>(surfaces.m_bound_render_targets[i]);

		if (!rtt)
		{
			continue;
		}

		if (is_depth(rtt->get_internal_format()) || rtt->samples() > 1)
		{
			// Listed, not read
			frame_dump_save(rtt, fmt::format("pass_a%u", i), why, d.fb_desc, d.draws, d.skipped);
			continue;
		}

		// Copied in order with the draws and read back after the frame, so the CPU never waits for the GPU
		// between passes. Any read of a target still makes the driver finish its pass there, which is how
		// these dumps hid South Park's missing objects on Adreno's GLES driver (see on_framebuffer_bound).
		auto copy = std::make_unique<gl::texture>(static_cast<GLenum>(rtt->get_target()), rtt->width(), rtt->height(), 1, 1, 1,
			static_cast<GLenum>(rtt->get_internal_format()), rtt->format_class());
		gl::g_hw_blitter->copy_image(cmd, rtt, copy.get(), position3u{}, position3u{}, size3u{ rtt->width(), rtt->height(), 1 });

		m_frame_dump_snapshots.push_back({ std::move(copy), fmt::format("pass_a%u", i), why, d.fb_desc, {}, d.draws, d.skipped });
	}

	if (const auto ds = std::get<1>(surfaces.m_bound_depth_stencil))
	{
		const std::string note = fmt::format("  (depth %ux%u, not saved)\n", ds->width(), ds->height());

		if (m_frame_dump_snapshots.size() > first_snapshot)
		{
			m_frame_dump_snapshots.back().after += note;
		}
		else
		{
			d.index += note;
		}
	}

	d.frame_draws += d.draws;
	d.frame_skipped += d.skipped;
	d.draws = d.skipped = 0;
}

void GLGSRender::frame_dump_write_snapshots()
{
	for (auto& snapshot : m_frame_dump_snapshots)
	{
		frame_dump_save(snapshot.tex.get(), snapshot.tag, snapshot.why.c_str(), snapshot.desc, snapshot.draws, snapshot.skipped);
		m_frame_dump.index += snapshot.after;
	}

	m_frame_dump_snapshots.clear();
}

void GLGSRender::frame_dump_save(gl::texture* tex, const std::string& tag, const char* why, const std::string& desc, u32 draws, u32 skipped)
{
	auto& d = m_frame_dump;

	if (!d.stop_reason.empty())
	{
		return;
	}

	const auto format = tex->get_internal_format();
	const u32 width = tex->width();
	const u32 height = tex->height();

	// Colour, single sample: depth and MSAA targets are listed but not saved
	if (is_depth(format) || tex->samples() > 1)
	{
		fmt::append(d.index, "  (%s: %s %ux%u, GL format 0x%x, not saved)\n", tag, is_depth(format) ? "depth" : "multisampled",
			width, height, static_cast<u32>(format));
		return;
	}

	static const gl::pixel_pack_settings pack_settings{};
	const pack_buffer_unbound unbound;

	const usz count = usz{width} * height;
	std::vector<u8> rgb(count * 3);
	u64 nan_or_inf = 0;

	if (is_float(format))
	{
		std::vector<f32> texels(count * 4);
		tex->copy_to(std::span<const u8>(reinterpret_cast<const u8*>(texels.data()), texels.size() * sizeof(f32)),
			gl::texture::format::rgba, gl::texture::type::f32, pack_settings);

		for (usz i = 0; i < count; i++)
		{
			const f32* texel = &texels[i * 4];

			if (!std::isfinite(texel[0]) || !std::isfinite(texel[1]) || !std::isfinite(texel[2]))
			{
				// Often the bug itself, so painted magenta and counted
				nan_or_inf++;
				rgb[i * 3 + 0] = 255;
				rgb[i * 3 + 1] = 0;
				rgb[i * 3 + 2] = 255;
				continue;
			}

			rgb[i * 3 + 0] = unorm8(texel[0]);
			rgb[i * 3 + 1] = unorm8(texel[1]);
			rgb[i * 3 + 2] = unorm8(texel[2]);
		}
	}
	else
	{
		std::vector<u8> texels(count * 4);
		tex->copy_to(std::span<const u8>(texels), gl::texture::format::rgba, gl::texture::type::ubyte, pack_settings);

		for (usz i = 0; i < count; i++)
		{
			std::memcpy(&rgb[i * 3], &texels[i * 4], 3);
		}
	}

	std::string line = draws == umax
		? fmt::format("%s: %s", why, desc)
		: fmt::format("%s: %u draw(s), %u skipped with their program not ready, into %s", why, draws, skipped, desc);
	fmt::append(line, "; %ux%u, GL format 0x%x", width, height, static_cast<u32>(format));

	if (nan_or_inf)
	{
		fmt::append(line, ", %u NaN or infinite texels shown in magenta", nan_or_inf);
	}

	frame_dump_write(fmt::format("%03u_%s_%ux%u.png", d.images, tag, width, height), rgb, width, height, line);
}

void GLGSRender::frame_dump_screen(u32 width, u32 height)
{
	if (!width || !height || !m_frame_dump.stop_reason.empty())
	{
		return;
	}

	// The window's back buffer before the swap, overlays included
	std::vector<u8> texels(usz{width} * height * 4);
	{
		const pack_buffer_unbound unbound;
		gl::screen.copy_to(texels.data(), coordi({}, { static_cast<int>(width), static_cast<int>(height) }), gl::texture::format::rgba, gl::texture::type::ubyte);
	}

	// The window's rows come bottom first
	std::vector<u8> rgb(usz{width} * height * 3);

	for (u32 y = 0; y < height; y++)
	{
		const u8* src = &texels[usz{height - 1 - y} * width * 4];
		u8* dst = &rgb[usz{y} * width * 3];

		for (u32 x = 0; x < width; x++)
		{
			std::memcpy(dst + x * 3, src + x * 4, 3);
		}
	}

	frame_dump_write(fmt::format("%03u_screen_%ux%u.png", m_frame_dump.images, width, height), rgb, width, height,
		fmt::format("screen: the window as presented (%ux%u)", width, height));
}

bool GLGSRender::frame_dump_write(const std::string& name, const std::vector<u8>& rgb, u32 width, u32 height, const std::string& line)
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

	const std::vector<u8> png = encode_png(rgb, width, height);

	if (png.empty())
	{
		fmt::append(d.index, "%s: could not be encoded\n", name);
		return false;
	}

	if (d.bytes + png.size() > max_bytes)
	{
		d.stop_reason = fmt::format("reached the limit of %u MB", static_cast<u32>(max_bytes >> 20));
		return false;
	}

	fs::file out(d.dir + name, fs::rewrite);

	if (!out)
	{
		d.stop_reason = fmt::format("could not write %s (%s)", name, fs::g_tls_error);
		return false;
	}

	out.write(png.data(), png.size());
	d.bytes += png.size();
	d.images++;

	fmt::append(d.index, "%s  %s\n", name, line);
	return true;
}

void GLGSRender::frame_dump_finish()
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

	// Shown relative to the data folder, which is how a user finds it
	std::string shown = d.dir;

	if (const std::string root = fs::get_config_dir(); shown.starts_with(root))
	{
		shown = "config/" + shown.substr(root.size());
	}

	rsx_log.notice("Frame dump: %u image(s), %u KB in %s%s", d.images, static_cast<u32>(d.bytes >> 10), d.dir,
		d.stop_reason.empty() ? std::string() : fmt::format(" (stopped early: %s)", d.stop_reason));
	rsx::overlays::queue_message(fmt::format("Frame dump saved: %u image(s) in %s", d.images, shown), 8'000'000);

	m_frame_dump_snapshots.clear();
	d = {};
}
