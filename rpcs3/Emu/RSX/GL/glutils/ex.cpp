#include "stdafx.h"
#include "ex.h"

namespace gl::ex
{
	// glGetIntegerv names a binding, not a target. Asked for GL_UNIFORM_BUFFER itself it fails,
	// and the target was cleared rather than restored, which for GL_ELEMENT_ARRAY_BUFFER took the
	// bound vertex array's index buffer with it.
	static GLenum binding_of(GLenum target)
	{
		switch (target)
		{
		case GL_ARRAY_BUFFER: return GL_ARRAY_BUFFER_BINDING;
		case GL_ELEMENT_ARRAY_BUFFER: return GL_ELEMENT_ARRAY_BUFFER_BINDING;
		case GL_PIXEL_PACK_BUFFER: return GL_PIXEL_PACK_BUFFER_BINDING;
		case GL_PIXEL_UNPACK_BUFFER: return GL_PIXEL_UNPACK_BUFFER_BINDING;
		case GL_UNIFORM_BUFFER: return GL_UNIFORM_BUFFER_BINDING;
		case GL_TEXTURE_BUFFER: return GL_TEXTURE_BUFFER_BINDING;
		case GL_SHADER_STORAGE_BUFFER: return GL_SHADER_STORAGE_BUFFER_BINDING;
		case GL_COPY_READ_BUFFER: return GL_COPY_READ_BUFFER_BINDING;
		case GL_COPY_WRITE_BUFFER: return GL_COPY_WRITE_BUFFER_BINDING;
		default: return GL_NONE;
		}
	}

	void glNamedBufferStorageEX(GLuint buffer, GLenum target, GLsizeiptr size, const void* data, GLbitfield flags)
	{
		GLuint restore = GL_NONE;
		if (const GLenum binding = binding_of(target))
		{
			glGetIntegerv(binding, utils::bless<GLint>(&restore));
		}


		glBindBuffer(target, buffer);
		glBufferStorage(target, size, data, flags);

		if (restore != buffer)
		{
			glBindBuffer(target, restore);
		}
	}
}
