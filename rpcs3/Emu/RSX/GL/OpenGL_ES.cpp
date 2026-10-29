#include "stdafx.h"
#include "OpenGL.h"

#ifdef RSX_GLES

#include "util/asm.hpp"

#include <cstring>
#include <mutex>
#include <optional>
#include <unordered_set>
#include <vector>

#include <dlfcn.h>
#include <cstdlib>

#define EGL_EGL_PROTOTYPES 0
#include <EGL/egl.h>
#include <EGL/eglext.h>

//
// Definitions for the core ES entry-point table and the desktop-GL shims
// declared in OpenGL_ES.hpp. See that file for the rationale.
//

#define GL_ES_PROC(ret, name, params) PFN_##name name = nullptr;
#include "OpenGL_ES_procs.inc"
GL_ES_CORE_PROCS
#undef GL_ES_PROC

namespace gl::es
{
	PFNGLBUFFERSTORAGEEXTPROC     p_glBufferStorageEXT = nullptr;
	PFNGLTEXTUREVIEWEXTPROC       p_glTextureViewEXT = nullptr;
	PFNGLMULTIDRAWARRAYSEXTPROC   p_glMultiDrawArraysEXT = nullptr;
	PFNGLMULTIDRAWELEMENTSEXTPROC p_glMultiDrawElementsEXT = nullptr;
	PFNGLINSERTEVENTMARKEREXTPROC glInsertEventMarkerEXT = nullptr;

	static bool s_loaded = false;

	// User clip planes need EXT_clip_cull_distance, which ANGLE does not have here
	static bool s_has_clip_distance = false;

	static bool is_unsupported_clip_distance(GLenum cap)
	{
		return !s_has_clip_distance && cap >= GL_CLIP_DISTANCE0 && cap < GL_CLIP_DISTANCE0 + 8;
	}

	// Defined at the bottom: swaps four core entry points for argument-filtering
	// wrappers. See the "Filtered forwards" section.
	static void install_filters();

	bool procs_loaded()
	{
		return s_loaded;
	}

	bool load_procs(proc_loader_t loader)
	{
		if (!loader)
		{
			return false;
		}

		std::vector<const char*> missing;

#define GL_ES_PROC(ret, name, params)                                    \
	name = reinterpret_cast<PFN_##name>(loader(#name));                  \
	if (!name) missing.push_back(#name);
#include "OpenGL_ES_procs.inc"
		GL_ES_CORE_PROCS
#undef GL_ES_PROC

		if (!missing.empty())
		{
			// Not an ES 3.2 implementation. Say exactly what is absent rather than
			// crashing on the first null call thirty frames later.
			rsx_log.error("GLES: %u core entry points could not be resolved. First few:", ::size32(missing));
			for (usz i = 0; i < std::min<usz>(missing.size(), 8); ++i)
			{
				rsx_log.error("GLES:   %s", missing[i]);
			}

			return false;
		}

		// Extensions, taken only when the driver lists them; absence is expected and handled.
		// Android answers a lookup for any name in the Khronos headers whether the driver has it
		// or not, and calling one it lacks does nothing but print "called unimplemented OpenGL ES
		// API" to logcat. Adreno has texture views only as OES and no multi-draw at all, so asking
		// for the EXT names sent every texture view and every multi-draw down that hole.
		std::unordered_set<std::string> extensions;
		GLint extension_count = 0;
		glGetIntegerv(GL_NUM_EXTENSIONS, &extension_count);

		for (GLint i = 0; i < extension_count; ++i)
		{
			if (const auto name = reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, i)))
			{
				extensions.emplace(name);
			}
		}

		const auto load_if = [&](const char* extension, const char* name)
		{
			return extensions.contains(extension) ? loader(name) : nullptr;
		};

		p_glBufferStorageEXT = reinterpret_cast<PFNGLBUFFERSTORAGEEXTPROC>(load_if("GL_EXT_buffer_storage", "glBufferStorageEXT"));

		// OES and EXT spell texture views identically apart from the suffix
		p_glTextureViewEXT = reinterpret_cast<PFNGLTEXTUREVIEWEXTPROC>(load_if("GL_OES_texture_view", "glTextureViewOES"));
		if (!p_glTextureViewEXT)
		{
			p_glTextureViewEXT = reinterpret_cast<PFNGLTEXTUREVIEWEXTPROC>(load_if("GL_EXT_texture_view", "glTextureViewEXT"));
		}

		p_glMultiDrawArraysEXT = reinterpret_cast<PFNGLMULTIDRAWARRAYSEXTPROC>(load_if("GL_EXT_multi_draw_arrays", "glMultiDrawArraysEXT"));
		p_glMultiDrawElementsEXT = reinterpret_cast<PFNGLMULTIDRAWELEMENTSEXTPROC>(load_if("GL_EXT_multi_draw_arrays", "glMultiDrawElementsEXT"));
		glInsertEventMarkerEXT = reinterpret_cast<PFNGLINSERTEVENTMARKEREXTPROC>(load_if("GL_EXT_debug_marker", "glInsertEventMarkerEXT"));
		s_has_clip_distance = extensions.contains("GL_EXT_clip_cull_distance");

		install_filters();

