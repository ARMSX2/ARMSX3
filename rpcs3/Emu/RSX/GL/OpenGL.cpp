#include "stdafx.h"
#include "OpenGL.h"

#if defined(HAVE_WAYLAND)
#include <EGL/egl.h>
#endif

#ifdef _WIN32

extern "C"
{
	// NVIDIA Optimus: Default dGPU instead of iGPU (Driver: 302+)
	__declspec(dllexport) DWORD NvOptimusEnablement = 0x00000001;
	// AMD: Request dGPU High Performance (Driver: 13.35+)
	__declspec(dllexport) int AmdPowerXpressRequestHighPerformance = 1;
}

#define OPENGL_PROC(p, n) p gl##n = nullptr
#define WGL_PROC(p, n) p wgl##n = nullptr
#define OPENGL_PROC2(p, n, tn) OPENGL_PROC(p, n)
	#include "GLProcTable.h"
#undef OPENGL_PROC
#undef OPENGL_PROC2
#undef WGL_PROC
#endif

void gl::init()
{
#ifdef _WIN32
#define OPENGL_PROC(p, n) OPENGL_PROC2(p, gl##n, gl##n)
#define WGL_PROC(p, n) OPENGL_PROC2(p, wgl##n, wgl##n)
#define OPENGL_PROC2(p, n, tn) /*if(!gl##n)*/ if(!(n = reinterpret_cast<p>(wglGetProcAddress(#tn)))) rsx_log.error("OpenGL: initialization of " #tn " failed.")
#include "GLProcTable.h"
#undef OPENGL_PROC
#undef WGL_PROC
#undef OPENGL_PROC2
#endif
#if defined(RSX_GLES)
	// ARMSX3: no GLEW on Android. The EGL context has already been made current
	// by GraphicsFrame::set_current at this point, so the entry points can be
	// resolved now. A failure here means we were handed something that is not an
	// ES 3.2 implementation, which is fatal - every call after this is a null
	// function pointer.
	ensure(gl::es::load_procs(&gl::es::default_proc_loader), "Failed to load OpenGL ES entry points");
#elif defined(__unix__)
	glewExperimental = true;
	glewInit();
#endif
}

void gl::set_swapinterval(int interval)
{
#if defined(RSX_GLES)
	gl::es::set_swap_interval(interval);
#elif defined(_WIN32)
	wglSwapIntervalEXT(interval);
#elif defined(HAVE_X11)
	if (glXSwapIntervalEXT)
	{
		if (auto window = glXGetCurrentDrawable())
		{
			glXSwapIntervalEXT(glXGetCurrentDisplay(), window, interval);
			return;
		}
	}

#ifdef HAVE_WAYLAND
	if (auto egl_display = eglGetCurrentDisplay(); egl_display != EGL_NO_DISPLAY)
	{
		eglSwapInterval(egl_display, interval);
		return;
	}
#endif

	//No existing drawable or missing swap extension, EGL?
	rsx_log.error("Failed to set swap interval");
#else
	rsx_log.error("Swap control not implemented for this platform. Vsync options not available. (interval=%d)", interval);
#endif
}
