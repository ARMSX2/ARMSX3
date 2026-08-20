#include "stdafx.h"
#include "OpenGL.h"

#ifdef RSX_GLES

#include <cstring>
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

		// Extensions. Absence is expected and handled; it is only the core table
		// that has to be complete.
		p_glBufferStorageEXT = reinterpret_cast<PFNGLBUFFERSTORAGEEXTPROC>(loader("glBufferStorageEXT"));

		// EXT and OES spell texture views identically apart from the suffix.
		p_glTextureViewEXT = reinterpret_cast<PFNGLTEXTUREVIEWEXTPROC>(loader("glTextureViewEXT"));
		if (!p_glTextureViewEXT)
		{
			p_glTextureViewEXT = reinterpret_cast<PFNGLTEXTUREVIEWEXTPROC>(loader("glTextureViewOES"));
		}

		p_glMultiDrawArraysEXT = reinterpret_cast<PFNGLMULTIDRAWARRAYSEXTPROC>(loader("glMultiDrawArraysEXT"));
		p_glMultiDrawElementsEXT = reinterpret_cast<PFNGLMULTIDRAWELEMENTSEXTPROC>(loader("glMultiDrawElementsEXT"));
		glInsertEventMarkerEXT = reinterpret_cast<PFNGLINSERTEVENTMARKEREXTPROC>(loader("glInsertEventMarkerEXT"));

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
	static PFN_glPixelStorei    raw_glPixelStorei = nullptr;
	static PFN_glEnable         raw_glEnable = nullptr;
	static PFN_glDisable        raw_glDisable = nullptr;
	static PFN_glTexParameteriv raw_glTexParameteriv = nullptr;
	static PFN_glBindTexture    raw_glBindTexture = nullptr;

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
			raw_glEnable(cap);
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
			raw_glDisable(cap);
			return;
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

		raw_glTexParameteriv(target, pname, params);
	}

	// Swap the four filters in over the raw driver pointers. Called at the end of
	// load_procs, after the generic loop has populated the table.
	static void install_filters()
	{
		raw_glPixelStorei = ::glPixelStorei;
		raw_glEnable = ::glEnable;
		raw_glDisable = ::glDisable;
		raw_glTexParameteriv = ::glTexParameteriv;
		raw_glBindTexture = ::glBindTexture;

		::glPixelStorei = &filter_PixelStorei;
		::glEnable = &filter_Enable;
		::glDisable = &filter_Disable;
		::glTexParameteriv = &filter_TexParameteriv;
		::glBindTexture = &filter_BindTexture;
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

		// Only the entry points we actually need. Resolved with dlsym on whichever
		// libEGL we opened - NOT with eglGetProcAddress, which we cannot call yet.
		PFNEGLGETPROCADDRESSPROC      p_eglGetProcAddress = nullptr;
		PFNEGLGETDISPLAYPROC          p_eglGetDisplay = nullptr;
		PFNEGLINITIALIZEPROC          p_eglInitialize = nullptr;
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
	}

	gl_backend active_backend()
	{
		return s_backend;
	}

	gl_backend egl_initialize()
	{
		if (s_egl_ready)
		{
			return s_backend;
		}

		// ANGLE first, if the app asked for it.
		if (const char* angle_egl = std::getenv("ARMSX3_ANGLE_EGL_LIBRARY"); angle_egl && *angle_egl)
		{
			s_egl_handle = dlopen(angle_egl, RTLD_NOW | RTLD_LOCAL);
			if (s_egl_handle)
			{
				s_backend = gl_backend::angle;
				rsx_log.success("GLES: using ANGLE from %s", angle_egl);

				// ANGLE's GLESv2 sits next to its EGL. Prefer the explicit override,
				// then the sibling path, so we never accidentally resolve GL entry
				// points out of the system driver while EGL is ANGLE's.
				const char* angle_gles = std::getenv("ARMSX3_ANGLE_GLES_LIBRARY");
				const std::string gles_path = (angle_gles && *angle_gles)
					? std::string(angle_gles)
					: dirname_of(angle_egl) + "/libGLESv2_angle.so";

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
				rsx_log.error("GLES: ANGLE was requested but %s could not be loaded (%s). Falling back to the system GLES driver.", angle_egl, dlerror());
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

		// One window surface, shared by every context. RPCS3 makes an extra
		// context per async shader-compiler thread, but those never present - they
		// only need a current context to compile against.
		if (s_surface == EGL_NO_SURFACE && native_window)
		{
			s_surface = p_eglCreateWindowSurface(s_display, s_config,
				static_cast<EGLNativeWindowType>(native_window), nullptr);

			if (s_surface == EGL_NO_SURFACE)
			{
				rsx_log.fatal("GLES: eglCreateWindowSurface failed (0x%x)", p_eglGetError());
				return false;
			}
		}

		if (!p_eglMakeCurrent(s_display, s_surface, s_surface, static_cast<EGLContext>(context)))
		{
			rsx_log.fatal("GLES: eglMakeCurrent failed (0x%x)", p_eglGetError());
			return false;
		}

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
}

#endif // RSX_GLES