		s_loaded = true;
		return true;
	}

	// ---------------------------------------------------------------------
	// Scoped rebind helpers. Every DSA shim below is "save, bind, call,
	// restore" - the operation ES kept, minus the sugar it dropped.
	// ---------------------------------------------------------------------

	static GLenum buffer_binding_of(GLenum target)
	{
		switch (target)
		{
		case GL_ARRAY_BUFFER:              return GL_ARRAY_BUFFER_BINDING;
		case GL_ELEMENT_ARRAY_BUFFER:      return GL_ELEMENT_ARRAY_BUFFER_BINDING;
		case GL_PIXEL_PACK_BUFFER:         return GL_PIXEL_PACK_BUFFER_BINDING;
		case GL_PIXEL_UNPACK_BUFFER:       return GL_PIXEL_UNPACK_BUFFER_BINDING;
		case GL_UNIFORM_BUFFER:            return GL_UNIFORM_BUFFER_BINDING;
		case GL_SHADER_STORAGE_BUFFER:     return GL_SHADER_STORAGE_BUFFER_BINDING;
		case GL_TEXTURE_BUFFER:            return GL_TEXTURE_BUFFER_BINDING;
		case GL_COPY_READ_BUFFER:          return GL_COPY_READ_BUFFER_BINDING;
		case GL_COPY_WRITE_BUFFER:         return GL_COPY_WRITE_BUFFER_BINDING;
		case GL_DRAW_INDIRECT_BUFFER:      return GL_DRAW_INDIRECT_BUFFER_BINDING;
		case GL_DISPATCH_INDIRECT_BUFFER:  return GL_DISPATCH_INDIRECT_BUFFER_BINDING;
		case GL_ATOMIC_COUNTER_BUFFER:     return GL_ATOMIC_COUNTER_BUFFER_BINDING;
		case GL_TRANSFORM_FEEDBACK_BUFFER: return GL_TRANSFORM_FEEDBACK_BUFFER_BINDING;
		default:                           return GL_COPY_WRITE_BUFFER_BINDING;
		}
	}

	static GLenum texture_binding_of(GLenum target)
	{
		switch (target)
		{
		// 1D is emulated as a height-1 2D texture; see glTexStorage1D.
		case GL_TEXTURE_1D:
		case GL_TEXTURE_2D:                       return GL_TEXTURE_BINDING_2D;
		case GL_TEXTURE_3D:                       return GL_TEXTURE_BINDING_3D;
		case GL_TEXTURE_2D_ARRAY:                 return GL_TEXTURE_BINDING_2D_ARRAY;
		case GL_TEXTURE_CUBE_MAP:                 return GL_TEXTURE_BINDING_CUBE_MAP;
		case GL_TEXTURE_BUFFER:                   return GL_TEXTURE_BINDING_BUFFER;
		case GL_TEXTURE_2D_MULTISAMPLE:           return GL_TEXTURE_BINDING_2D_MULTISAMPLE;
		case GL_TEXTURE_2D_MULTISAMPLE_ARRAY:     return GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY;
		default:                                  return GL_TEXTURE_BINDING_2D;
		}
	}

	// 1D textures do not exist in ES. Everything that names GL_TEXTURE_1D is
	// redirected to a height-1 GL_TEXTURE_2D. That is transparent at the API
	// layer; the shader side is handled by the ES shader generator, which emits
	// sampler2D for 1D samplers and pads the coordinate with 0.5.
	static GLenum real_texture_target(GLenum target)
	{
		return (target == GL_TEXTURE_1D) ? GL_TEXTURE_2D : target;
	}

	struct scoped_buffer_bind
	{
		GLenum m_target;
		GLint m_prev = 0;

		scoped_buffer_bind(GLenum target, GLuint buffer)
			: m_target(target)
		{
			glGetIntegerv(buffer_binding_of(target), &m_prev);
			glBindBuffer(target, buffer);
		}

		~scoped_buffer_bind()
		{
			glBindBuffer(m_target, static_cast<GLuint>(m_prev));
		}
	};

	struct scoped_texture_bind
	{
		GLenum m_target;
		GLint m_prev = 0;

		scoped_texture_bind(GLenum target, GLuint texture)
			: m_target(real_texture_target(target))
		{
			glGetIntegerv(texture_binding_of(m_target), &m_prev);
			glBindTexture(m_target, texture);
		}

		~scoped_texture_bind()
		{
			glBindTexture(m_target, static_cast<GLuint>(m_prev));
		}
	};

	struct scoped_fbo_bind
	{
		GLint m_prev_draw = 0;
		GLint m_prev_read = 0;

		explicit scoped_fbo_bind(GLuint fbo)
		{
			glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &m_prev_draw);
			glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &m_prev_read);
			glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		}

		~scoped_fbo_bind()
		{
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(m_prev_draw));
			glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(m_prev_read));
		}
	};

	// Scratch binding point for buffer DSA. GL_COPY_WRITE_BUFFER exists precisely
	// so drivers do not have to invalidate anything meaningful when you use it.
	static constexpr GLenum k_scratch_buffer_target = GL_COPY_WRITE_BUFFER;

	// ---------------------------------------------------------------------
	// Buffer DSA
	// ---------------------------------------------------------------------

	void glNamedBufferDataEXT(GLuint buffer, GLsizeiptr size, const void* data, GLenum usage)
	{
		const scoped_buffer_bind bind(k_scratch_buffer_target, buffer);
		glBufferData(k_scratch_buffer_target, size, data, usage);
	}

	void glNamedBufferSubDataEXT(GLuint buffer, GLintptr offset, GLsizeiptr size, const void* data)
	{
		const scoped_buffer_bind bind(k_scratch_buffer_target, buffer);
		glBufferSubData(k_scratch_buffer_target, offset, size, data);
	}

	static void impl_NamedBufferStorage(GLuint buffer, GLsizeiptr size, const void* data, GLbitfield flags)
	{
		const scoped_buffer_bind bind(k_scratch_buffer_target, buffer);
		glBufferStorage(k_scratch_buffer_target, size, data, flags);
	}

	void (*glNamedBufferStorage)(GLuint, GLsizeiptr, const void*, GLbitfield) = &impl_NamedBufferStorage;

	void glClearNamedBufferSubDataEXT(GLuint buffer, GLenum /*internalformat*/, GLintptr offset, GLsizeiptr size, GLenum /*format*/, GLenum /*type*/, const void* data)
	{
		// ES has no glClearBufferSubData. RPCS3 only ever calls this with a
		// GL_R32UI pattern (buffer_object.cpp fill()), so replicate the u32 host
		// side and upload it. Sizes here are small (label/scratch regions).
		const u32 pattern = data ? *static_cast<const u32*>(data) : 0u;
		const usz count = static_cast<usz>(size) / sizeof(u32);

		std::vector<u32> staging(count, pattern);

		const scoped_buffer_bind bind(k_scratch_buffer_target, buffer);
		glBufferSubData(k_scratch_buffer_target, offset, static_cast<GLsizeiptr>(count * sizeof(u32)), staging.data());
	}

	void* glMapNamedBufferRangeEXT(GLuint buffer, GLintptr offset, GLsizeiptr length, GLbitfield access)
	{
		const scoped_buffer_bind bind(k_scratch_buffer_target, buffer);
		return glMapBufferRange(k_scratch_buffer_target, offset, length, access);
	}

	void* (*glMapNamedBufferRange)(GLuint, GLintptr, GLsizeiptr, GLbitfield) = &glMapNamedBufferRangeEXT;

	GLboolean glUnmapNamedBufferEXT(GLuint buffer)
	{
		const scoped_buffer_bind bind(k_scratch_buffer_target, buffer);
		return glUnmapBuffer(k_scratch_buffer_target);
	}

	void glCopyNamedBufferSubData(GLuint readBuffer, GLuint writeBuffer, GLintptr readOffset, GLintptr writeOffset, GLsizeiptr size)
	{
		const scoped_buffer_bind src(GL_COPY_READ_BUFFER, readBuffer);
		const scoped_buffer_bind dst(GL_COPY_WRITE_BUFFER, writeBuffer);
		glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, readOffset, writeOffset, size);
	}

	void glNamedCopyBufferSubDataEXT(GLuint readBuffer, GLuint writeBuffer, GLintptr readOffset, GLintptr writeOffset, GLsizeiptr size)
	{
		glCopyNamedBufferSubData(readBuffer, writeBuffer, readOffset, writeOffset, size);
	}

	void glBufferStorage(GLenum target, GLsizeiptr size, const void* data, GLbitfield flags)
	{
		if (p_glBufferStorageEXT) [[likely]]
		{
			p_glBufferStorageEXT(target, size, data, flags);
			return;
		}

		// No EXT_buffer_storage. capabilities.cpp reports ARB_buffer_storage as
		// unsupported in that case, which routes the backend onto
		// legacy_ring_buffer, so this fallback exists only so a stray call cannot
		// leave a buffer unallocated.
		glBufferData(target, size, data, (flags & GL_MAP_WRITE_BIT) ? GL_DYNAMIC_DRAW : GL_STATIC_DRAW);
	}

	// ---------------------------------------------------------------------
	// Texture DSA
	// ---------------------------------------------------------------------

	void glTextureSubImage1DEXT(GLuint texture, GLenum /*target*/, GLint level, GLint xoffset, GLsizei width, GLenum format, GLenum type, const void* pixels)
	{
		const scoped_texture_bind bind(GL_TEXTURE_2D, texture);
		glTexSubImage2D(GL_TEXTURE_2D, level, xoffset, 0, width, 1, format, type, pixels);
	}

	void glTextureSubImage2DEXT(GLuint texture, GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const void* pixels)
	{
		// Cube faces are passed as the face enum, which is not a bindable target.
		const bool is_cube_face = (target >= GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL_TEXTURE_CUBE_MAP_NEGATIVE_Z);
		const scoped_texture_bind bind(is_cube_face ? GL_TEXTURE_CUBE_MAP : target, texture);
		glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
	}

	void glTextureSubImage3DEXT(GLuint texture, GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, const void* pixels)
	{
		const scoped_texture_bind bind(target, texture);
		glTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels);
	}

	void glCompressedTextureSubImage1DEXT(GLuint texture, GLenum /*target*/, GLint level, GLint xoffset, GLsizei width, GLenum format, GLsizei imageSize, const void* data)
	{
		const scoped_texture_bind bind(GL_TEXTURE_2D, texture);
		glCompressedTexSubImage2D(GL_TEXTURE_2D, level, xoffset, 0, width, 1, format, imageSize, data);
	}

	void glCompressedTextureSubImage2DEXT(GLuint texture, GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLsizei imageSize, const void* data)
	{
		const bool is_cube_face = (target >= GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL_TEXTURE_CUBE_MAP_NEGATIVE_Z);
		const scoped_texture_bind bind(is_cube_face ? GL_TEXTURE_CUBE_MAP : target, texture);
		glCompressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format, imageSize, data);
	}

	void glCompressedTextureSubImage3DEXT(GLuint texture, GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLsizei imageSize, const void* data)
	{
		const scoped_texture_bind bind(target, texture);
		glCompressedTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, imageSize, data);
	}

	static void impl_TextureBufferRangeEXT(GLuint texture, GLenum target, GLenum internalformat, GLuint buffer, GLintptr offset, GLsizeiptr size)
	{
		const scoped_texture_bind bind(target, texture);
		glTexBufferRange(GL_TEXTURE_BUFFER, internalformat, buffer, offset, size);
	}

	void (*glTextureBufferRangeEXT)(GLuint, GLenum, GLenum, GLuint, GLintptr, GLsizeiptr) = &impl_TextureBufferRangeEXT;

	// ARB spelling: no target, because a texture buffer can only ever be bound to
	// GL_TEXTURE_BUFFER. Unambiguous, so this one is a real implementation.
	static void impl_TextureBufferRange(GLuint texture, GLenum internalformat, GLuint buffer, GLintptr offset, GLsizeiptr size)
	{
		impl_TextureBufferRangeEXT(texture, GL_TEXTURE_BUFFER, internalformat, buffer, offset, size);
	}

	void (*glTextureBufferRange)(GLuint, GLenum, GLuint, GLintptr, GLsizeiptr) = &impl_TextureBufferRange;

	void glTextureParameteriEXT(GLuint texture, GLenum target, GLenum pname, GLint param)
	{
		const scoped_texture_bind bind(target, texture);
		glTexParameteri(real_texture_target(target), pname, param);
	}

	void glTextureParameterivEXT(GLuint texture, GLenum target, GLenum pname, const GLint* params)
	{
		const scoped_texture_bind bind(target, texture);
		glTexParameteriv(real_texture_target(target), pname, params);
	}

	// ARB-DSA 3D uploads. These take no target because on desktop the texture
	// object remembers whether it is 3D, a 2D array or a cube map - state ES
	// simply does not keep. Nothing can be bound, so nothing can be uploaded.
	// Unreachable at runtime: capabilities.cpp advertises EXT_direct_state_access
	// and not ARB, which sends every one of these call sites down the EXT branch
	// that does carry a target. Say so rather than guessing a target and silently
	// corrupting a cube face.
	static void report_arb_dsa(const char* fn)
	{
		rsx_log.fatal("GLES: %s was called, but ARB_direct_state_access is not advertised on ES "
			"(the EXT spelling is, because it carries the texture target ES needs). "
			"This is a bug in the caller's capability check.", fn);
	}

	void glTextureSubImage3D(GLuint, GLint, GLint, GLint, GLint, GLsizei, GLsizei, GLsizei, GLenum, GLenum, const void*)
	{
		report_arb_dsa("glTextureSubImage3D");
	}

	void glCompressedTextureSubImage3D(GLuint, GLint, GLint, GLint, GLint, GLsizei, GLsizei, GLsizei, GLenum, GLsizei, const void*)
	{
		report_arb_dsa("glCompressedTextureSubImage3D");
	}

	// ---------------------------------------------------------------------
	// Framebuffer DSA
	// ---------------------------------------------------------------------

	void glNamedFramebufferTextureEXT(GLuint framebuffer, GLenum attachment, GLuint texture, GLint level)
	{
		const scoped_fbo_bind bind(framebuffer);
		glFramebufferTexture(GL_FRAMEBUFFER, attachment, texture, level);
	}

	GLenum glCheckNamedFramebufferStatusEXT(GLuint framebuffer, GLenum target)
	{
		const scoped_fbo_bind bind(framebuffer);
		return glCheckFramebufferStatus(target);
	}

	void glFramebufferDrawBuffersEXT(GLuint framebuffer, GLsizei n, const GLenum* bufs)
	{
		const scoped_fbo_bind bind(framebuffer);
		glDrawBuffers(n, bufs);
	}

	void glFramebufferReadBufferEXT(GLuint framebuffer, GLenum src)
	{
		const scoped_fbo_bind bind(framebuffer);
		glReadBuffer(src);
	}

	// ---------------------------------------------------------------------
	// ARB-DSA spellings.
	//
	// glutils/common.h's DSA_CALL macros are runtime if/else, not #if, so BOTH
	// branches are compiled even though only the EXT one is ever taken on ES.
	// Where the ARB form carries enough information it gets a real body; where it
	// does not - because desktop relies on the texture object remembering its own
	// target - it says so instead of guessing.
	// ---------------------------------------------------------------------

	void glNamedBufferData(GLuint buffer, GLsizeiptr size, const void* data, GLenum usage)
	{
		glNamedBufferDataEXT(buffer, size, data, usage);
	}

	void glNamedBufferSubData(GLuint buffer, GLintptr offset, GLsizeiptr size, const void* data)
	{
		glNamedBufferSubDataEXT(buffer, offset, size, data);
	}

	void glClearNamedBufferSubData(GLuint buffer, GLenum internalformat, GLintptr offset, GLsizeiptr size, GLenum format, GLenum type, const void* data)
	{
		glClearNamedBufferSubDataEXT(buffer, internalformat, offset, size, format, type, data);
	}

	GLboolean glUnmapNamedBuffer(GLuint buffer)
	{
		return glUnmapNamedBufferEXT(buffer);
	}

	// 1D is emulated as height-1 2D, so the target is never in doubt.
	void glTextureSubImage1D(GLuint texture, GLint level, GLint xoffset, GLsizei width, GLenum format, GLenum type, const void* pixels)
	{
		glTextureSubImage1DEXT(texture, GL_TEXTURE_1D, level, xoffset, width, format, type, pixels);
	}

	void glCompressedTextureSubImage1D(GLuint texture, GLint level, GLint xoffset, GLsizei width, GLenum format, GLsizei imageSize, const void* data)
	{
		glCompressedTextureSubImage1DEXT(texture, GL_TEXTURE_1D, level, xoffset, width, format, imageSize, data);
	}

	// The 2D call sites in this tree all pass GL_TEXTURE_2D on the EXT side, so
	// the ARB form is unambiguous too.
	void glTextureSubImage2D(GLuint texture, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const void* pixels)
	{
		glTextureSubImage2DEXT(texture, GL_TEXTURE_2D, level, xoffset, yoffset, width, height, format, type, pixels);
	}

	void glCompressedTextureSubImage2D(GLuint texture, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLsizei imageSize, const void* data)
	{
		glCompressedTextureSubImage2DEXT(texture, GL_TEXTURE_2D, level, xoffset, yoffset, width, height, format, imageSize, data);
	}

	// Framebuffer DSA never had a target to lose.
	void glNamedFramebufferTexture(GLuint framebuffer, GLenum attachment, GLuint texture, GLint level)
	{
		glNamedFramebufferTextureEXT(framebuffer, attachment, texture, level);
	}

	// Layered attach, needed since upstream started binding 3D/array/cube levels through DSA.
	// EXT_direct_state_access has no NamedFramebufferTextureLayer, so this binds the framebuffer
	// and uses the non-DSA entry point -- correct, and no worse than what the caller did before
	// DSA_CALL2 existed. The previous binding is restored so the caller sees no side effect.
	void glNamedFramebufferTextureLayer(GLuint framebuffer, GLenum attachment, GLuint texture, GLint level, GLint layer)
	{
		GLint previous = 0;
		glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &previous);

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
		glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, attachment, texture, level, layer);

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(previous));
	}

	// DSA_CALL2 resolves either spelling, so both have to exist here.
	void glNamedFramebufferTextureLayerEXT(GLuint framebuffer, GLenum attachment, GLuint texture, GLint level, GLint layer)
	{
		glNamedFramebufferTextureLayer(framebuffer, attachment, texture, level, layer);
	}

	GLenum glCheckNamedFramebufferStatus(GLuint framebuffer, GLenum target)
	{
		return glCheckNamedFramebufferStatusEXT(framebuffer, target);
	}

	void glNamedFramebufferDrawBuffers(GLuint framebuffer, GLsizei n, const GLenum* bufs)
	{
		glFramebufferDrawBuffersEXT(framebuffer, n, bufs);
	}

	void glNamedFramebufferReadBuffer(GLuint framebuffer, GLenum src)
	{
		glFramebufferReadBufferEXT(framebuffer, src);
	}

	// Framebuffer-less rendering (upstream 66ba73282) gives an attachment-less framebuffer its
	// default size and sample count. ES 3.1 has only the bound-target glFramebufferParameteri, so
	// this binds, sets and restores the previous binding, like glNamedFramebufferTextureLayer.
	void glNamedFramebufferParameteri(GLuint framebuffer, GLenum pname, GLint param)
	{
		GLint previous = 0;
		glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &previous);

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
		glFramebufferParameteri(GL_DRAW_FRAMEBUFFER, pname, param);

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(previous));
	}

	// DSA_CALL2 resolves either spelling, so both have to exist here.
	void glNamedFramebufferParameteriEXT(GLuint framebuffer, GLenum pname, GLint param)
	{
		glNamedFramebufferParameteri(framebuffer, pname, param);
	}

	// These two do lose the target, and a texture parameter set on the wrong
	// target is a silent visual bug, so they refuse rather than guess.
	void glTextureParameteri(GLuint, GLenum, GLint)
	{
		report_arb_dsa("glTextureParameteri");
	}

	void glTextureParameteriv(GLuint, GLenum, const GLint*)
	{
		report_arb_dsa("glTextureParameteriv");
	}

	// ---------------------------------------------------------------------
	// Texture readback. The one place where ES genuinely lost a capability
	// rather than a spelling.
	// ---------------------------------------------------------------------

	static GLuint s_readback_fbo = 0;

	static void impl_GetTextureSubImage(GLuint texture, GLint level, GLint xoffset, GLint yoffset, GLint zoffset,
		GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, void* pixels)
	{
		if (depth > 1 || zoffset != 0)
		{
			// Layered readback needs one glReadPixels per layer and a stride the
			// caller never told us about. Nothing in this tree reaches it: the
			// volumetric paths all go through the compute shaders.
			rsx_log.error("GLES: layered texture readback is not implemented (depth=%d)", depth);
			return;
		}

		if (!s_readback_fbo)
		{
			glGenFramebuffers(1, &s_readback_fbo);
		}

		const scoped_fbo_bind bind(s_readback_fbo);
		glFramebufferTexture(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texture, level);

		if (const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER); status != GL_FRAMEBUFFER_COMPLETE)
		{
			// Depth/stencil and most non-colour-renderable formats land here. Both
			// of RPCS3's hot readback formats (RGBA8, D24S8) already prefer the
			// cs_rgba8_to_ssbo / cs_d24x8_to_ssbo compute paths in GLTexture.cpp,
			// so this is the cold tail, not the common case.
			glFramebufferTexture(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, 0, 0);
			rsx_log.error("GLES: cannot read back texture %u (fbo status 0x%x, format 0x%x)", texture, status, format);
			return;
		}

		glReadBuffer(GL_COLOR_ATTACHMENT0);
		glReadPixels(xoffset, yoffset, width, height, format, type, pixels);
		glFramebufferTexture(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, 0, 0);
	}

	static void impl_GetTextureImage(GLuint texture, GLint level, GLenum format, GLenum type, GLsizei /*bufSize*/, void* pixels)
	{
		GLint width = 0, height = 0;

		{
			const scoped_texture_bind bind(GL_TEXTURE_2D, texture);
			glGetTexLevelParameteriv(GL_TEXTURE_2D, level, GL_TEXTURE_WIDTH, &width);
			glGetTexLevelParameteriv(GL_TEXTURE_2D, level, GL_TEXTURE_HEIGHT, &height);
		}

		if (width <= 0 || height <= 0)
		{
			rsx_log.error("GLES: texture %u level %d has no queryable 2D extent; readback skipped", texture, level);
			return;
		}

		impl_GetTextureSubImage(texture, level, 0, 0, 0, width, height, 1, format, type, pixels);
	}

	void (*glGetTextureImage)(GLuint, GLint, GLenum, GLenum, GLsizei, void*) = &impl_GetTextureImage;

	static void impl_GetTextureImageEXT(GLuint texture, GLenum /*target*/, GLint level, GLenum format, GLenum type, void* pixels)
	{
		impl_GetTextureImage(texture, level, format, type, 0, pixels);
	}

	void (*glGetTextureImageEXT)(GLuint, GLenum, GLint, GLenum, GLenum, void*) = &impl_GetTextureImageEXT;

	void glGetTextureSubImage(GLuint texture, GLint level, GLint xoffset, GLint yoffset, GLint zoffset,
		GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, GLsizei /*bufSize*/, void* pixels)
	{
		impl_GetTextureSubImage(texture, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels);
	}

	// ---------------------------------------------------------------------
	// Remaining desktop entry points
	// ---------------------------------------------------------------------

	void glTextureView(GLuint texture, GLenum target, GLuint origtexture, GLenum internalformat, GLuint minlevel, GLuint numlevels, GLuint minlayer, GLuint numlayers)
	{
		if (p_glTextureViewEXT) [[likely]]
		{
			p_glTextureViewEXT(texture, real_texture_target(target), origtexture, internalformat, minlevel, numlevels, minlayer, numlayers);
			return;
		}

		// There is no way to alias texture storage in ES without the extension.
		// Report it loudly once: the texture cache builds views for every
		// swizzled/stencil-aliased sampler, so a silent failure here reads as
		// "half the textures are black".
		static bool warned = false;
		if (!std::exchange(warned, true))
		{
			rsx_log.error("GLES: EXT_texture_view / OES_texture_view is unavailable. Texture views cannot be created.");
		}
	}

	void glTextureBarrier()
	{
		// Not equivalent, and not pretending to be: glMemoryBarrier orders
		// incoherent memory accesses, whereas glTextureBarrier orders raster
		// output against subsequent texture fetches from the same image. ES has
		// nothing for the latter. capabilities.cpp reports both texture-barrier
		// caps as false so the backend takes its documented
		// "feedback loops have undefined results" path instead of relying on this.
		glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_FRAMEBUFFER_BARRIER_BIT);
	}

	void glTexStorage1D(GLenum /*target*/, GLsizei levels, GLenum internalformat, GLsizei width)
	{
		// See real_texture_target: 1D becomes a height-1 2D texture.
		glTexStorage2D(GL_TEXTURE_2D, levels, internalformat, width, 1);
	}

	void glDepthRange(GLdouble n, GLdouble f)
	{
		glDepthRangef(static_cast<GLfloat>(n), static_cast<GLfloat>(f));
	}

	void glClearDepth(GLdouble d)
	{
		glClearDepthf(static_cast<GLfloat>(d));
	}

	void glPrimitiveRestartIndex(GLuint index)
	{
		// ES only has GL_PRIMITIVE_RESTART_FIXED_INDEX, whose index is implicitly
		// all-ones for the bound index type - which is exactly what RPCS3 passes
		// (0xffff for u16, 0xffffffff for u32). So this really is a no-op, not a
		// silently dropped feature. Complain if that assumption ever breaks.
		if (index != 0xffffu && index != 0xffffffffu)
		{
			rsx_log.error("GLES: primitive restart index 0x%x cannot be expressed; ES only supports the fixed all-ones index.", index);
		}
	}

	void glMultiDrawArrays(GLenum mode, const GLint* first, const GLsizei* count, GLsizei drawcount)
	{
		if (p_glMultiDrawArraysEXT)
		{
			p_glMultiDrawArraysEXT(mode, first, count, drawcount);
			return;
		}

		for (GLsizei i = 0; i < drawcount; ++i)
		{
			if (count[i] > 0) glDrawArrays(mode, first[i], count[i]);
		}
	}

	void glMultiDrawElements(GLenum mode, const GLsizei* count, GLenum type, const void* const* indices, GLsizei drawcount)
	{
		if (p_glMultiDrawElementsEXT)
		{
			p_glMultiDrawElementsEXT(mode, const_cast<GLsizei*>(count), type, const_cast<const void**>(indices), drawcount);
			return;
		}

		for (GLsizei i = 0; i < drawcount; ++i)
		{
			if (count[i] > 0) glDrawElements(mode, count[i], type, indices[i]);
		}
	}

	void glGetQueryObjectiv(GLuint id, GLenum pname, GLint* params)
	{
		GLuint value = 0;
		glGetQueryObjectuiv(id, pname, &value);
		*params = static_cast<GLint>(value);
	}

	void glBindFragDataLocation(GLuint /*program*/, GLuint /*colorNumber*/, const char* /*name*/)
	{
		// ES locates fragment outputs with layout(location=) only, which the ES
		// shader generator already emits. Nothing to do.
	}

	// ---------------------------------------------------------------------
	// Genuinely unsupported. Each complains once so a missing feature shows up
	// in a log rather than as a rendering mystery.
	// ---------------------------------------------------------------------

	static void warn_once(bool& flag, const char* what)
	{
		if (!std::exchange(flag, true))
		{
			rsx_log.warning("GLES: %s is not available in OpenGL ES; the call was ignored.", what);
		}
	}

	void glLogicOp(GLenum)
	{
		static bool warned = false;
		warn_once(warned, "colour logic op");
	}

	void glPolygonMode(GLenum, GLenum)
	{
		static bool warned = false;
		warn_once(warned, "glPolygonMode (wireframe/point fill)");
	}

	void glDrawPixels(GLsizei, GLsizei, GLenum, GLenum, const void*)
	{
		static bool warned = false;
		warn_once(warned, "glDrawPixels");
	}

	void glDepthBoundsEXT(GLclampd, GLclampd)
	{
		static bool warned = false;
		warn_once(warned, "depth bounds test");
	}

	void glDepthBoundsdNV(GLdouble, GLdouble)
	{
		static bool warned = false;
		warn_once(warned, "depth bounds test");
	}

	void glDepthRangedNV(GLdouble n, GLdouble f)
	{
		glDepthRangef(static_cast<GLfloat>(n), static_cast<GLfloat>(f));
	}

	void glVertexAttrib1d(GLuint index, GLdouble x)
	{
		glVertexAttrib1f(index, static_cast<GLfloat>(x));
	}

	void glVertexAttrib2d(GLuint index, GLdouble x, GLdouble y)
	{
		glVertexAttrib2f(index, static_cast<GLfloat>(x), static_cast<GLfloat>(y));
	}

	void glVertexAttrib3d(GLuint index, GLdouble x, GLdouble y, GLdouble z)
	{
		glVertexAttrib3f(index, static_cast<GLfloat>(x), static_cast<GLfloat>(y), static_cast<GLfloat>(z));
	}

	void glVertexAttrib4d(GLuint index, GLdouble x, GLdouble y, GLdouble z, GLdouble w)
	{
		glVertexAttrib4f(index, static_cast<GLfloat>(x), static_cast<GLfloat>(y), static_cast<GLfloat>(z), static_cast<GLfloat>(w));
	}

	static void report_bindless()
	{
		rsx_log.fatal("GLES: bindless textures do not exist in OpenGL ES. "
			"This path is supposed to be unreachable - ARB_bindless_texture is reported "
			"unsupported and GLGSRender downgrades the shader interpreter accordingly.");
	}

	GLuint64 glGetTextureHandleARB(GLuint)
	{
		report_bindless();
		return 0;
	}

	void glMakeTextureHandleResidentARB(GLuint64)
	{
		report_bindless();
	}

	void glMakeTextureHandleNonResidentARB(GLuint64)
	{
		report_bindless();
	}

	void glProgramUniformHandleui64ARB(GLuint, GLint, GLuint64)
	{
		report_bindless();
	}

	void glProgramUniformHandleui64vARB(GLuint, GLint, GLsizei, const GLuint64*)
	{
		report_bindless();
	}

	// ---------------------------------------------------------------------
	// Filtered forwards
	// ---------------------------------------------------------------------

	// The driver's real entry points, saved before the filters replace them.
	static PFN_glPixelStorei       raw_glPixelStorei = nullptr;
	static PFN_glEnable            raw_glEnable = nullptr;
	static PFN_glDisable           raw_glDisable = nullptr;
	static PFN_glTexParameteri     raw_glTexParameteri = nullptr;
	static PFN_glTexParameteriv    raw_glTexParameteriv = nullptr;
	static PFN_glTexParameterf     raw_glTexParameterf = nullptr;
	static PFN_glTexParameterfv    raw_glTexParameterfv = nullptr;
	static PFN_glSamplerParameterf raw_glSamplerParameterf = nullptr;
	static PFN_glBindTexture       raw_glBindTexture = nullptr;
	static PFN_glTexSubImage2D     raw_glTexSubImage2D = nullptr;
	static PFN_glTexSubImage3D     raw_glTexSubImage3D = nullptr;
	static PFN_glCopyImageSubData  raw_glCopyImageSubData = nullptr;
	static PFN_glGetTexLevelParameteriv raw_glGetTexLevelParameteriv = nullptr;

	// GL_TEXTURE_1D reaches the driver from three directions - texture creation,
	// the per-draw sampler binding, and GLGSRender's null-texture setup - and ES
	// would reject every one of them with GL_INVALID_ENUM. Mapping the target here
	// rather than at each call site keeps the height-1-2D emulation in one place.
	//
	// The cost, stated plainly: a 1D and a 2D texture can no longer be bound to
	// the same texture unit at once, because on ES they are the same binding point.
	// RPCS3 does exactly that for its null textures (GLGSRender.cpp binds all four
	// dimensions per unit), so the 2D null texture wins and a shader that samples
	// an unbound 1D texture reads the 2D null instead of the 1D one. Both are a
	// 1x1 transparent black texel, so the visible result is identical.
	static void GL_APIENTRY filter_BindTexture(GLenum target, GLuint texture)
	{
		raw_glBindTexture(real_texture_target(target), texture);
	}

	static void GL_APIENTRY filter_PixelStorei(GLenum pname, GLint param)
	{
		switch (pname)
		{
		case GL_PACK_SWAP_BYTES:
		case GL_UNPACK_SWAP_BYTES:
		case GL_PACK_LSB_FIRST:
		case GL_UNPACK_LSB_FIRST:
		case GL_PACK_IMAGE_HEIGHT:
		case GL_PACK_SKIP_IMAGES:
			// ES dropped these. Verified unreachable with a non-default value in
			// this tree: pixel_pack_settings/pixel_unpack_settings expose setters
			// for them but only alignment() and row_length() are ever called, so
			// they are always the spec default. Shout if that ever changes,
			// because silently dropping a byte swap would corrupt every texture.
			if (param != 0)
			{
				rsx_log.error("GLES: pixel store parameter 0x%x = %d cannot be expressed in ES and was dropped.", pname, param);
			}
			return;
		default:
			raw_glPixelStorei(pname, param);
			return;
		}
	}

	static void GL_APIENTRY filter_Enable(GLenum cap)
	{
		switch (cap)
		{
		case GL_PRIMITIVE_RESTART:
			// See glPrimitiveRestartIndex.
			raw_glEnable(GL_PRIMITIVE_RESTART_FIXED_INDEX);
			return;
		case GL_MULTISAMPLE:
		case GL_VERTEX_PROGRAM_POINT_SIZE:
			// Implicit in ES: multisampling follows the framebuffer, and point
			// size is always taken from gl_PointSize.
			return;
		case GL_COLOR_LOGIC_OP:
		case GL_LINE_SMOOTH:
		case GL_POLYGON_OFFSET_LINE:
		case GL_POLYGON_OFFSET_POINT:
		case GL_DEPTH_CLAMP:
		case GL_DEPTH_BOUNDS_TEST_EXT:
		case GL_SAMPLE_ALPHA_TO_ONE:
		{
			static bool warned = false;
			warn_once(warned, "one or more desktop-only render states");
			return;
		}
		default:
			if (!is_unsupported_clip_distance(cap))
			{
				raw_glEnable(cap);
			}
			return;
		}
	}

	static void GL_APIENTRY filter_Disable(GLenum cap)
	{
		switch (cap)
		{
		case GL_PRIMITIVE_RESTART:
			raw_glDisable(GL_PRIMITIVE_RESTART_FIXED_INDEX);
			return;
		case GL_MULTISAMPLE:
		case GL_VERTEX_PROGRAM_POINT_SIZE:
		case GL_COLOR_LOGIC_OP:
		case GL_LINE_SMOOTH:
		case GL_POLYGON_OFFSET_LINE:
		case GL_POLYGON_OFFSET_POINT:
		case GL_DEPTH_CLAMP:
		case GL_DEPTH_BOUNDS_TEST_EXT:
		case GL_SAMPLE_ALPHA_TO_ONE:
			return;
		default:
			if (!is_unsupported_clip_distance(cap))
			{
				raw_glDisable(cap);
			}
			return;
		}
	}

	// ES has no LOD bias, on textures or on samplers. RPCS3 sets it on every sampler it makes,
	// nearly always to zero, which is what ES does anyway. A game that asks for another value
	// gets zero, which makes its textures a little sharper or blurrier than intended.
	static bool drop_lod_bias(GLenum pname, GLfloat value)
	{
		if (pname != GL_TEXTURE_LOD_BIAS)
		{
			return false;
		}

		if (value != 0.f)
		{
			static bool warned = false;
			warn_once(warned, "texture LOD bias");
		}

		return true;
	}

	// The texture constructor sets its parameters on whatever target it was made for, 1D
	// included; see filter_BindTexture.
	static void GL_APIENTRY filter_TexParameteri(GLenum target, GLenum pname, GLint param)
	{
		if (!drop_lod_bias(pname, static_cast<GLfloat>(param)))
		{
			raw_glTexParameteri(real_texture_target(target), pname, param);
		}
	}

	static void GL_APIENTRY filter_TexParameterf(GLenum target, GLenum pname, GLfloat param)
	{
		if (!drop_lod_bias(pname, param))
		{
			raw_glTexParameterf(real_texture_target(target), pname, param);
		}
	}

	static void GL_APIENTRY filter_TexParameterfv(GLenum target, GLenum pname, const GLfloat* params)
	{
		if (!drop_lod_bias(pname, params[0]))
		{
			raw_glTexParameterfv(real_texture_target(target), pname, params);
		}
	}

	static void GL_APIENTRY filter_TexParameteriv(GLenum target, GLenum pname, const GLint* params)
	{
		if (pname == GL_TEXTURE_SWIZZLE_RGBA)
		{
			// One desktop call, four ES calls.
			glTexParameteri(target, GL_TEXTURE_SWIZZLE_R, params[0]);
			glTexParameteri(target, GL_TEXTURE_SWIZZLE_G, params[1]);
			glTexParameteri(target, GL_TEXTURE_SWIZZLE_B, params[2]);
			glTexParameteri(target, GL_TEXTURE_SWIZZLE_A, params[3]);
			return;
		}

		if (!drop_lod_bias(pname, static_cast<GLfloat>(params[0])))
		{
			raw_glTexParameteriv(real_texture_target(target), pname, params);
		}
	}

	static void GL_APIENTRY filter_SamplerParameterf(GLuint sampler, GLenum pname, GLfloat param)
	{
		if (!drop_lod_bias(pname, param))
		{
			raw_glSamplerParameterf(sampler, pname, param);
		}
	}

	// The texture constructor works out a pitch from the channel sizes it asks for here. For a 1D
	// texture the driver refused the question, and the pitch came from uninitialised values.
	static void GL_APIENTRY filter_GetTexLevelParameteriv(GLenum target, GLint level, GLenum pname, GLint* params)
	{
		raw_glGetTexLevelParameteriv(real_texture_target(target), level, pname, params);
	}

	static void GL_APIENTRY filter_CopyImageSubData(GLuint src, GLenum src_target, GLint src_level, GLint src_x, GLint src_y, GLint src_z,
		GLuint dst, GLenum dst_target, GLint dst_level, GLint dst_x, GLint dst_y, GLint dst_z, GLsizei width, GLsizei height, GLsizei depth)
	{
		raw_glCopyImageSubData(src, real_texture_target(src_target), src_level, src_x, src_y, src_z,
			dst, real_texture_target(dst_target), dst_level, dst_x, dst_y, dst_z, width, height, depth);
	}

	// Desktop GL takes a texel as one packed 32-bit word (GL_UNSIGNED_INT_8_8_8_8 and _REV) and
	// in blue-first order (GL_BGRA); ES takes neither, only bytes in R, G, B, A order. RPCS3
	// uploads its null textures, the overlay images and frames the game drew on the CPU that way,
	// so the driver refused them and they stayed empty. They are put in byte order here instead,
	// which leaves the texture holding exactly what a desktop driver would have stored.
	struct texel_bytes
	{
		// Where each channel sits within the four bytes of a source texel
		u8 r, g, b, a;
	};

	static std::optional<texel_bytes> desktop_texel_layout(GLenum format, GLenum type)
	{
		// The host is little-endian: a packed word's low byte comes first in memory
		switch (format)
		{
		case GL_RGBA:
			switch (type)
			{
			case GL_UNSIGNED_INT_8_8_8_8: return texel_bytes{ 3, 2, 1, 0 };
			case GL_UNSIGNED_INT_8_8_8_8_REV: return texel_bytes{ 0, 1, 2, 3 };
			default: return std::nullopt;
			}
		case GL_BGRA:
			switch (type)
			{
			case GL_UNSIGNED_BYTE:
			case GL_UNSIGNED_INT_8_8_8_8_REV: return texel_bytes{ 2, 1, 0, 3 };
			case GL_UNSIGNED_INT_8_8_8_8: return texel_bytes{ 1, 2, 3, 0 };
			default: return std::nullopt;
			}
		default:
			return std::nullopt;
		}
	}

	// Reads the source the way the unpack state describes it and writes it out tightly packed,
	// one R, G, B, A texel after another. Nullopt when the source is in a pixel buffer, which
	// would have to be mapped first: none of the uploads above use one.
	static std::optional<std::vector<u8>> repack_texels(const void* pixels, GLsizei width, GLsizei height, GLsizei depth, texel_bytes layout)
	{
		GLint unpack_buffer = 0;
		glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &unpack_buffer);

		if (unpack_buffer || !pixels)
		{
			static bool reported = false;
			if (!std::exchange(reported, true))
			{
				rsx_log.error("GLES: an upload from a pixel buffer is in a desktop-only pixel format and was not converted.");
			}
			return std::nullopt;
		}

		GLint row_length = 0, image_height = 0, skip_pixels = 0, skip_rows = 0, skip_images = 0, alignment = 4;
		glGetIntegerv(GL_UNPACK_ROW_LENGTH, &row_length);
		glGetIntegerv(GL_UNPACK_IMAGE_HEIGHT, &image_height);
		glGetIntegerv(GL_UNPACK_SKIP_PIXELS, &skip_pixels);
		glGetIntegerv(GL_UNPACK_SKIP_ROWS, &skip_rows);
		glGetIntegerv(GL_UNPACK_SKIP_IMAGES, &skip_images);
		glGetIntegerv(GL_UNPACK_ALIGNMENT, &alignment);

		const usz row_pitch = utils::align<usz>(usz(row_length > 0 ? row_length : width) * 4, std::max(alignment, 1));
		const usz image_pitch = row_pitch * usz(image_height > 0 ? image_height : height);
		const u8* src = static_cast<const u8*>(pixels) + skip_images * image_pitch + skip_rows * row_pitch + skip_pixels * 4;

		std::vector<u8> texels(usz(width) * height * depth * 4);
		u8* dst = texels.data();

		for (GLsizei z = 0; z < depth; ++z)
		{
			for (GLsizei y = 0; y < height; ++y)
			{
				const u8* row = src + z * image_pitch + y * row_pitch;

				for (GLsizei x = 0; x < width; ++x, dst += 4)
				{
					const u8* texel = row + x * 4;
					dst[0] = texel[layout.r];
					dst[1] = texel[layout.g];
					dst[2] = texel[layout.b];
					dst[3] = texel[layout.a];
				}
			}
		}

		return texels;
	}

	// The repacked texels are tight, so the caller's row length, skips and alignment are set
	// aside for the upload.
	struct scoped_tight_unpack
	{
		static constexpr GLenum k_state[] = { GL_UNPACK_ROW_LENGTH, GL_UNPACK_IMAGE_HEIGHT, GL_UNPACK_SKIP_PIXELS,
			GL_UNPACK_SKIP_ROWS, GL_UNPACK_SKIP_IMAGES, GL_UNPACK_ALIGNMENT };
		GLint m_saved[std::size(k_state)]{};

		scoped_tight_unpack()
		{
			for (usz i = 0; i < std::size(k_state); ++i)
			{
				glGetIntegerv(k_state[i], &m_saved[i]);
				raw_glPixelStorei(k_state[i], k_state[i] == GL_UNPACK_ALIGNMENT ? 4 : 0);
			}
		}

		~scoped_tight_unpack()
		{
			for (usz i = 0; i < std::size(k_state); ++i)
			{
				raw_glPixelStorei(k_state[i], m_saved[i]);
			}
		}
	};

	static void GL_APIENTRY filter_TexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height,
		GLenum format, GLenum type, const void* pixels)
	{
		if (const auto layout = desktop_texel_layout(format, type))
		{
			if (const auto texels = repack_texels(pixels, width, height, 1, *layout))
			{
				const scoped_tight_unpack tight;
				raw_glTexSubImage2D(target, level, xoffset, yoffset, width, height, GL_RGBA, GL_UNSIGNED_BYTE, texels->data());
				return;
			}
		}

		raw_glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
	}

	static void GL_APIENTRY filter_TexSubImage3D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset,
		GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, const void* pixels)
	{
		if (const auto layout = desktop_texel_layout(format, type))
		{
			if (const auto texels = repack_texels(pixels, width, height, depth, *layout))
			{
				const scoped_tight_unpack tight;
				raw_glTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, GL_RGBA, GL_UNSIGNED_BYTE, texels->data());
				return;
			}
		}

		raw_glTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels);
	}

	// Swap the filters in over the raw driver pointers. Called at the end of
	// load_procs, after the generic loop has populated the table.
	static void install_filters()
	{
		raw_glPixelStorei = ::glPixelStorei;
		raw_glEnable = ::glEnable;
		raw_glDisable = ::glDisable;
		raw_glTexParameteri = ::glTexParameteri;
		raw_glTexParameteriv = ::glTexParameteriv;
		raw_glTexParameterf = ::glTexParameterf;
		raw_glTexParameterfv = ::glTexParameterfv;
		raw_glSamplerParameterf = ::glSamplerParameterf;
		raw_glBindTexture = ::glBindTexture;
		raw_glTexSubImage2D = ::glTexSubImage2D;
		raw_glTexSubImage3D = ::glTexSubImage3D;
		raw_glCopyImageSubData = ::glCopyImageSubData;
		raw_glGetTexLevelParameteriv = ::glGetTexLevelParameteriv;

		::glPixelStorei = &filter_PixelStorei;
		::glEnable = &filter_Enable;
		::glDisable = &filter_Disable;
		::glTexParameteri = &filter_TexParameteri;
		::glTexParameteriv = &filter_TexParameteriv;
		::glTexParameterf = &filter_TexParameterf;
		::glTexParameterfv = &filter_TexParameterfv;
		::glSamplerParameterf = &filter_SamplerParameterf;
		::glBindTexture = &filter_BindTexture;
		::glTexSubImage2D = &filter_TexSubImage2D;
		::glTexSubImage3D = &filter_TexSubImage3D;
		::glCopyImageSubData = &filter_CopyImageSubData;
		::glGetTexLevelParameteriv = &filter_GetTexLevelParameteriv;
	}
}

