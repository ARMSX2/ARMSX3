#pragma once

// ARMSX3: OpenGL ES 3.2 target for the RPCS3 GL backend.
//
// This file stands in for <GL/glew.h> on Android. It is pulled in from OpenGL.h,
// which is the only upstream file that had to change for any of this.
//
// Three jobs, in order of importance:
//
//  1. Entry points are FUNCTION POINTERS, not link-time symbols. Linking
//     libGLESv3.so would weld us to the system GLES driver forever: Android
//     resolves libEGL.so / libGLESv2.so through the public-library namespace, so
//     an APK cannot shadow those sonames. Every emulator that ships ANGLE
//     therefore dlopen()s it under a private soname (libEGL_angle.so) and
//     resolves each entry point by hand. gl::es::load_procs() is that hand, and
//     it is what makes the ANGLE switch a runtime choice.
//
//  2. Desktop-only entry points RPCS3 calls get an ES implementation here rather
//     than an #ifdef at each of the ~40 call sites. They live in namespace
//     gl::es and are hoisted to global scope with a using-directive, exactly the
//     trick glutils/ex.h already uses for glNamedBufferStorageEX - so unqualified
//     calls in upstream code bind to them with no upstream edit at all.
//
//     The DSA family (glNamedBuffer*, glTexture*) is pure sugar: bind, call,
//     restore. ES lost the sugar, not the capability. The ones that are NOT
//     sugar are called out individually below.
//
//  3. Enums, types and the handful of extension pointers ES needs.
//
// What is deliberately NOT here, because it cannot be faithfully emulated:
//   glLogicOp        - ES has no colour logic op at all. Stubbed, feature lost.
//   glPolygonMode    - ES has no wireframe fill mode. Stubbed, debug-only.
//   glDrawPixels     - compatibility-profile call; dead in this tree already.
//   GL_SAMPLES_PASSED - ES occlusion queries are boolean only. See capabilities.
// Each of those logs once on first use rather than silently doing nothing.

#if !defined(__ANDROID__)
#error OpenGL_ES.hpp is Android-only
#endif

#define RSX_GLES 1

// Prototypes off: we want variables named glFoo, not extern "C" declarations we
// could never point at ANGLE. The Khronos headers support this officially.
#define GL_GLES_PROTOTYPES 0

#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>

// Not every GL translation unit reaches this header via stdafx.h - glutils are
// included from state_tracker.hpp first - so do not assume <string> is in scope.
#include <string>

#undef GL_GLES_PROTOTYPES

// GLHelpers.cpp declares its debug callback `void APIENTRY log_debug(...)`.
// glew spells it APIENTRY, Khronos ES spells it GL_APIENTRY.
#ifndef APIENTRY
#define APIENTRY GL_APIENTRY
#endif

// ES has no doubles anywhere in the API. RPCS3 mentions them in a few
// desktop-only paths (glDepthRange, glVertexAttrib*d) that we shim below.
#ifndef GL_DOUBLE
using GLdouble = double;
using GLclampd = double;
#define GL_DOUBLE 0x140A
#endif

//
// ---- Enums ES does not define -----------------------------------------------
//
// Values are the canonical desktop GL values. Where ES has the same token under
// an _EXT/_OES suffix the extension header already defined it and we alias to
// that instead of redefining, so the two can never drift apart.
//

// Texture targets. ES has no 1D texture of any kind; see gl::es::TEXTURE_1D
// emulation notes in OpenGL_ES.cpp. The token has to exist for the switch
// statements in GLTexture.cpp / image.cpp to compile.
#ifndef GL_TEXTURE_1D
#define GL_TEXTURE_1D 0x0DE0
#endif

// Buffer storage (EXT_buffer_storage). Same numeric values as ARB_buffer_storage.
#ifndef GL_MAP_PERSISTENT_BIT
#define GL_MAP_PERSISTENT_BIT GL_MAP_PERSISTENT_BIT_EXT
#endif
#ifndef GL_MAP_COHERENT_BIT
#define GL_MAP_COHERENT_BIT GL_MAP_COHERENT_BIT_EXT
#endif
#ifndef GL_DYNAMIC_STORAGE_BIT
#define GL_DYNAMIC_STORAGE_BIT GL_DYNAMIC_STORAGE_BIT_EXT
#endif
#ifndef GL_CLIENT_STORAGE_BIT
#define GL_CLIENT_STORAGE_BIT GL_CLIENT_STORAGE_BIT_EXT
#endif
#ifndef GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT
#define GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT_EXT
#endif

