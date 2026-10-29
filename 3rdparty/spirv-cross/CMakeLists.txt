# SPIRV-Cross

# ARMSX3: the OpenGL renderer on Android runs RPCS3's desktop GLSL through glslang into SPIR-V,
# and SPIRV-Cross writes that back out as GLSL ES (rpcs3/Emu/RSX/GL/GLSLES.cpp). Only the GLSL
# backend is needed.
set(SPIRV_CROSS_CLI OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_HLSL OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_MSL OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_CPP OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_REFLECT OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_C_API OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_ENABLE_UTIL OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_SHARED OFF CACHE BOOL "" FORCE)
set(SPIRV_CROSS_STATIC ON CACHE BOOL "" FORCE)
set(SPIRV_CROSS_SKIP_INSTALL ON CACHE BOOL "" FORCE)
add_subdirectory(SPIRV-Cross EXCLUDE_FROM_ALL)

# The emulator is built with -fno-exceptions (ConfigureCompiler.cmake), and SPIRV-Cross reports a
# shader it cannot translate by throwing; built the same way, that would abort the app. So it keeps
# exceptions, and so does the one file that calls it, whose catch turns every failure into a false
# return before it can reach code built without them.
foreach(target spirv-cross-core spirv-cross-glsl)
	target_compile_options(${target} PRIVATE -fexceptions -frtti)
endforeach()

add_library(3rdparty_spirv_cross STATIC spirv_to_essl.cpp)
target_compile_options(3rdparty_spirv_cross PRIVATE -fexceptions -frtti)
target_include_directories(3rdparty_spirv_cross INTERFACE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(3rdparty_spirv_cross PRIVATE spirv-cross-glsl spirv-cross-core)