//
// ---- EGL ---------------------------------------------------------------------
//

namespace gl::es
{
	namespace
	{
		void* s_egl_handle = nullptr;
		void* s_gles_handle = nullptr;
		gl_backend s_backend = gl_backend::system;
		bool s_egl_ready = false;

		EGLDisplay s_display = EGL_NO_DISPLAY;
		EGLConfig s_config = nullptr;
		EGLSurface s_surface = EGL_NO_SURFACE;

		// What the surface was made for, so present_window can tell when Android replaced the
		// window. umax: made by make_current, which has no epoch; the next present adopts it.
		void* s_surface_window = nullptr;
		u64 s_surface_epoch = umax;

		// Contexts made and not yet destroyed. The window is let go with the last one, and a
		// different EGL library can only be loaded while there are none.
		u32 s_live_contexts = 0;

		// The ANGLE library egl_initialize was asked for, empty for the system driver.
		std::string s_requested_angle;

		// Only the entry points we actually need. Resolved with dlsym on whichever
		// libEGL we opened - NOT with eglGetProcAddress, which we cannot call yet.
		PFNEGLGETPROCADDRESSPROC      p_eglGetProcAddress = nullptr;
		PFNEGLGETDISPLAYPROC          p_eglGetDisplay = nullptr;
		PFNEGLINITIALIZEPROC          p_eglInitialize = nullptr;
		PFNEGLTERMINATEPROC           p_eglTerminate = nullptr;
		PFNEGLCHOOSECONFIGPROC        p_eglChooseConfig = nullptr;
		PFNEGLCREATECONTEXTPROC       p_eglCreateContext = nullptr;
		PFNEGLDESTROYCONTEXTPROC      p_eglDestroyContext = nullptr;
		PFNEGLCREATEWINDOWSURFACEPROC p_eglCreateWindowSurface = nullptr;
		PFNEGLDESTROYSURFACEPROC      p_eglDestroySurface = nullptr;
		PFNEGLMAKECURRENTPROC         p_eglMakeCurrent = nullptr;
		PFNEGLSWAPBUFFERSPROC         p_eglSwapBuffers = nullptr;
		PFNEGLSWAPINTERVALPROC        p_eglSwapInterval = nullptr;
		PFNEGLBINDAPIPROC             p_eglBindAPI = nullptr;
		PFNEGLGETERRORPROC            p_eglGetError = nullptr;