// Sized normalised formats. EXT_texture_norm16 on Adreno/Mali; the fallback when
// the extension is missing is handled in capabilities, not here.
#ifndef GL_R16
#define GL_R16 GL_R16_EXT
#endif
#ifndef GL_RG16
#define GL_RG16 GL_RG16_EXT
#endif
#ifndef GL_RGBA16
#define GL_RGBA16 GL_RGBA16_EXT
#endif
#ifndef GL_R16_SNORM
#define GL_R16_SNORM GL_R16_SNORM_EXT
#endif
#ifndef GL_RG16_SNORM
#define GL_RG16_SNORM GL_RG16_SNORM_EXT
#endif
#ifndef GL_RGBA16_SNORM
#define GL_RGBA16_SNORM GL_RGBA16_SNORM_EXT
#endif

// BGRA. EXT_texture_format_BGRA8888 / APPLE_texture_format_BGRA8888 both define
// GL_BGRA_EXT with the desktop value. Note the extension only sanctions
// BGRA + UNSIGNED_BYTE; the packed-type combinations RSX wants are converted by
// the existing compute-shader upload path, not by the driver.
#ifndef GL_BGRA
#define GL_BGRA GL_BGRA_EXT
#endif
#ifndef GL_BGR
#define GL_BGR 0x80E0
#endif
// GL_BGRA8 / GL_BGR5_A1 are deliberately absent: glutils/image.h defines both
// itself, unconditionally, and its GL_BGR5_A1 is a made-up 0x99F0 rather than any
// real enumerant. Defining them here would silently disagree with upstream.

// Packed pixel types dropped in ES. Nothing in ES accepts these; they exist so
// GLTexture.cpp's format tables compile. gl::es::translate_pixel_type() maps
// them onto an ES-legal pair and reports whether a shuffle pass is needed.
#ifndef GL_UNSIGNED_BYTE_3_3_2
#define GL_UNSIGNED_BYTE_3_3_2 0x8032
#endif
#ifndef GL_UNSIGNED_BYTE_2_3_3_REV
#define GL_UNSIGNED_BYTE_2_3_3_REV 0x8362
#endif
#ifndef GL_UNSIGNED_SHORT_5_6_5_REV
#define GL_UNSIGNED_SHORT_5_6_5_REV 0x8364
#endif
#ifndef GL_UNSIGNED_SHORT_4_4_4_4_REV
#define GL_UNSIGNED_SHORT_4_4_4_4_REV 0x8365
#endif
#ifndef GL_UNSIGNED_SHORT_1_5_5_5_REV
#define GL_UNSIGNED_SHORT_1_5_5_5_REV 0x8366
#endif
#ifndef GL_UNSIGNED_INT_8_8_8_8
#define GL_UNSIGNED_INT_8_8_8_8 0x8035
#endif
#ifndef GL_UNSIGNED_INT_8_8_8_8_REV
#define GL_UNSIGNED_INT_8_8_8_8_REV 0x8367
#endif
#ifndef GL_UNSIGNED_INT_10_10_10_2
#define GL_UNSIGNED_INT_10_10_10_2 0x8036
#endif

// Pixel store parameters ES dropped. Verified unreachable in this tree: nothing
// calls pixel_pack_settings::swap_bytes/lsb_first/skip_images/image_height, so
// these are only ever set to their defaults. gl::es::glPixelStorei filters them.
#ifndef GL_PACK_SWAP_BYTES
#define GL_PACK_SWAP_BYTES 0x0D00
#endif
#ifndef GL_PACK_LSB_FIRST
#define GL_PACK_LSB_FIRST 0x0D01
#endif
#ifndef GL_PACK_IMAGE_HEIGHT
#define GL_PACK_IMAGE_HEIGHT 0x806C
#endif
#ifndef GL_PACK_SKIP_IMAGES
#define GL_PACK_SKIP_IMAGES 0x806B
#endif
#ifndef GL_UNPACK_SWAP_BYTES
#define GL_UNPACK_SWAP_BYTES 0x0CF0
#endif
#ifndef GL_UNPACK_LSB_FIRST
#define GL_UNPACK_LSB_FIRST 0x0CF1
#endif

