# libarmsx3_lsfg.so -- Lossless Scaling frame generation, sealed away from the emulator core.
#
# The entire reason this is a separate shared object is symbol collision. volk defines 655 globals
# named vkCreateImage, vkQueueSubmit, ... and all 124 that our Vulkan loader declares in
# rpcs3/Emu/RSX/VK/vk_android_loader.h are among them. Linked into libarmsx3-core.so this either
# fails at link or, worse, merges -- and framegen's volkLoadDevice(itsOwnDevice) then repoints the
# whole RSX renderer at framegen's VkDevice. See armsx3_lsfg_shim.h.
#
# Android only. framegen's non-Android path shares images by FD, which Adreno and Mali refuse for
# AHB-imported memory, so there is nothing here worth building for desktop.

if (NOT ANDROID)
	return()
endif()

set(LSFG_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/lsfg-vk-android")

if (NOT EXISTS "${LSFG_ROOT}/framegen/CMakeLists.txt")
	message(STATUS "LSFG: 3rdparty/lsfg/lsfg-vk-android is missing, frame generation will not be built")
	return()
endif()

if (NOT EXISTS "${LSFG_ROOT}/thirdparty/volk/volk.c")
	# Called out explicitly because the failure is otherwise mystifying: framegen links volk
	# PUBLIC, so without it the error names framegen rather than the submodule that is missing.
	message(STATUS "LSFG: thirdparty/volk is missing (git submodule update --init), skipping")
	return()
endif()

# volk, built for Android.
#
# VK_USE_PLATFORM_ANDROID_KHR has to be set on VOLK ITSELF, not only on framegen. Without it volk
# never defines vkGetAndroidHardwareBufferPropertiesANDROID, and the resulting undefined symbol
# points at framegen -- sending you to debug the wrong target entirely.
add_library(armsx3_lsfg_volk STATIC "${LSFG_ROOT}/thirdparty/volk/volk.c")
target_include_directories(armsx3_lsfg_volk PUBLIC "${LSFG_ROOT}/thirdparty/volk")
target_compile_definitions(armsx3_lsfg_volk PUBLIC VK_USE_PLATFORM_ANDROID_KHR VK_NO_PROTOTYPES)
set_target_properties(armsx3_lsfg_volk PROPERTIES
	POSITION_INDEPENDENT_CODE ON
	C_VISIBILITY_PRESET hidden)

# framegen.
#
# Its own CMakeLists expects a target called `volk`, so alias ours rather than patching upstream.
if (NOT TARGET volk)
	add_library(volk ALIAS armsx3_lsfg_volk)
endif()

add_subdirectory("${LSFG_ROOT}/framegen" "${CMAKE_CURRENT_BINARY_DIR}/framegen" EXCLUDE_FROM_ALL)

# Undo the project-wide -fno-exceptions for framegen and the shim.
#
# The top-level build sets it with add_compile_options, which every later add_subdirectory
# inherits. framegen has dozens of throw sites and they do not warn -- they fail to compile. The
# shim needs exceptions for the opposite reason: it exists to CATCH them so none reach the dlopen
# boundary.
foreach (tgt lsfg-vk-framegen)
	if (TARGET ${tgt})
		target_compile_options(${tgt} PRIVATE -fexceptions)
		set_target_properties(${tgt} PROPERTIES
			POSITION_INDEPENDENT_CODE ON
			CXX_VISIBILITY_PRESET hidden
			VISIBILITY_INLINES_HIDDEN ON)
		# PRIVATE, not PUBLIC: leaking this onto consumers collides with the valueless #define
		# our own Vulkan headers use, in hundreds of RSX translation units.
		target_compile_definitions(${tgt} PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
	endif()
endforeach()

# Shader extraction: DXBC out of the user's own Lossless.dll, translated to SPIR-V.
#
# framegen asks for SPIR-V by name and does not read the DLL itself, so this chain is the caller's
# responsibility. Building upstream's own libraries rather than writing a DXBC translator: dxbc is
# DXVK's, and reimplementing it would be absurd.
#
# Optional. Without these the library still builds and frame generation still reports itself
# available -- it just cannot initialize until shaders exist, which is also what happens when the
# user has not supplied a DLL.
set(LSFG_HAS_EXTRACT OFF)

if (EXISTS "${LSFG_ROOT}/thirdparty/dxbc/CMakeLists.txt" AND
    EXISTS "${LSFG_ROOT}/thirdparty/pe-parse/CMakeLists.txt")

	# pe-parse defaults to a shared library and command-line tools, neither of which belongs in
	# an APK. Forced here because its options are plain option(), so they take whatever is
	# already in the cache unless overridden.
	set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
	set(BUILD_COMMAND_LINE_TOOLS OFF CACHE BOOL "" FORCE)
	set(PEPARSE_ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
	set(PEPARSE_ENABLE_TESTING OFF CACHE BOOL "" FORCE)

	add_subdirectory("${LSFG_ROOT}/thirdparty/dxbc" "${CMAKE_CURRENT_BINARY_DIR}/dxbc" EXCLUDE_FROM_ALL)
	add_subdirectory("${LSFG_ROOT}/thirdparty/pe-parse" "${CMAKE_CURRENT_BINARY_DIR}/pe-parse" EXCLUDE_FROM_ALL)

	foreach (tgt dxbc pe-parse)
		if (TARGET ${tgt})
			# Same -fno-exceptions problem as framegen: both throw, and inheriting the
			# project-wide flag turns that into a compile error rather than a warning.
			target_compile_options(${tgt} PRIVATE -fexceptions)
			set_target_properties(${tgt} PROPERTIES
				POSITION_INDEPENDENT_CODE ON
				CXX_VISIBILITY_PRESET hidden)
			set(LSFG_HAS_EXTRACT ON)
		endif()
	endforeach()
endif()

add_library(armsx3_lsfg SHARED armsx3_lsfg_shim.cpp)

target_include_directories(armsx3_lsfg PRIVATE
	"${CMAKE_CURRENT_SOURCE_DIR}"
	"${LSFG_ROOT}/framegen/public")

target_compile_options(armsx3_lsfg PRIVATE -fexceptions)
target_compile_definitions(armsx3_lsfg PRIVATE VK_USE_PLATFORM_ANDROID_KHR)

set_target_properties(armsx3_lsfg PROPERTIES
	CXX_STANDARD 20
	CXX_STANDARD_REQUIRED ON
	CXX_VISIBILITY_PRESET hidden
	VISIBILITY_INLINES_HIDDEN ON
	OUTPUT_NAME "armsx3_lsfg")

target_link_libraries(armsx3_lsfg PRIVATE lsfg-vk-framegen armsx3_lsfg_volk android log)

if (LSFG_HAS_EXTRACT)
	target_sources(armsx3_lsfg PRIVATE
		"${LSFG_ROOT}/src/extract/trans.cpp"
		"${LSFG_ROOT}/src/extract/extract.cpp")
	target_include_directories(armsx3_lsfg PRIVATE "${LSFG_ROOT}/include")
	target_link_libraries(armsx3_lsfg PRIVATE dxbc pe-parse)
	target_compile_definitions(armsx3_lsfg PRIVATE ARMSX3_LSFG_HAVE_EXTRACT=1)
	message(STATUS "LSFG: shader extraction enabled (dxbc + pe-parse)")
else()
	message(STATUS "LSFG: shader extraction NOT available, frame generation cannot initialize")
endif()

# Keep the exported surface to the shim alone.
#
# The version script is what makes the isolation real rather than aspirational: without it,
# framegen's and volk's symbols are still dynamic and the loader can bind our renderer's vk* to
# them. Verify with:
#   llvm-nm --defined-only --extern-only libarmsx3_lsfg.so
# Only armsx3_lsfg_* may appear. Any vk* or LSFG_3_1 symbol means this stopped working.
target_link_options(armsx3_lsfg PRIVATE
	"-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/armsx3_lsfg.map"
	"-Wl,--no-undefined")