		template <typename T>
		bool resolve(void* handle, T& fn, const char* name)
		{
			fn = reinterpret_cast<T>(dlsym(handle, name));
			if (!fn)
			{
				rsx_log.error("GLES: %s is missing from the EGL library", name);
				return false;
			}

			return true;
		}

		// Returns the directory part of a path, or an empty string.
		std::string dirname_of(const std::string& path)
		{
			const auto slash = path.find_last_of('/');
			return (slash == std::string::npos) ? std::string() : path.substr(0, slash);
		}

		// Unbind this thread's context and destroy the window surface, which lets the window go.
		// An ANativeWindow takes one producer at a time: a surface left on it keeps a Vulkan
		// swapchain, or the next game's surface, from attaching.
		void release_surface()
		{
			if (s_display == EGL_NO_DISPLAY)
			{
				return;
			}

			p_eglMakeCurrent(s_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

			if (s_surface != EGL_NO_SURFACE)
			{
				p_eglDestroySurface(s_display, s_surface);
				s_surface = EGL_NO_SURFACE;
			}

			s_surface_window = nullptr;
			s_surface_epoch = umax;
		}
	}

	gl_backend active_backend()
	{
		return s_backend;
	}

	gl_backend egl_initialize()
	{
		const char* const angle_egl = std::getenv("ARMSX3_ANGLE_EGL_LIBRARY");
		const std::string requested = (angle_egl && *angle_egl) ? angle_egl : "";

		if (s_egl_ready)
		{
			// ANGLE or the system driver is picked per game (MainActivityRuntime.applyAngleEnv), and
			// Apply & Restart boots the next game in this same process, so a changed pick has to be
			// able to take over. Only once nothing made with the current library is alive.
			if (requested == s_requested_angle)
			{
				return s_backend;
			}

			if (s_live_contexts)
			{
				// Said out loud: otherwise the game simply runs on the driver the player turned off
				rsx_log.warning("GLES: %s was picked, but %u context(s) of the current driver are still alive, so it stays in use",
					requested.empty() ? "The system driver" : "ANGLE", s_live_contexts);
				return s_backend;
			}

			release_surface();

			if (s_display != EGL_NO_DISPLAY)
			{
				p_eglTerminate(s_display);
				s_display = EGL_NO_DISPLAY;
			}

			// Both libraries stay loaded: a driver is not something to unload, and an idle one
			// costs nothing. Their handles are just no longer the ones in use.
			s_config = nullptr;
			s_egl_handle = nullptr;
			s_gles_handle = nullptr;
			s_egl_ready = false;
			rsx_log.notice("GLES: switching to %s", requested.empty() ? "the system driver" : "ANGLE");
		}

		s_requested_angle = requested;

		// ANGLE first, if the app asked for it.
		if (!requested.empty())
		{
			s_egl_handle = dlopen(requested.c_str(), RTLD_NOW | RTLD_LOCAL);
			if (s_egl_handle)
			{
				s_backend = gl_backend::angle;
				rsx_log.success("GLES: using ANGLE from %s", requested);

				// ANGLE's GLESv2 sits next to its EGL. Prefer the explicit override,
				// then the sibling path, so we never accidentally resolve GL entry
				// points out of the system driver while EGL is ANGLE's.
				const char* angle_gles = std::getenv("ARMSX3_ANGLE_GLES_LIBRARY");
				const std::string gles_path = (angle_gles && *angle_gles)
					? std::string(angle_gles)
					: dirname_of(requested) + "/libGLESv2_angle.so";

				s_gles_handle = dlopen(gles_path.c_str(), RTLD_NOW | RTLD_LOCAL);
				if (!s_gles_handle)
				{
					rsx_log.error("GLES: ANGLE EGL loaded but its GLESv2 (%s) did not: %s", gles_path, dlerror());
				}
			}
			else
			{
				// Loud on purpose. A silent fall back to the system driver here is
				// indistinguishable from "ANGLE is broken" in a user's bug report.
				rsx_log.error("GLES: ANGLE was requested but %s could not be loaded (%s). Falling back to the system GLES driver.", requested, dlerror());
			}
		}

		if (!s_egl_handle)
		{
			s_backend = gl_backend::system;
			s_egl_handle = dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
			if (!s_egl_handle)
			{
				rsx_log.fatal("GLES: libEGL.so could not be loaded: %s", dlerror());
				return s_backend;
			}
		}

		if (!s_gles_handle)
		{
			// libGLESv3.so is the ES 3.x stub on Android; libGLESv2.so exports the
			// same symbols on every device that has ES 3, so try both.
			s_gles_handle = dlopen("libGLESv3.so", RTLD_NOW | RTLD_LOCAL);
			if (!s_gles_handle)
			{
				s_gles_handle = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
			}
		}

		const bool ok =
			resolve(s_egl_handle, p_eglGetProcAddress, "eglGetProcAddress") &&
			resolve(s_egl_handle, p_eglGetDisplay, "eglGetDisplay") &&
			resolve(s_egl_handle, p_eglInitialize, "eglInitialize") &&
			resolve(s_egl_handle, p_eglTerminate, "eglTerminate") &&
			resolve(s_egl_handle, p_eglChooseConfig, "eglChooseConfig") &&
			resolve(s_egl_handle, p_eglCreateContext, "eglCreateContext") &&
			resolve(s_egl_handle, p_eglDestroyContext, "eglDestroyContext") &&
			resolve(s_egl_handle, p_eglCreateWindowSurface, "eglCreateWindowSurface") &&
			resolve(s_egl_handle, p_eglDestroySurface, "eglDestroySurface") &&
			resolve(s_egl_handle, p_eglMakeCurrent, "eglMakeCurrent") &&
			resolve(s_egl_handle, p_eglSwapBuffers, "eglSwapBuffers") &&
			resolve(s_egl_handle, p_eglSwapInterval, "eglSwapInterval") &&
			resolve(s_egl_handle, p_eglBindAPI, "eglBindAPI") &&
			resolve(s_egl_handle, p_eglGetError, "eglGetError");

		if (!ok)
		{
			rsx_log.fatal("GLES: the EGL library is unusable.");
			return s_backend;
		}

		s_egl_ready = true;
		return s_backend;
	}