// Enable/disable capabilities ES does not have. gl::es::glEnable/glDisable drop
// the ones that are decorative and route the ones that map (PRIMITIVE_RESTART).
#ifndef GL_MULTISAMPLE
#define GL_MULTISAMPLE 0x809D
#endif
#ifndef GL_SAMPLE_ALPHA_TO_ONE
#define GL_SAMPLE_ALPHA_TO_ONE 0x809F
#endif
#ifndef GL_LINE_SMOOTH
#define GL_LINE_SMOOTH 0x0B20
#endif
#ifndef GL_POLYGON_OFFSET_LINE
#define GL_POLYGON_OFFSET_LINE 0x2A02
#endif
#ifndef GL_POLYGON_OFFSET_POINT
#define GL_POLYGON_OFFSET_POINT 0x2A01
#endif
#ifndef GL_VERTEX_PROGRAM_POINT_SIZE
#define GL_VERTEX_PROGRAM_POINT_SIZE 0x8642
#endif
#ifndef GL_COLOR_LOGIC_OP
#define GL_COLOR_LOGIC_OP 0x0BF2
#endif
#ifndef GL_DEPTH_CLAMP
#define GL_DEPTH_CLAMP 0x864F
#endif
#ifndef GL_PRIMITIVE_RESTART
#define GL_PRIMITIVE_RESTART 0x8F9D
#endif
#ifndef GL_DEPTH_BOUNDS_TEST_EXT
#define GL_DEPTH_BOUNDS_TEST_EXT 0x8890
#endif

// Clip distances: EXT_clip_cull_distance, core-equivalent tokens.
#ifndef GL_CLIP_DISTANCE0
#define GL_CLIP_DISTANCE0 GL_CLIP_DISTANCE0_EXT
#endif

// Occlusion queries. ES has GL_ANY_SAMPLES_PASSED only - queries are boolean.
// Defining the token lets GLGSRender compile; capabilities.cpp forces
// precise_zpass_count off so it is never selected.
#ifndef GL_SAMPLES_PASSED
#define GL_SAMPLES_PASSED 0x8914
#endif

// Sampler state ES lacks.
#ifndef GL_TEXTURE_LOD_BIAS
#define GL_TEXTURE_LOD_BIAS 0x8501
#endif
#ifndef GL_TEXTURE_SWIZZLE_RGBA
#define GL_TEXTURE_SWIZZLE_RGBA 0x8E46
#endif
#ifndef GL_MIRROR_CLAMP_TO_EDGE
#define GL_MIRROR_CLAMP_TO_EDGE GL_MIRROR_CLAMP_TO_EDGE_EXT
#endif
#ifndef GL_MIRROR_CLAMP_EXT
#define GL_MIRROR_CLAMP_EXT 0x8742
#endif
#ifndef GL_MIRROR_CLAMP_TO_BORDER_EXT
#define GL_MIRROR_CLAMP_TO_BORDER_EXT 0x8912
#endif
#ifndef GL_TEXTURE_BORDER_VALUES_NV
#define GL_TEXTURE_BORDER_VALUES_NV 0x871A
#endif

// Polygon mode / clip control tokens, referenced by the state tracker.
#ifndef GL_FILL
#define GL_FILL 0x1B02
#endif
#ifndef GL_LOWER_LEFT
#define GL_LOWER_LEFT 0x8CA1
#endif
#ifndef GL_ZERO_TO_ONE
#define GL_ZERO_TO_ONE 0x935F
#endif

// AMD pinned memory. Gated behind AMD_pinned_memory_supported, never true here.
#ifndef GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD
#define GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD 0x9160
#endif

// glGetTexLevelParameteriv channel-size queries. ES 3.1 has these.
#ifndef GL_INTERNAL_FORMAT
#define GL_INTERNAL_FORMAT GL_TEXTURE_INTERNAL_FORMAT
#endif

//
// ---- Core ES entry points ---------------------------------------------------
//

#define GL_ES_PROC(ret, name, params)                 \
	using PFN_##name = ret(GL_APIENTRYP)params;       \
	extern PFN_##name name;
#include "OpenGL_ES_procs.inc"
GL_ES_CORE_PROCS
#undef GL_ES_PROC

