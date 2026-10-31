# ARMSX3: ffmpeg for Android.
#
# Upstream 3rdparty/CMakeLists.txt wraps its whole FFMPEG block in
# `if(NOT ANDROID)` -- it deliberately does not define 3rdparty_ffmpeg on
# Android and expects the Android build to supply it. But line ~378 then does
# `add_library(3rdparty::ffmpeg ALIAS 3rdparty_ffmpeg)` UNGUARDED, so the target
# must already exist by the time 3rdparty/ is processed. Hence this file is
# include()d from the root CMakeLists *before* add_subdirectory(3rdparty).
#
# Upstream's own 3rdparty/ffmpeg/CMakeLists.txt cannot serve Android: it maps
# OS -> prebuilt via WIN32/APPLE/UNIX/MINGW, and Android matches UNIX, so it
# would fetch a *Linux* arm64 build and fail to link against bionic.
#
# So android/build-ffmpeg.sh builds the version upstream ships, with upstream's
# components and its assembly on. This used to download RPCS3-Android's 5.1
# prebuilt, which had every component ffmpeg has and none of its NEON code.
#
# One build serves every variant (it targets the lowest API level any of them
# runs on, and picks its CPU features at run time), so it lives beside the
# variant build directories rather than in each of them, and builds once.

set(FFMPEG_VERSION 8.1.1)

if(NOT CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64")
    message(FATAL_ERROR "ARMSX3: android/build-ffmpeg.sh only builds ffmpeg for arm64")
endif()

# Keyed on the build script, so changing how ffmpeg is built builds it again instead of reusing
# what the old recipe produced.
file(SHA1 "${CMAKE_SOURCE_DIR}/android/build-ffmpeg.sh" FFMPEG_RECIPE_HASH)
string(SUBSTRING "${FFMPEG_RECIPE_HASH}" 0 10 FFMPEG_RECIPE_HASH)
set(FFMPEG_PATH "${CMAKE_SOURCE_DIR}/build-ffmpeg/${FFMPEG_VERSION}-arm64-v8a-${FFMPEG_RECIPE_HASH}")

# Build at configure time: the imported-library IMPORTED_LOCATION paths below
# must exist before any target links them.
if(NOT EXISTS "${FFMPEG_PATH}/lib/libavcodec.a")
    message(STATUS "ARMSX3: building ffmpeg ${FFMPEG_VERSION} for arm64-v8a")
    execute_process(
        COMMAND "${CMAKE_SOURCE_DIR}/android/build-ffmpeg.sh" "${ANDROID_NDK}" "${FFMPEG_PATH}"
        RESULT_VARIABLE FFMPEG_BUILD_RESULT
    )
    if(NOT FFMPEG_BUILD_RESULT EQUAL 0)
        message(FATAL_ERROR "ARMSX3: building ffmpeg failed (android/build-ffmpeg.sh)")
    endif()
endif()

function(armsx3_import_ffmpeg_library name)
    add_library(ffmpeg::${name} STATIC IMPORTED GLOBAL)
    set_property(TARGET ffmpeg::${name} PROPERTY
        IMPORTED_LOCATION "${FFMPEG_PATH}/lib/lib${name}.a")
    set_property(TARGET ffmpeg::${name} PROPERTY
        INTERFACE_INCLUDE_DIRECTORIES "${FFMPEG_PATH}/include")
endfunction()

armsx3_import_ffmpeg_library(avcodec)
armsx3_import_ffmpeg_library(avformat)
armsx3_import_ffmpeg_library(avutil)
armsx3_import_ffmpeg_library(swscale)
armsx3_import_ffmpeg_library(swresample)

# The name upstream's `3rdparty::ffmpeg` alias expects.
add_library(3rdparty_ffmpeg INTERFACE)
target_link_libraries(3rdparty_ffmpeg INTERFACE
    ffmpeg::avformat
    ffmpeg::avcodec
    ffmpeg::avutil
    ffmpeg::swscale
    ffmpeg::swresample
)