	void* default_proc_loader(const char* name)
	{
		// dlsym first: Android only guarantees eglGetProcAddress for extensions,
		// and on ANGLE the core entry points live in its own GLESv2 handle.
		if (s_gles_handle)
		{
			if (void* sym = dlsym(s_gles_handle, name))
			{
				return sym;
			}
		}

		if (p_eglGetProcAddress)
		{
			return reinterpret_cast<void*>(p_eglGetProcAddress(name));
		}

		return nullptr;
	}

	void* create_context(void* native_window, void* share_context)
	{
		if (egl_initialize(); !s_egl_ready)
		{
			return nullptr;
		}

		if (s_display == EGL_NO_DISPLAY)
		{
			s_display = p_eglGetDisplay(EGL_DEFAULT_DISPLAY);
			if (s_display == EGL_NO_DISPLAY || !p_eglInitialize(s_display, nullptr, nullptr))
			{
				rsx_log.fatal("GLES: eglInitialize failed (0x%x)", p_eglGetError());
				return nullptr;
			}

			p_eglBindAPI(EGL_OPENGL_ES_API);

			const EGLint config_attribs[] =
			{
				EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
				EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
				EGL_RED_SIZE,        8,
				EGL_GREEN_SIZE,      8,
				EGL_BLUE_SIZE,       8,
				EGL_ALPHA_SIZE,      8,
				// No depth/stencil on the swapchain: RPCS3 renders every frame into
				// its own FBOs and only blits the result here.
				EGL_DEPTH_SIZE,      0,
				EGL_STENCIL_SIZE,    0,
				EGL_NONE
			};

			EGLint num_configs = 0;
			if (!p_eglChooseConfig(s_display, config_attribs, &s_config, 1, &num_configs) || num_configs < 1)
			{
				rsx_log.fatal("GLES: no ES 3.x window config available (0x%x)", p_eglGetError());
				return nullptr;
			}
		}

		// ES 3.2 first. 3.1 is the ANGLE-on-Android ceiling, and the backend can
		// run there as long as the 3.2-only features degrade - which is why
		// capabilities.cpp keys off the reported version rather than assuming.
		for (const EGLint minor : {2, 1})
		{
			const EGLint ctx_attribs[] =
			{
				EGL_CONTEXT_MAJOR_VERSION, 3,
				EGL_CONTEXT_MINOR_VERSION, minor,
				EGL_NONE
			};

			EGLContext ctx = p_eglCreateContext(s_display, s_config,
				share_context ? static_cast<EGLContext>(share_context) : EGL_NO_CONTEXT, ctx_attribs);

			if (ctx != EGL_NO_CONTEXT)
			{
				rsx_log.success("GLES: created an OpenGL ES 3.%d context (%s)", minor,
					s_backend == gl_backend::angle ? "ANGLE" : "system driver");
				s_live_contexts++;
				return ctx;
			}
		}

		rsx_log.fatal("GLES: eglCreateContext failed (0x%x)", p_eglGetError());
		(void)native_window;
		return nullptr;
	}