namespace gl::es
{
	// Resolved from the EGL/GLES pair we were pointed at (system or ANGLE).
	// `loader` is normally eglGetProcAddress + dlsym on the GLES library.
	using proc_loader_t = void* (*)(const char*);

	// Returns false if any *core* ES 3.2 entry point is missing, which means we
	// were handed something that is not an ES 3.2 implementation.
	bool load_procs(proc_loader_t loader);

	// True once load_procs succeeded. Everything below is UB before that.
	bool procs_loaded();

	// Optional extensions, resolved by load_procs. Null when unavailable; call
	// sites must check, and capabilities.cpp mirrors them into caps flags.
	extern PFNGLBUFFERSTORAGEEXTPROC        p_glBufferStorageEXT;
	extern PFNGLTEXTUREVIEWEXTPROC          p_glTextureViewEXT;
	extern PFNGLMULTIDRAWARRAYSEXTPROC      p_glMultiDrawArraysEXT;
	extern PFNGLMULTIDRAWELEMENTSEXTPROC    p_glMultiDrawElementsEXT;
}

// EXT_debug_marker. RPCS3 null-tests this one before calling (glutils/common.h
// push_debug_label), so it has to stay a pointer rather than become a function -
// otherwise the test is -Waddress bait that is always true.
namespace gl::es
{
	extern PFNGLINSERTEVENTMARKEREXTPROC glInsertEventMarkerEXT;
}

//
// ---- Desktop entry points, reimplemented for ES ------------------------------
//
// Hoisted to global scope by the using-directive at the bottom so upstream's
// unqualified calls find them. Same mechanism as glutils/ex.h.
//

namespace gl::es
{
	// --- Direct State Access: bind, call, restore. Pure sugar in ES terms. ---
	//
	// We advertise EXT_direct_state_access, NOT ARB, and that choice is load
	// bearing. glutils/common.h's DSA_CALL macros take a `target` argument and
	// pass it to the EXT spelling while the ARB spelling drops it - because on
	// desktop an ARB-DSA texture object remembers its own target. ES has no such
	// object state, so an emulation of the ARB form would have nothing to bind
	// to. Taking the EXT branch keeps the target the emulation needs, and it also
	// routes the two ARB-only entry points (glGetTextureSubImage, cube-map
	// glTextureSubImage3D) onto upstream's own existing fallbacks.
	// capabilities.cpp sets the flags to match.

	void glNamedBufferDataEXT(GLuint buffer, GLsizeiptr size, const void* data, GLenum usage);
	void glNamedBufferSubDataEXT(GLuint buffer, GLintptr offset, GLsizeiptr size, const void* data);
	void glClearNamedBufferSubDataEXT(GLuint buffer, GLenum internalformat, GLintptr offset, GLsizeiptr size, GLenum format, GLenum type, const void* data);
	void* glMapNamedBufferRangeEXT(GLuint buffer, GLintptr offset, GLsizeiptr length, GLbitfield access);
	GLboolean glUnmapNamedBufferEXT(GLuint buffer);
	void glNamedCopyBufferSubDataEXT(GLuint readBuffer, GLuint writeBuffer, GLintptr readOffset, GLintptr writeOffset, GLsizeiptr size);

	void glTextureSubImage1DEXT(GLuint texture, GLenum target, GLint level, GLint xoffset, GLsizei width, GLenum format, GLenum type, const void* pixels);
	void glTextureSubImage2DEXT(GLuint texture, GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const void* pixels);
	void glTextureSubImage3DEXT(GLuint texture, GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, const void* pixels);
	void glCompressedTextureSubImage1DEXT(GLuint texture, GLenum target, GLint level, GLint xoffset, GLsizei width, GLenum format, GLsizei imageSize, const void* data);
	void glCompressedTextureSubImage2DEXT(GLuint texture, GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLsizei imageSize, const void* data);
	void glCompressedTextureSubImage3DEXT(GLuint texture, GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLsizei imageSize, const void* data);
	void glTextureParameteriEXT(GLuint texture, GLenum target, GLenum pname, GLint param);
	void glTextureParameterivEXT(GLuint texture, GLenum target, GLenum pname, const GLint* params);

