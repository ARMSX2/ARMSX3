#pragma once

#include "util/types.hpp"
#include <vector>

#include "display.h"

class GSFrameBase
{
public:
	GSFrameBase() = default;
	GSFrameBase(const GSFrameBase&) = delete;
	virtual ~GSFrameBase() = default;

	virtual void close() = 0;
	virtual void reset() = 0;
	virtual bool shown() = 0;
	virtual void hide() = 0;
	virtual void show() = 0;
	virtual void toggle_fullscreen() = 0;

	virtual void delete_context(draw_context_t ctx) = 0;
	virtual draw_context_t make_context() = 0;
	virtual void set_current(draw_context_t ctx) = 0;
	virtual void flip(draw_context_t ctx, bool skip_frame = false) = 0;
	virtual int client_width() = 0;
	virtual int client_height() = 0;
	virtual f64 client_display_rate() = 0;
	virtual bool has_alpha() = 0;

	virtual display_handle_t handle() const = 0;

	// Bumped whenever handle() starts referring to a DIFFERENT native window.
	//
	// The swapchain is rebuilt when client_width()/client_height() stop matching it, and that is
	// the only trigger there is. A replacement window with the same dimensions is therefore
	// invisible: the swapchain stays bound to a window that is no longer on screen and the picture
	// goes black until something happens to change the size -- which is why rotating the device
	// "fixed" it. Platforms whose window cannot be swapped under a live swapchain keep the default.
	virtual u64 display_epoch() const { return 0; }

	virtual bool can_consume_frame() const = 0;
	virtual void present_frame(std::vector<u8>&& data, u32 pitch, u32 width, u32 height, bool is_bgra) const = 0;
	virtual void take_screenshot(std::vector<u8>&& sshot_data, u32 sshot_width, u32 sshot_height, bool is_bgra) = 0;

	virtual void update_title(double fps = 0.0) = 0;
};