	void destroy_context(void* context)
	{
		if (s_egl_ready && context)
		{
			p_eglDestroyContext(s_display, static_cast<EGLContext>(context));

			// The last one goes with the renderer: let the window go too, for a Vulkan swapchain or
			// for the next game's surface
			if (s_live_contexts && --s_live_contexts == 0)
			{
				release_surface();
			}
		}
	}

	bool make_current(void* context, void* native_window)
	{
		if (!s_egl_ready)
		{
			return false;
		}

		if (!context)
		{
			p_eglMakeCurrent(s_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
			return true;
		}

		// No window: a shader-compiler thread, one of the extra contexts RPCS3 makes. It never
		// presents and only needs a current context to compile against. It cannot have the window
		// surface either: a surface is current on one thread at a time, and the render thread holds
		// it (EGL_BAD_ACCESS). So it runs without one (EGL_KHR_surfaceless_context).
		if (!native_window)
		{
			if (!p_eglMakeCurrent(s_display, EGL_NO_SURFACE, EGL_NO_SURFACE, static_cast<EGLContext>(context)))
			{
				rsx_log.fatal("GLES: eglMakeCurrent without a surface failed (0x%x)", p_eglGetError());
				return false;
			}

			return true;
		}

		// The one window surface, for the render thread
		if (s_surface == EGL_NO_SURFACE)
		{
			s_surface = p_eglCreateWindowSurface(s_display, s_config,
				static_cast<EGLNativeWindowType>(native_window), nullptr);

			if (s_surface == EGL_NO_SURFACE)
			{
				rsx_log.fatal("GLES: eglCreateWindowSurface failed (0x%x)", p_eglGetError());
				return false;
			}

			s_surface_window = native_window;
			s_surface_epoch = umax;
		}

		if (!p_eglMakeCurrent(s_display, s_surface, s_surface, static_cast<EGLContext>(context)))
		{
			rsx_log.fatal("GLES: eglMakeCurrent failed (0x%x)", p_eglGetError());
			return false;
		}

		return true;
	}

	bool present_window(void* context, void* native_window, u64 epoch)
	{
		if (!s_egl_ready || !context || !native_window)
		{
			return false;
		}

		if (s_surface != EGL_NO_SURFACE)
		{
			// Made by make_current, which has no epoch to give it
			if (s_surface_epoch == umax && s_surface_window == native_window)
			{
				s_surface_epoch = epoch;
			}

			if (s_surface_window == native_window && s_surface_epoch == epoch)
			{
				return true;
			}

			rsx_log.notice("GLES: the window was replaced, making a new surface for it");
			release_surface();
		}

		if (!make_current(context, native_window))
		{
			return false;
		}

		s_surface_epoch = epoch;
		return true;
	}

	void swap_buffers()
	{
		if (s_egl_ready && s_surface != EGL_NO_SURFACE)
		{
			p_eglSwapBuffers(s_display, s_surface);
		}
	}

	void set_swap_interval(int interval)
	{
		if (s_egl_ready && s_display != EGL_NO_DISPLAY)
		{
			p_eglSwapInterval(s_display, interval);
		}
	}

	namespace
	{
		void GL_APIENTRY report_error(GLenum /*source*/, GLenum /*type*/, GLuint id, GLenum /*severity*/,
			GLsizei /*length*/, const GLchar* message, const void* /*user*/)
		{
			// A bad call usually repeats every draw. Each different complaint is logged a few
			// times and then only counted, so one that turns up later is not lost behind it.
			// Driver threads can call this too.
			static std::mutex s_mutex;
			static std::unordered_map<std::string, u32> s_seen;

			const std::string text = message ? message : "";
			u32 count = 0;
			{
				std::lock_guard lock(s_mutex);

				// Drivers can put changing numbers in a message; past this many, stop remembering
				if (const auto found = s_seen.find(text); found != s_seen.end())
				{
					count = ++found->second;
				}
				else if (s_seen.size() < 256)
				{
					s_seen.emplace(text, 1);
					count = 1;
				}
			}

			if (count && count <= 3)
			{
				rsx_log.error("GLES: the driver rejected a call (0x%x): %s", id, text);
			}
			else if (count && (count & (count - 1)) == 0)
			{
				rsx_log.error("GLES: the driver rejected the same call %u times: %s", count, text);
			}
		}
	}

	void enable_error_reporting()
	{
		GLint units = 0, fragment_units = 0, vertex_units = 0;
		glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &units);
		glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, &fragment_units);
		glGetIntegerv(GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS, &vertex_units);
		rsx_log.notice("GLES: %d texture units (%d fragment, %d vertex), texture views %s", units, fragment_units,
			vertex_units, p_glTextureViewEXT ? "available" : "emulated (the driver has none)");

		if (!glDebugMessageCallback || !glDebugMessageControl)
		{
			rsx_log.warning("GLES: the driver has no debug output, so rejected calls go unreported");
			return;
		}

		glEnable(GL_DEBUG_OUTPUT);
		glDebugMessageControl(GL_DONT_CARE, GL_DONT_CARE, GL_DONT_CARE, 0, nullptr, GL_FALSE);
		glDebugMessageControl(GL_DONT_CARE, GL_DEBUG_TYPE_ERROR, GL_DONT_CARE, 0, nullptr, GL_TRUE);
		glDebugMessageCallback(&report_error, nullptr);
	}
}

#endif // RSX_GLES