	void glNamedFramebufferTextureEXT(GLuint framebuffer, GLenum attachment, GLuint texture, GLint level);
	GLenum glCheckNamedFramebufferStatusEXT(GLuint framebuffer, GLenum target);
	void glFramebufferDrawBuffersEXT(GLuint framebuffer, GLsizei n, const GLenum* bufs);
	void glFramebufferReadBufferEXT(GLuint framebuffer, GLenum src);

	// ARB-DSA spellings. Not selected at runtime (see above) but still named by
	// upstream inside `if (ARB_direct_state_access_supported)` branches, so they
	// have to exist. Where the ARB form carries enough information to be
	// implemented correctly it is - glGetTextureImage and glGetTextureSubImage
	// discover their own extents, glCopyNamedBufferSubData needs no target. The
	// two that do not - the target-less 3D uploads, which on desktop rely on the
	// texture object remembering whether it is 3D, 2D-array or a cube map - say so
	// and return rather than guessing.
	void glNamedBufferData(GLuint buffer, GLsizeiptr size, const void* data, GLenum usage);
	void glNamedBufferSubData(GLuint buffer, GLintptr offset, GLsizeiptr size, const void* data);
	void glClearNamedBufferSubData(GLuint buffer, GLenum internalformat, GLintptr offset, GLsizeiptr size, GLenum format, GLenum type, const void* data);
	GLboolean glUnmapNamedBuffer(GLuint buffer);
	void glCopyNamedBufferSubData(GLuint readBuffer, GLuint writeBuffer, GLintptr readOffset, GLintptr writeOffset, GLsizeiptr size);

	void glTextureSubImage1D(GLuint texture, GLint level, GLint xoffset, GLsizei width, GLenum format, GLenum type, const void* pixels);
	void glTextureSubImage2D(GLuint texture, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const void* pixels);
	void glTextureSubImage3D(GLuint texture, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, const void* pixels);
	void glCompressedTextureSubImage1D(GLuint texture, GLint level, GLint xoffset, GLsizei width, GLenum format, GLsizei imageSize, const void* data);
	void glCompressedTextureSubImage2D(GLuint texture, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLsizei imageSize, const void* data);
	void glCompressedTextureSubImage3D(GLuint texture, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLsizei imageSize, const void* data);
	void glTextureParameteri(GLuint texture, GLenum pname, GLint param);
	void glTextureParameteriv(GLuint texture, GLenum pname, const GLint* params);

	void glNamedFramebufferTexture(GLuint framebuffer, GLenum attachment, GLuint texture, GLint level);
	void glNamedFramebufferTextureLayer(GLuint framebuffer, GLenum attachment, GLuint texture, GLint level, GLint layer);
	void glNamedFramebufferTextureLayerEXT(GLuint framebuffer, GLenum attachment, GLuint texture, GLint level, GLint layer);
	GLenum glCheckNamedFramebufferStatus(GLuint framebuffer, GLenum target);
	void glNamedFramebufferDrawBuffers(GLuint framebuffer, GLsizei n, const GLenum* bufs);
	void glNamedFramebufferReadBuffer(GLuint framebuffer, GLenum src);

	// --- Not sugar. Real reimplementations. ---

	// ES has no glGetTexImage in any form. Read back through a scratch FBO +
	// glReadPixels; the caller's bound GL_PIXEL_PACK_BUFFER is honoured, which is
	// how RPCS3 uses it. Depth/stencil aspects cannot be read this way in ES and
	// log instead - callers for those formats already prefer the
	// cs_d24x8_to_ssbo / cs_rgba8_to_ssbo compute paths.
	//
	// Pointers, not functions: capabilities.cpp null-tests glGetTextureImage,
	// glTextureBufferRange, glNamedBufferStorage and glMapNamedBufferRange to
	// sniff out Intel drivers that under-report. That test has to stay a pointer
	// test.
	extern void (*glGetTextureImage)(GLuint texture, GLint level, GLenum format, GLenum type, GLsizei bufSize, void* pixels);
	void glGetTextureSubImage(GLuint texture, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, GLsizei bufSize, void* pixels);
	extern void (*glTextureBufferRange)(GLuint texture, GLenum internalformat, GLuint buffer, GLintptr offset, GLsizeiptr size);
	extern void (*glNamedBufferStorage)(GLuint buffer, GLsizeiptr size, const void* data, GLbitfield flags);
	extern void* (*glMapNamedBufferRange)(GLuint buffer, GLintptr offset, GLsizeiptr length, GLbitfield access);

