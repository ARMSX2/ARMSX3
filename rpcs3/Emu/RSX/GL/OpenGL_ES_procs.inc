// GENERATED - do not edit by hand.
// Source: the Khronos GLES headers shipped with the Android NDK.
// Regenerate: python3 android/tools/gen_gles_procs.py <repo-root>
//
// Every OpenGL ES core entry point, expressed as a function pointer rather than a
// link-time symbol. That indirection is the point: it is what lets the core run on
// ANGLE (dlopen'd under a private soname) instead of the system GLES driver.

#ifndef GL_ES_PROC
#error GL_ES_PROC must be defined before including this file
#endif

#define GL_ES_CORE_PROCS \
	/* ---- ES 2.0 ---- */ \
	GL_ES_PROC(void, glActiveTexture, (GLenum texture)) \
	GL_ES_PROC(void, glAttachShader, (GLuint program, GLuint shader)) \
	GL_ES_PROC(void, glBindAttribLocation, (GLuint program, GLuint index, const GLchar *name)) \
	GL_ES_PROC(void, glBindBuffer, (GLenum target, GLuint buffer)) \
	GL_ES_PROC(void, glBindFramebuffer, (GLenum target, GLuint framebuffer)) \
	GL_ES_PROC(void, glBindRenderbuffer, (GLenum target, GLuint renderbuffer)) \
	GL_ES_PROC(void, glBindTexture, (GLenum target, GLuint texture)) \
	GL_ES_PROC(void, glBlendColor, (GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha)) \
	GL_ES_PROC(void, glBlendEquation, (GLenum mode)) \
	GL_ES_PROC(void, glBlendEquationSeparate, (GLenum modeRGB, GLenum modeAlpha)) \
	GL_ES_PROC(void, glBlendFunc, (GLenum sfactor, GLenum dfactor)) \
	GL_ES_PROC(void, glBlendFuncSeparate, (GLenum sfactorRGB, GLenum dfactorRGB, GLenum sfactorAlpha, GLenum dfactorAlpha)) \
	GL_ES_PROC(void, glBufferData, (GLenum target, GLsizeiptr size, const void *data, GLenum usage)) \
	GL_ES_PROC(void, glBufferSubData, (GLenum target, GLintptr offset, GLsizeiptr size, const void *data)) \
	GL_ES_PROC(GLenum, glCheckFramebufferStatus, (GLenum target)) \
	GL_ES_PROC(void, glClear, (GLbitfield mask)) \
	GL_ES_PROC(void, glClearColor, (GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha)) \
	GL_ES_PROC(void, glClearDepthf, (GLfloat d)) \
	GL_ES_PROC(void, glClearStencil, (GLint s)) \
	GL_ES_PROC(void, glColorMask, (GLboolean red, GLboolean green, GLboolean blue, GLboolean alpha)) \
	GL_ES_PROC(void, glCompileShader, (GLuint shader)) \
	GL_ES_PROC(void, glCompressedTexImage2D, (GLenum target, GLint level, GLenum internalformat, GLsizei width, GLsizei height, GLint border, GLsizei imageSize, const void *data)) \
	GL_ES_PROC(void, glCompressedTexSubImage2D, (GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLsizei imageSize, const void *data)) \
	GL_ES_PROC(void, glCopyTexImage2D, (GLenum target, GLint level, GLenum internalformat, GLint x, GLint y, GLsizei width, GLsizei height, GLint border)) \
	GL_ES_PROC(void, glCopyTexSubImage2D, (GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint x, GLint y, GLsizei width, GLsizei height)) \
	GL_ES_PROC(GLuint, glCreateProgram, (void)) \
	GL_ES_PROC(GLuint, glCreateShader, (GLenum type)) \
	GL_ES_PROC(void, glCullFace, (GLenum mode)) \
	GL_ES_PROC(void, glDeleteBuffers, (GLsizei n, const GLuint *buffers)) \
	GL_ES_PROC(void, glDeleteFramebuffers, (GLsizei n, const GLuint *framebuffers)) \
	GL_ES_PROC(void, glDeleteProgram, (GLuint program)) \
	GL_ES_PROC(void, glDeleteRenderbuffers, (GLsizei n, const GLuint *renderbuffers)) \
	GL_ES_PROC(void, glDeleteShader, (GLuint shader)) \
	GL_ES_PROC(void, glDeleteTextures, (GLsizei n, const GLuint *textures)) \
	GL_ES_PROC(void, glDepthFunc, (GLenum func)) \
	GL_ES_PROC(void, glDepthMask, (GLboolean flag)) \
	GL_ES_PROC(void, glDepthRangef, (GLfloat n, GLfloat f)) \
	GL_ES_PROC(void, glDetachShader, (GLuint program, GLuint shader)) \
	GL_ES_PROC(void, glDisable, (GLenum cap)) \
	GL_ES_PROC(void, glDisableVertexAttribArray, (GLuint index)) \
	GL_ES_PROC(void, glDrawArrays, (GLenum mode, GLint first, GLsizei count)) \
	GL_ES_PROC(void, glDrawElements, (GLenum mode, GLsizei count, GLenum type, const void *indices)) \
	GL_ES_PROC(void, glEnable, (GLenum cap)) \
	GL_ES_PROC(void, glEnableVertexAttribArray, (GLuint index)) \
	GL_ES_PROC(void, glFinish, (void)) \
	GL_ES_PROC(void, glFlush, (void)) \
	GL_ES_PROC(void, glFramebufferRenderbuffer, (GLenum target, GLenum attachment, GLenum renderbuffertarget, GLuint renderbuffer)) \
	GL_ES_PROC(void, glFramebufferTexture2D, (GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level)) \
	GL_ES_PROC(void, glFrontFace, (GLenum mode)) \
	GL_ES_PROC(void, glGenBuffers, (GLsizei n, GLuint *buffers)) \
	GL_ES_PROC(void, glGenerateMipmap, (GLenum target)) \
	GL_ES_PROC(void, glGenFramebuffers, (GLsizei n, GLuint *framebuffers)) \
	GL_ES_PROC(void, glGenRenderbuffers, (GLsizei n, GLuint *renderbuffers)) \
	GL_ES_PROC(void, glGenTextures, (GLsizei n, GLuint *textures)) \
	GL_ES_PROC(void, glGetActiveAttrib, (GLuint program, GLuint index, GLsizei bufSize, GLsizei *length, GLint *size, GLenum *type, GLchar *name)) \
	GL_ES_PROC(void, glGetActiveUniform, (GLuint program, GLuint index, GLsizei bufSize, GLsizei *length, GLint *size, GLenum *type, GLchar *name)) \
	GL_ES_PROC(void, glGetAttachedShaders, (GLuint program, GLsizei maxCount, GLsizei *count, GLuint *shaders)) \
	GL_ES_PROC(GLint, glGetAttribLocation, (GLuint program, const GLchar *name)) \
	GL_ES_PROC(void, glGetBooleanv, (GLenum pname, GLboolean *data)) \
	GL_ES_PROC(void, glGetBufferParameteriv, (GLenum target, GLenum pname, GLint *params)) \
	GL_ES_PROC(GLenum, glGetError, (void)) \
	GL_ES_PROC(void, glGetFloatv, (GLenum pname, GLfloat *data)) \
	GL_ES_PROC(void, glGetFramebufferAttachmentParameteriv, (GLenum target, GLenum attachment, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetIntegerv, (GLenum pname, GLint *data)) \
	GL_ES_PROC(void, glGetProgramiv, (GLuint program, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetProgramInfoLog, (GLuint program, GLsizei bufSize, GLsizei *length, GLchar *infoLog)) \
	GL_ES_PROC(void, glGetRenderbufferParameteriv, (GLenum target, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetShaderiv, (GLuint shader, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetShaderInfoLog, (GLuint shader, GLsizei bufSize, GLsizei *length, GLchar *infoLog)) \
	GL_ES_PROC(void, glGetShaderPrecisionFormat, (GLenum shadertype, GLenum precisiontype, GLint *range, GLint *precision)) \
	GL_ES_PROC(void, glGetShaderSource, (GLuint shader, GLsizei bufSize, GLsizei *length, GLchar *source)) \
	GL_ES_PROC(const GLubyte *, glGetString, (GLenum name)) \
	GL_ES_PROC(void, glGetTexParameterfv, (GLenum target, GLenum pname, GLfloat *params)) \
	GL_ES_PROC(void, glGetTexParameteriv, (GLenum target, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetUniformfv, (GLuint program, GLint location, GLfloat *params)) \
	GL_ES_PROC(void, glGetUniformiv, (GLuint program, GLint location, GLint *params)) \
	GL_ES_PROC(GLint, glGetUniformLocation, (GLuint program, const GLchar *name)) \
	GL_ES_PROC(void, glGetVertexAttribfv, (GLuint index, GLenum pname, GLfloat *params)) \
	GL_ES_PROC(void, glGetVertexAttribiv, (GLuint index, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetVertexAttribPointerv, (GLuint index, GLenum pname, void **pointer)) \
	GL_ES_PROC(void, glHint, (GLenum target, GLenum mode)) \
	GL_ES_PROC(GLboolean, glIsBuffer, (GLuint buffer)) \
	GL_ES_PROC(GLboolean, glIsEnabled, (GLenum cap)) \
	GL_ES_PROC(GLboolean, glIsFramebuffer, (GLuint framebuffer)) \
	GL_ES_PROC(GLboolean, glIsProgram, (GLuint program)) \
	GL_ES_PROC(GLboolean, glIsRenderbuffer, (GLuint renderbuffer)) \
	GL_ES_PROC(GLboolean, glIsShader, (GLuint shader)) \
	GL_ES_PROC(GLboolean, glIsTexture, (GLuint texture)) \
	GL_ES_PROC(void, glLineWidth, (GLfloat width)) \
	GL_ES_PROC(void, glLinkProgram, (GLuint program)) \
	GL_ES_PROC(void, glPixelStorei, (GLenum pname, GLint param)) \
	GL_ES_PROC(void, glPolygonOffset, (GLfloat factor, GLfloat units)) \
	GL_ES_PROC(void, glReadPixels, (GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, void *pixels)) \
	GL_ES_PROC(void, glReleaseShaderCompiler, (void)) \
	GL_ES_PROC(void, glRenderbufferStorage, (GLenum target, GLenum internalformat, GLsizei width, GLsizei height)) \
	GL_ES_PROC(void, glSampleCoverage, (GLfloat value, GLboolean invert)) \
	GL_ES_PROC(void, glScissor, (GLint x, GLint y, GLsizei width, GLsizei height)) \
	GL_ES_PROC(void, glShaderBinary, (GLsizei count, const GLuint *shaders, GLenum binaryformat, const void *binary, GLsizei length)) \
	GL_ES_PROC(void, glShaderSource, (GLuint shader, GLsizei count, const GLchar *const*string, const GLint *length)) \
	GL_ES_PROC(void, glStencilFunc, (GLenum func, GLint ref, GLuint mask)) \
	GL_ES_PROC(void, glStencilFuncSeparate, (GLenum face, GLenum func, GLint ref, GLuint mask)) \
	GL_ES_PROC(void, glStencilMask, (GLuint mask)) \
	GL_ES_PROC(void, glStencilMaskSeparate, (GLenum face, GLuint mask)) \
	GL_ES_PROC(void, glStencilOp, (GLenum fail, GLenum zfail, GLenum zpass)) \
	GL_ES_PROC(void, glStencilOpSeparate, (GLenum face, GLenum sfail, GLenum dpfail, GLenum dppass)) \
	GL_ES_PROC(void, glTexImage2D, (GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLint border, GLenum format, GLenum type, const void *pixels)) \
	GL_ES_PROC(void, glTexParameterf, (GLenum target, GLenum pname, GLfloat param)) \
	GL_ES_PROC(void, glTexParameterfv, (GLenum target, GLenum pname, const GLfloat *params)) \
	GL_ES_PROC(void, glTexParameteri, (GLenum target, GLenum pname, GLint param)) \
	GL_ES_PROC(void, glTexParameteriv, (GLenum target, GLenum pname, const GLint *params)) \
	GL_ES_PROC(void, glTexSubImage2D, (GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const void *pixels)) \
	GL_ES_PROC(void, glUniform1f, (GLint location, GLfloat v0)) \
	GL_ES_PROC(void, glUniform1fv, (GLint location, GLsizei count, const GLfloat *value)) \
	GL_ES_PROC(void, glUniform1i, (GLint location, GLint v0)) \
	GL_ES_PROC(void, glUniform1iv, (GLint location, GLsizei count, const GLint *value)) \
	GL_ES_PROC(void, glUniform2f, (GLint location, GLfloat v0, GLfloat v1)) \
	GL_ES_PROC(void, glUniform2fv, (GLint location, GLsizei count, const GLfloat *value)) \
	GL_ES_PROC(void, glUniform2i, (GLint location, GLint v0, GLint v1)) \
	GL_ES_PROC(void, glUniform2iv, (GLint location, GLsizei count, const GLint *value)) \
	GL_ES_PROC(void, glUniform3f, (GLint location, GLfloat v0, GLfloat v1, GLfloat v2)) \
	GL_ES_PROC(void, glUniform3fv, (GLint location, GLsizei count, const GLfloat *value)) \
	GL_ES_PROC(void, glUniform3i, (GLint location, GLint v0, GLint v1, GLint v2)) \
	GL_ES_PROC(void, glUniform3iv, (GLint location, GLsizei count, const GLint *value)) \
	GL_ES_PROC(void, glUniform4f, (GLint location, GLfloat v0, GLfloat v1, GLfloat v2, GLfloat v3)) \
	GL_ES_PROC(void, glUniform4fv, (GLint location, GLsizei count, const GLfloat *value)) \
	GL_ES_PROC(void, glUniform4i, (GLint location, GLint v0, GLint v1, GLint v2, GLint v3)) \
	GL_ES_PROC(void, glUniform4iv, (GLint location, GLsizei count, const GLint *value)) \
	GL_ES_PROC(void, glUniformMatrix2fv, (GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glUniformMatrix3fv, (GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glUniformMatrix4fv, (GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glUseProgram, (GLuint program)) \
	GL_ES_PROC(void, glValidateProgram, (GLuint program)) \
	GL_ES_PROC(void, glVertexAttrib1f, (GLuint index, GLfloat x)) \
	GL_ES_PROC(void, glVertexAttrib1fv, (GLuint index, const GLfloat *v)) \
	GL_ES_PROC(void, glVertexAttrib2f, (GLuint index, GLfloat x, GLfloat y)) \
	GL_ES_PROC(void, glVertexAttrib2fv, (GLuint index, const GLfloat *v)) \
	GL_ES_PROC(void, glVertexAttrib3f, (GLuint index, GLfloat x, GLfloat y, GLfloat z)) \
	GL_ES_PROC(void, glVertexAttrib3fv, (GLuint index, const GLfloat *v)) \
	GL_ES_PROC(void, glVertexAttrib4f, (GLuint index, GLfloat x, GLfloat y, GLfloat z, GLfloat w)) \
	GL_ES_PROC(void, glVertexAttrib4fv, (GLuint index, const GLfloat *v)) \
	GL_ES_PROC(void, glVertexAttribPointer, (GLuint index, GLint size, GLenum type, GLboolean normalized, GLsizei stride, const void *pointer)) \
	GL_ES_PROC(void, glViewport, (GLint x, GLint y, GLsizei width, GLsizei height)) \
	/* ---- ES 3.0 ---- */ \
	GL_ES_PROC(void, glReadBuffer, (GLenum src)) \
	GL_ES_PROC(void, glDrawRangeElements, (GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, const void *indices)) \
	GL_ES_PROC(void, glTexImage3D, (GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLsizei depth, GLint border, GLenum format, GLenum type, const void *pixels)) \
	GL_ES_PROC(void, glTexSubImage3D, (GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, const void *pixels)) \
	GL_ES_PROC(void, glCopyTexSubImage3D, (GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLint x, GLint y, GLsizei width, GLsizei height)) \
	GL_ES_PROC(void, glCompressedTexImage3D, (GLenum target, GLint level, GLenum internalformat, GLsizei width, GLsizei height, GLsizei depth, GLint border, GLsizei imageSize, const void *data)) \
	GL_ES_PROC(void, glCompressedTexSubImage3D, (GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLsizei imageSize, const void *data)) \
	GL_ES_PROC(void, glGenQueries, (GLsizei n, GLuint *ids)) \
	GL_ES_PROC(void, glDeleteQueries, (GLsizei n, const GLuint *ids)) \
	GL_ES_PROC(GLboolean, glIsQuery, (GLuint id)) \
	GL_ES_PROC(void, glBeginQuery, (GLenum target, GLuint id)) \
	GL_ES_PROC(void, glEndQuery, (GLenum target)) \
	GL_ES_PROC(void, glGetQueryiv, (GLenum target, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetQueryObjectuiv, (GLuint id, GLenum pname, GLuint *params)) \
	GL_ES_PROC(GLboolean, glUnmapBuffer, (GLenum target)) \
	GL_ES_PROC(void, glGetBufferPointerv, (GLenum target, GLenum pname, void **params)) \
	GL_ES_PROC(void, glDrawBuffers, (GLsizei n, const GLenum *bufs)) \
	GL_ES_PROC(void, glUniformMatrix2x3fv, (GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glUniformMatrix3x2fv, (GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glUniformMatrix2x4fv, (GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glUniformMatrix4x2fv, (GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glUniformMatrix3x4fv, (GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glUniformMatrix4x3fv, (GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glBlitFramebuffer, (GLint srcX0, GLint srcY0, GLint srcX1, GLint srcY1, GLint dstX0, GLint dstY0, GLint dstX1, GLint dstY1, GLbitfield mask, GLenum filter)) \
	GL_ES_PROC(void, glRenderbufferStorageMultisample, (GLenum target, GLsizei samples, GLenum internalformat, GLsizei width, GLsizei height)) \
	GL_ES_PROC(void, glFramebufferTextureLayer, (GLenum target, GLenum attachment, GLuint texture, GLint level, GLint layer)) \
	GL_ES_PROC(void *, glMapBufferRange, (GLenum target, GLintptr offset, GLsizeiptr length, GLbitfield access)) \
	GL_ES_PROC(void, glFlushMappedBufferRange, (GLenum target, GLintptr offset, GLsizeiptr length)) \
	GL_ES_PROC(void, glBindVertexArray, (GLuint array)) \
	GL_ES_PROC(void, glDeleteVertexArrays, (GLsizei n, const GLuint *arrays)) \
	GL_ES_PROC(void, glGenVertexArrays, (GLsizei n, GLuint *arrays)) \
	GL_ES_PROC(GLboolean, glIsVertexArray, (GLuint array)) \
	GL_ES_PROC(void, glGetIntegeri_v, (GLenum target, GLuint index, GLint *data)) \
	GL_ES_PROC(void, glBeginTransformFeedback, (GLenum primitiveMode)) \
	GL_ES_PROC(void, glEndTransformFeedback, (void)) \
	GL_ES_PROC(void, glBindBufferRange, (GLenum target, GLuint index, GLuint buffer, GLintptr offset, GLsizeiptr size)) \
	GL_ES_PROC(void, glBindBufferBase, (GLenum target, GLuint index, GLuint buffer)) \
	GL_ES_PROC(void, glTransformFeedbackVaryings, (GLuint program, GLsizei count, const GLchar *const*varyings, GLenum bufferMode)) \
	GL_ES_PROC(void, glGetTransformFeedbackVarying, (GLuint program, GLuint index, GLsizei bufSize, GLsizei *length, GLsizei *size, GLenum *type, GLchar *name)) \
	GL_ES_PROC(void, glVertexAttribIPointer, (GLuint index, GLint size, GLenum type, GLsizei stride, const void *pointer)) \
	GL_ES_PROC(void, glGetVertexAttribIiv, (GLuint index, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetVertexAttribIuiv, (GLuint index, GLenum pname, GLuint *params)) \
	GL_ES_PROC(void, glVertexAttribI4i, (GLuint index, GLint x, GLint y, GLint z, GLint w)) \
	GL_ES_PROC(void, glVertexAttribI4ui, (GLuint index, GLuint x, GLuint y, GLuint z, GLuint w)) \
	GL_ES_PROC(void, glVertexAttribI4iv, (GLuint index, const GLint *v)) \
	GL_ES_PROC(void, glVertexAttribI4uiv, (GLuint index, const GLuint *v)) \
	GL_ES_PROC(void, glGetUniformuiv, (GLuint program, GLint location, GLuint *params)) \
	GL_ES_PROC(GLint, glGetFragDataLocation, (GLuint program, const GLchar *name)) \
	GL_ES_PROC(void, glUniform1ui, (GLint location, GLuint v0)) \
	GL_ES_PROC(void, glUniform2ui, (GLint location, GLuint v0, GLuint v1)) \
	GL_ES_PROC(void, glUniform3ui, (GLint location, GLuint v0, GLuint v1, GLuint v2)) \
	GL_ES_PROC(void, glUniform4ui, (GLint location, GLuint v0, GLuint v1, GLuint v2, GLuint v3)) \
	GL_ES_PROC(void, glUniform1uiv, (GLint location, GLsizei count, const GLuint *value)) \
	GL_ES_PROC(void, glUniform2uiv, (GLint location, GLsizei count, const GLuint *value)) \
	GL_ES_PROC(void, glUniform3uiv, (GLint location, GLsizei count, const GLuint *value)) \
	GL_ES_PROC(void, glUniform4uiv, (GLint location, GLsizei count, const GLuint *value)) \
	GL_ES_PROC(void, glClearBufferiv, (GLenum buffer, GLint drawbuffer, const GLint *value)) \
	GL_ES_PROC(void, glClearBufferuiv, (GLenum buffer, GLint drawbuffer, const GLuint *value)) \
	GL_ES_PROC(void, glClearBufferfv, (GLenum buffer, GLint drawbuffer, const GLfloat *value)) \
	GL_ES_PROC(void, glClearBufferfi, (GLenum buffer, GLint drawbuffer, GLfloat depth, GLint stencil)) \
	GL_ES_PROC(const GLubyte *, glGetStringi, (GLenum name, GLuint index)) \
	GL_ES_PROC(void, glCopyBufferSubData, (GLenum readTarget, GLenum writeTarget, GLintptr readOffset, GLintptr writeOffset, GLsizeiptr size)) \
	GL_ES_PROC(void, glGetUniformIndices, (GLuint program, GLsizei uniformCount, const GLchar *const*uniformNames, GLuint *uniformIndices)) \
	GL_ES_PROC(void, glGetActiveUniformsiv, (GLuint program, GLsizei uniformCount, const GLuint *uniformIndices, GLenum pname, GLint *params)) \
	GL_ES_PROC(GLuint, glGetUniformBlockIndex, (GLuint program, const GLchar *uniformBlockName)) \
	GL_ES_PROC(void, glGetActiveUniformBlockiv, (GLuint program, GLuint uniformBlockIndex, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetActiveUniformBlockName, (GLuint program, GLuint uniformBlockIndex, GLsizei bufSize, GLsizei *length, GLchar *uniformBlockName)) \
	GL_ES_PROC(void, glUniformBlockBinding, (GLuint program, GLuint uniformBlockIndex, GLuint uniformBlockBinding)) \
	GL_ES_PROC(void, glDrawArraysInstanced, (GLenum mode, GLint first, GLsizei count, GLsizei instancecount)) \
	GL_ES_PROC(void, glDrawElementsInstanced, (GLenum mode, GLsizei count, GLenum type, const void *indices, GLsizei instancecount)) \
	GL_ES_PROC(GLsync, glFenceSync, (GLenum condition, GLbitfield flags)) \
	GL_ES_PROC(GLboolean, glIsSync, (GLsync sync)) \
	GL_ES_PROC(void, glDeleteSync, (GLsync sync)) \
	GL_ES_PROC(GLenum, glClientWaitSync, (GLsync sync, GLbitfield flags, GLuint64 timeout)) \
	GL_ES_PROC(void, glWaitSync, (GLsync sync, GLbitfield flags, GLuint64 timeout)) \
	GL_ES_PROC(void, glGetInteger64v, (GLenum pname, GLint64 *data)) \
	GL_ES_PROC(void, glGetSynciv, (GLsync sync, GLenum pname, GLsizei bufSize, GLsizei *length, GLint *values)) \
	GL_ES_PROC(void, glGetInteger64i_v, (GLenum target, GLuint index, GLint64 *data)) \
	GL_ES_PROC(void, glGetBufferParameteri64v, (GLenum target, GLenum pname, GLint64 *params)) \
	GL_ES_PROC(void, glGenSamplers, (GLsizei count, GLuint *samplers)) \
	GL_ES_PROC(void, glDeleteSamplers, (GLsizei count, const GLuint *samplers)) \
	GL_ES_PROC(GLboolean, glIsSampler, (GLuint sampler)) \
	GL_ES_PROC(void, glBindSampler, (GLuint unit, GLuint sampler)) \
	GL_ES_PROC(void, glSamplerParameteri, (GLuint sampler, GLenum pname, GLint param)) \
	GL_ES_PROC(void, glSamplerParameteriv, (GLuint sampler, GLenum pname, const GLint *param)) \
	GL_ES_PROC(void, glSamplerParameterf, (GLuint sampler, GLenum pname, GLfloat param)) \
	GL_ES_PROC(void, glSamplerParameterfv, (GLuint sampler, GLenum pname, const GLfloat *param)) \
	GL_ES_PROC(void, glGetSamplerParameteriv, (GLuint sampler, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetSamplerParameterfv, (GLuint sampler, GLenum pname, GLfloat *params)) \
	GL_ES_PROC(void, glVertexAttribDivisor, (GLuint index, GLuint divisor)) \
	GL_ES_PROC(void, glBindTransformFeedback, (GLenum target, GLuint id)) \
	GL_ES_PROC(void, glDeleteTransformFeedbacks, (GLsizei n, const GLuint *ids)) \
	GL_ES_PROC(void, glGenTransformFeedbacks, (GLsizei n, GLuint *ids)) \
	GL_ES_PROC(GLboolean, glIsTransformFeedback, (GLuint id)) \
	GL_ES_PROC(void, glPauseTransformFeedback, (void)) \
	GL_ES_PROC(void, glResumeTransformFeedback, (void)) \
	GL_ES_PROC(void, glGetProgramBinary, (GLuint program, GLsizei bufSize, GLsizei *length, GLenum *binaryFormat, void *binary)) \
	GL_ES_PROC(void, glProgramBinary, (GLuint program, GLenum binaryFormat, const void *binary, GLsizei length)) \
	GL_ES_PROC(void, glProgramParameteri, (GLuint program, GLenum pname, GLint value)) \
	GL_ES_PROC(void, glInvalidateFramebuffer, (GLenum target, GLsizei numAttachments, const GLenum *attachments)) \
	GL_ES_PROC(void, glInvalidateSubFramebuffer, (GLenum target, GLsizei numAttachments, const GLenum *attachments, GLint x, GLint y, GLsizei width, GLsizei height)) \
	GL_ES_PROC(void, glTexStorage2D, (GLenum target, GLsizei levels, GLenum internalformat, GLsizei width, GLsizei height)) \
	GL_ES_PROC(void, glTexStorage3D, (GLenum target, GLsizei levels, GLenum internalformat, GLsizei width, GLsizei height, GLsizei depth)) \
	GL_ES_PROC(void, glGetInternalformativ, (GLenum target, GLenum internalformat, GLenum pname, GLsizei bufSize, GLint *params)) \
	/* ---- ES 3.1 ---- */ \
	GL_ES_PROC(void, glDispatchCompute, (GLuint num_groups_x, GLuint num_groups_y, GLuint num_groups_z)) \
	GL_ES_PROC(void, glDispatchComputeIndirect, (GLintptr indirect)) \
	GL_ES_PROC(void, glDrawArraysIndirect, (GLenum mode, const void *indirect)) \
	GL_ES_PROC(void, glDrawElementsIndirect, (GLenum mode, GLenum type, const void *indirect)) \
	GL_ES_PROC(void, glFramebufferParameteri, (GLenum target, GLenum pname, GLint param)) \
	GL_ES_PROC(void, glGetFramebufferParameteriv, (GLenum target, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetProgramInterfaceiv, (GLuint program, GLenum programInterface, GLenum pname, GLint *params)) \
	GL_ES_PROC(GLuint, glGetProgramResourceIndex, (GLuint program, GLenum programInterface, const GLchar *name)) \
	GL_ES_PROC(void, glGetProgramResourceName, (GLuint program, GLenum programInterface, GLuint index, GLsizei bufSize, GLsizei *length, GLchar *name)) \
	GL_ES_PROC(void, glGetProgramResourceiv, (GLuint program, GLenum programInterface, GLuint index, GLsizei propCount, const GLenum *props, GLsizei bufSize, GLsizei *length, GLint *params)) \
	GL_ES_PROC(GLint, glGetProgramResourceLocation, (GLuint program, GLenum programInterface, const GLchar *name)) \
	GL_ES_PROC(void, glUseProgramStages, (GLuint pipeline, GLbitfield stages, GLuint program)) \
	GL_ES_PROC(void, glActiveShaderProgram, (GLuint pipeline, GLuint program)) \
	GL_ES_PROC(GLuint, glCreateShaderProgramv, (GLenum type, GLsizei count, const GLchar *const*strings)) \
	GL_ES_PROC(void, glBindProgramPipeline, (GLuint pipeline)) \
	GL_ES_PROC(void, glDeleteProgramPipelines, (GLsizei n, const GLuint *pipelines)) \
	GL_ES_PROC(void, glGenProgramPipelines, (GLsizei n, GLuint *pipelines)) \
	GL_ES_PROC(GLboolean, glIsProgramPipeline, (GLuint pipeline)) \
	GL_ES_PROC(void, glGetProgramPipelineiv, (GLuint pipeline, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glProgramUniform1i, (GLuint program, GLint location, GLint v0)) \
	GL_ES_PROC(void, glProgramUniform2i, (GLuint program, GLint location, GLint v0, GLint v1)) \
	GL_ES_PROC(void, glProgramUniform3i, (GLuint program, GLint location, GLint v0, GLint v1, GLint v2)) \
	GL_ES_PROC(void, glProgramUniform4i, (GLuint program, GLint location, GLint v0, GLint v1, GLint v2, GLint v3)) \
	GL_ES_PROC(void, glProgramUniform1ui, (GLuint program, GLint location, GLuint v0)) \
	GL_ES_PROC(void, glProgramUniform2ui, (GLuint program, GLint location, GLuint v0, GLuint v1)) \
	GL_ES_PROC(void, glProgramUniform3ui, (GLuint program, GLint location, GLuint v0, GLuint v1, GLuint v2)) \
	GL_ES_PROC(void, glProgramUniform4ui, (GLuint program, GLint location, GLuint v0, GLuint v1, GLuint v2, GLuint v3)) \
	GL_ES_PROC(void, glProgramUniform1f, (GLuint program, GLint location, GLfloat v0)) \
	GL_ES_PROC(void, glProgramUniform2f, (GLuint program, GLint location, GLfloat v0, GLfloat v1)) \
	GL_ES_PROC(void, glProgramUniform3f, (GLuint program, GLint location, GLfloat v0, GLfloat v1, GLfloat v2)) \
	GL_ES_PROC(void, glProgramUniform4f, (GLuint program, GLint location, GLfloat v0, GLfloat v1, GLfloat v2, GLfloat v3)) \
	GL_ES_PROC(void, glProgramUniform1iv, (GLuint program, GLint location, GLsizei count, const GLint *value)) \
	GL_ES_PROC(void, glProgramUniform2iv, (GLuint program, GLint location, GLsizei count, const GLint *value)) \
	GL_ES_PROC(void, glProgramUniform3iv, (GLuint program, GLint location, GLsizei count, const GLint *value)) \
	GL_ES_PROC(void, glProgramUniform4iv, (GLuint program, GLint location, GLsizei count, const GLint *value)) \
	GL_ES_PROC(void, glProgramUniform1uiv, (GLuint program, GLint location, GLsizei count, const GLuint *value)) \
	GL_ES_PROC(void, glProgramUniform2uiv, (GLuint program, GLint location, GLsizei count, const GLuint *value)) \
	GL_ES_PROC(void, glProgramUniform3uiv, (GLuint program, GLint location, GLsizei count, const GLuint *value)) \
	GL_ES_PROC(void, glProgramUniform4uiv, (GLuint program, GLint location, GLsizei count, const GLuint *value)) \
	GL_ES_PROC(void, glProgramUniform1fv, (GLuint program, GLint location, GLsizei count, const GLfloat *value)) \
	GL_ES_PROC(void, glProgramUniform2fv, (GLuint program, GLint location, GLsizei count, const GLfloat *value)) \
	GL_ES_PROC(void, glProgramUniform3fv, (GLuint program, GLint location, GLsizei count, const GLfloat *value)) \
	GL_ES_PROC(void, glProgramUniform4fv, (GLuint program, GLint location, GLsizei count, const GLfloat *value)) \
	GL_ES_PROC(void, glProgramUniformMatrix2fv, (GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glProgramUniformMatrix3fv, (GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glProgramUniformMatrix4fv, (GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glProgramUniformMatrix2x3fv, (GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glProgramUniformMatrix3x2fv, (GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glProgramUniformMatrix2x4fv, (GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glProgramUniformMatrix4x2fv, (GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glProgramUniformMatrix3x4fv, (GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glProgramUniformMatrix4x3fv, (GLuint program, GLint location, GLsizei count, GLboolean transpose, const GLfloat *value)) \
	GL_ES_PROC(void, glValidateProgramPipeline, (GLuint pipeline)) \
	GL_ES_PROC(void, glGetProgramPipelineInfoLog, (GLuint pipeline, GLsizei bufSize, GLsizei *length, GLchar *infoLog)) \
	GL_ES_PROC(void, glBindImageTexture, (GLuint unit, GLuint texture, GLint level, GLboolean layered, GLint layer, GLenum access, GLenum format)) \
	GL_ES_PROC(void, glGetBooleani_v, (GLenum target, GLuint index, GLboolean *data)) \
	GL_ES_PROC(void, glMemoryBarrier, (GLbitfield barriers)) \
	GL_ES_PROC(void, glMemoryBarrierByRegion, (GLbitfield barriers)) \
	GL_ES_PROC(void, glTexStorage2DMultisample, (GLenum target, GLsizei samples, GLenum internalformat, GLsizei width, GLsizei height, GLboolean fixedsamplelocations)) \
	GL_ES_PROC(void, glGetMultisamplefv, (GLenum pname, GLuint index, GLfloat *val)) \
	GL_ES_PROC(void, glSampleMaski, (GLuint maskNumber, GLbitfield mask)) \
	GL_ES_PROC(void, glGetTexLevelParameteriv, (GLenum target, GLint level, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetTexLevelParameterfv, (GLenum target, GLint level, GLenum pname, GLfloat *params)) \
	GL_ES_PROC(void, glBindVertexBuffer, (GLuint bindingindex, GLuint buffer, GLintptr offset, GLsizei stride)) \
	GL_ES_PROC(void, glVertexAttribFormat, (GLuint attribindex, GLint size, GLenum type, GLboolean normalized, GLuint relativeoffset)) \
	GL_ES_PROC(void, glVertexAttribIFormat, (GLuint attribindex, GLint size, GLenum type, GLuint relativeoffset)) \
	GL_ES_PROC(void, glVertexAttribBinding, (GLuint attribindex, GLuint bindingindex)) \
	GL_ES_PROC(void, glVertexBindingDivisor, (GLuint bindingindex, GLuint divisor)) \
	/* ---- ES 3.2 ---- */ \
	GL_ES_PROC(void, glBlendBarrier, (void)) \
	GL_ES_PROC(void, glCopyImageSubData, (GLuint srcName, GLenum srcTarget, GLint srcLevel, GLint srcX, GLint srcY, GLint srcZ, GLuint dstName, GLenum dstTarget, GLint dstLevel, GLint dstX, GLint dstY, GLint dstZ, GLsizei srcWidth, GLsizei srcHeight, GLsizei srcDepth)) \
	GL_ES_PROC(void, glDebugMessageControl, (GLenum source, GLenum type, GLenum severity, GLsizei count, const GLuint *ids, GLboolean enabled)) \
	GL_ES_PROC(void, glDebugMessageInsert, (GLenum source, GLenum type, GLuint id, GLenum severity, GLsizei length, const GLchar *buf)) \
	GL_ES_PROC(void, glDebugMessageCallback, (GLDEBUGPROC callback, const void *userParam)) \
	GL_ES_PROC(GLuint, glGetDebugMessageLog, (GLuint count, GLsizei bufSize, GLenum *sources, GLenum *types, GLuint *ids, GLenum *severities, GLsizei *lengths, GLchar *messageLog)) \
	GL_ES_PROC(void, glPushDebugGroup, (GLenum source, GLuint id, GLsizei length, const GLchar *message)) \
	GL_ES_PROC(void, glPopDebugGroup, (void)) \
	GL_ES_PROC(void, glObjectLabel, (GLenum identifier, GLuint name, GLsizei length, const GLchar *label)) \
	GL_ES_PROC(void, glGetObjectLabel, (GLenum identifier, GLuint name, GLsizei bufSize, GLsizei *length, GLchar *label)) \
	GL_ES_PROC(void, glObjectPtrLabel, (const void *ptr, GLsizei length, const GLchar *label)) \
	GL_ES_PROC(void, glGetObjectPtrLabel, (const void *ptr, GLsizei bufSize, GLsizei *length, GLchar *label)) \
	GL_ES_PROC(void, glGetPointerv, (GLenum pname, void **params)) \
	GL_ES_PROC(void, glEnablei, (GLenum target, GLuint index)) \
	GL_ES_PROC(void, glDisablei, (GLenum target, GLuint index)) \
	GL_ES_PROC(void, glBlendEquationi, (GLuint buf, GLenum mode)) \
	GL_ES_PROC(void, glBlendEquationSeparatei, (GLuint buf, GLenum modeRGB, GLenum modeAlpha)) \
	GL_ES_PROC(void, glBlendFunci, (GLuint buf, GLenum src, GLenum dst)) \
	GL_ES_PROC(void, glBlendFuncSeparatei, (GLuint buf, GLenum srcRGB, GLenum dstRGB, GLenum srcAlpha, GLenum dstAlpha)) \
	GL_ES_PROC(void, glColorMaski, (GLuint index, GLboolean r, GLboolean g, GLboolean b, GLboolean a)) \
	GL_ES_PROC(GLboolean, glIsEnabledi, (GLenum target, GLuint index)) \
	GL_ES_PROC(void, glDrawElementsBaseVertex, (GLenum mode, GLsizei count, GLenum type, const void *indices, GLint basevertex)) \
	GL_ES_PROC(void, glDrawRangeElementsBaseVertex, (GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, const void *indices, GLint basevertex)) \
	GL_ES_PROC(void, glDrawElementsInstancedBaseVertex, (GLenum mode, GLsizei count, GLenum type, const void *indices, GLsizei instancecount, GLint basevertex)) \
	GL_ES_PROC(void, glFramebufferTexture, (GLenum target, GLenum attachment, GLuint texture, GLint level)) \
	GL_ES_PROC(void, glPrimitiveBoundingBox, (GLfloat minX, GLfloat minY, GLfloat minZ, GLfloat minW, GLfloat maxX, GLfloat maxY, GLfloat maxZ, GLfloat maxW)) \
	GL_ES_PROC(GLenum, glGetGraphicsResetStatus, (void)) \
	GL_ES_PROC(void, glReadnPixels, (GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, GLsizei bufSize, void *data)) \
	GL_ES_PROC(void, glGetnUniformfv, (GLuint program, GLint location, GLsizei bufSize, GLfloat *params)) \
	GL_ES_PROC(void, glGetnUniformiv, (GLuint program, GLint location, GLsizei bufSize, GLint *params)) \
	GL_ES_PROC(void, glGetnUniformuiv, (GLuint program, GLint location, GLsizei bufSize, GLuint *params)) \
	GL_ES_PROC(void, glMinSampleShading, (GLfloat value)) \
	GL_ES_PROC(void, glPatchParameteri, (GLenum pname, GLint value)) \
	GL_ES_PROC(void, glTexParameterIiv, (GLenum target, GLenum pname, const GLint *params)) \
	GL_ES_PROC(void, glTexParameterIuiv, (GLenum target, GLenum pname, const GLuint *params)) \
	GL_ES_PROC(void, glGetTexParameterIiv, (GLenum target, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetTexParameterIuiv, (GLenum target, GLenum pname, GLuint *params)) \
	GL_ES_PROC(void, glSamplerParameterIiv, (GLuint sampler, GLenum pname, const GLint *param)) \
	GL_ES_PROC(void, glSamplerParameterIuiv, (GLuint sampler, GLenum pname, const GLuint *param)) \
	GL_ES_PROC(void, glGetSamplerParameterIiv, (GLuint sampler, GLenum pname, GLint *params)) \
	GL_ES_PROC(void, glGetSamplerParameterIuiv, (GLuint sampler, GLenum pname, GLuint *params)) \
	GL_ES_PROC(void, glTexBuffer, (GLenum target, GLenum internalformat, GLuint buffer)) \
	GL_ES_PROC(void, glTexBufferRange, (GLenum target, GLenum internalformat, GLuint buffer, GLintptr offset, GLsizeiptr size)) \
	GL_ES_PROC(void, glTexStorage3DMultisample, (GLenum target, GLsizei samples, GLenum internalformat, GLsizei width, GLsizei height, GLsizei depth, GLboolean fixedsamplelocations)) \
	/* end */