	// EXT_texture_view when present. Without it, texture views are impossible in
	// ES and this reports failure so the caller can fall back to a copy.
	void glTextureView(GLuint texture, GLenum target, GLuint origtexture, GLenum internalformat, GLuint minlevel, GLuint numlevels, GLuint minlayer, GLuint numlayers);

	// EXT_buffer_storage when present, else immutable storage is unavailable and
	// capabilities reports ARB_buffer_storage_supported = false, which puts the
	// backend on its own legacy_ring_buffer path.
	void glBufferStorage(GLenum target, GLsizeiptr size, const void* data, GLbitfield flags);

	// ES has no texture barrier and no equivalent. glMemoryBarrier's texture-fetch
	// bit is NOT the same thing (it orders incoherent writes, not raster-order
	// framebuffer feedback). Emitted anyway as the closest available fence; the
	// backend already tolerates its absence - see GLGSRender.cpp's warning about
	// undefined feedback loops - and capabilities reports the caps flags false so
	// strict rendering mode does not rely on it.
	void glTextureBarrier();

	// 1D textures do not exist in ES. Emulated as height-1 2D: see the notes in
	// OpenGL_ES.cpp for what that costs.
	void glTexStorage1D(GLenum target, GLsizei levels, GLenum internalformat, GLsizei width);

	// Trivial type narrowing - ES is float-only.
	void glDepthRange(GLdouble n, GLdouble f);
	void glClearDepth(GLdouble d);

	// ES uses GL_PRIMITIVE_RESTART_FIXED_INDEX, whose implied index is exactly the
	// all-ones value RPCS3 passes here, so this is a genuine no-op rather than a
	// dropped feature. glEnable(GL_PRIMITIVE_RESTART) is redirected accordingly.
	void glPrimitiveRestartIndex(GLuint index);

	// EXT_multi_draw_arrays when present, else an explicit loop. Same result.
	void glMultiDrawArrays(GLenum mode, const GLint* first, const GLsizei* count, GLsizei drawcount);
	void glMultiDrawElements(GLenum mode, const GLsizei* count, GLenum type, const void* const* indices, GLsizei drawcount);

	// ES has only glGetQueryObjectuiv.
	void glGetQueryObjectiv(GLuint id, GLenum pname, GLint* params);

	// Fragment outputs are located by layout(location=) in the generated ES
	// shaders, which is the only mechanism ES has. No-op.
	void glBindFragDataLocation(GLuint program, GLuint colorNumber, const char* name);

	// --- Stubs for things ES genuinely cannot do. Each logs once. ---
	void glLogicOp(GLenum opcode);
	void glPolygonMode(GLenum face, GLenum mode);
	void glDrawPixels(GLsizei width, GLsizei height, GLenum format, GLenum type, const void* pixels);
	void glDepthBoundsEXT(GLclampd zmin, GLclampd zmax);
	void glDepthBoundsdNV(GLdouble zmin, GLdouble zmax);
	void glDepthRangedNV(GLdouble zNear, GLdouble zFar);
	void glVertexAttrib1d(GLuint index, GLdouble x);
	void glVertexAttrib2d(GLuint index, GLdouble x, GLdouble y);
	void glVertexAttrib3d(GLuint index, GLdouble x, GLdouble y, GLdouble z);
	void glVertexAttrib4d(GLuint index, GLdouble x, GLdouble y, GLdouble z, GLdouble w);

	// --- Filtered forwards ---
	//
	// Four core ES entry points need their arguments filtered rather than
	// replaced: glEnable/glDisable (capabilities ES does not have, plus the
	// GL_PRIMITIVE_RESTART -> GL_PRIMITIVE_RESTART_FIXED_INDEX redirect),
	// glPixelStorei (pixel-store params ES removed) and glTexParameteriv
	// (GL_TEXTURE_SWIZZLE_RGBA is one desktop call and four ES calls).
	//
	// They are NOT declared here. Declaring them in this namespace would make
	// `::glEnable` ambiguous against the global entry-point table, because of the
	// using-directive at the bottom of this file. Instead load_procs() resolves
	// the driver's real function into a private slot and overwrites the public
	// table entry with the filter, so every existing call site is intercepted
	// with no name games at all.

	// Bindless textures. No ES equivalent exists, at all. capabilities.cpp reports
	// ARB_bindless_texture_supported = false and GLGSRender already downgrades the
	// shader interpreter to the async recompiler when that is the case, so these
	// are unreachable by construction.
	//
	// They log and return rather than abort, deliberately: making them [[noreturn]]
	// makes clang deduce the same of every inline caller, and glutils/program.h's
	// handle64 operator= is one of those - which turns -Wmissing-noreturn into a
	// build break in an upstream header we do not want to touch. Returning 0 from
	// glGetTextureHandleARB also lands on image.h's existing
	// ensure(glGetTextureHandleARB(...)) with a message that says what happened.
	GLuint64 glGetTextureHandleARB(GLuint texture);
	void glMakeTextureHandleResidentARB(GLuint64 handle);
	void glMakeTextureHandleNonResidentARB(GLuint64 handle);
	void glProgramUniformHandleui64ARB(GLuint program, GLint location, GLuint64 value);
	void glProgramUniformHandleui64vARB(GLuint program, GLint location, GLsizei count, const GLuint64* values);

	// Two more ARB-DSA spellings capabilities.cpp null-tests, plus their EXT twins.
	// Pointers so `if (glGetTextureImageEXT && glTextureBufferRangeEXT)` stays a
	// pointer test.
	extern void (*glGetTextureImageEXT)(GLuint texture, GLenum target, GLint level, GLenum format, GLenum type, void* pixels);
	extern void (*glTextureBufferRangeEXT)(GLuint texture, GLenum target, GLenum internalformat, GLuint buffer, GLintptr offset, GLsizeiptr size);

	// NV texture barrier: same story as ARB. Never advertised, must compile.
	inline void glTextureBarrierNV() { glTextureBarrier(); }
}

using namespace ::gl::es;

//
// ---- EGL: library selection, context management, ANGLE ----------------------
//
// RPCS3's GL backend gets its context from GSFrameBase (make_context /
// set_current / flip). On desktop that is gl_gs_frame in rpcs3qt; on Android
// there is nothing, so the EGL side lives here and android/src/rpcsx-android.cpp
// forwards to it.
//
// ANGLE selection: if ARMSX3_ANGLE_EGL_LIBRARY names a readable file we dlopen
// that instead of the system libEGL, exactly as ARMSX2 does. It has to be a
// private soname (libEGL_angle.so) - Android resolves libEGL.so / libGLESv2.so
// through the public-library namespace, so an APK cannot shadow them.
//
// The env var being unset is the normal case and means "system GLES driver".
// If it IS set and the file is missing we say so loudly rather than falling back
// in silence: a silent fallback to the system driver is exactly the failure mode
// that made ARMSX2's ANGLE reports undiagnosable.
namespace gl::es
{
	enum class gl_backend
	{
		system,
		angle
	};

	// Opens libEGL + libGLESv2 (ANGLE's or the system's) and resolves the entry
	// points needed to make a context. Idempotent. Returns which one it got.
	gl_backend egl_initialize();

	// Which library the last egl_initialize() actually opened. For logging and
	// for the UI to tell the truth about whether ANGLE is in use.
	gl_backend active_backend();

	// The loader load_procs() wants: dlsym on the GLES library, falling back to
	// eglGetProcAddress. Both are needed - Android's eglGetProcAddress is only
	// guaranteed for extensions, and ANGLE only exports through its own handle.
	void* default_proc_loader(const char* name);

	// Context lifecycle. `native_window` is an ANativeWindow*. `share` may be
	// null; RPCS3 asks for one extra context per async shader-compiler thread and
	// they must share with the primary, which is why creation takes it.
	void* create_context(void* native_window, void* share_context);
	void  destroy_context(void* context);
	bool  make_current(void* context, void* native_window);
	void  swap_buffers();
	void  set_swap_interval(int interval);
}

// ---- Generated-GLSL retargeting ---------------------------------------------
//
// Rewrites a finished shader's #version / #extension / precision header for ES.
// Applied from gl::glsl::shader::precompile(), the one point every generated
// shader passes through. Implementation and its list of known gaps: GLSLES.cpp.
namespace gl::es
{
	void patch_glsl_for_es(std::string& source);
}
