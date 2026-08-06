# ARMSX3: prebuilt ffmpeg for Android.
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
# Prebuilts come from RPCS3-Android/ffmpeg-android (same source RPCSX uses).

set(FFMPEG_VERSION 5.1)

if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64")
    set(RPCS3_DOWNLOAD_ARCH "arm64-v8a")
else()
    set(RPCS3_DOWNLOAD_ARCH "x86-64")
endif()

if(NOT EXISTS ${CMAKE_BINARY_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.gz)
    message(STATUS "ARMSX3: downloading ffmpeg-${FFMPEG_VERSION} for ${RPCS3_DOWNLOAD_ARCH}")
    file(DOWNLOAD
        https://github.com/RPCS3-Android/ffmpeg-android/releases/download/${FFMPEG_VERSION}/ffmpeg-${RPCS3_DOWNLOAD_ARCH}-Android.tar.gz
        ${CMAKE_BINARY_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.gz
        SHOW_PROGRESS
        STATUS FFMPEG_DOWNLOAD_STATUS
    )
    list(GET FFMPEG_DOWNLOAD_STATUS 0 FFMPEG_STATUS_CODE)
    if(NOT FFMPEG_STATUS_CODE EQUAL 0)
        # CMake leaves a 0-byte file behind on failure, which would poison the
        # `if(NOT EXISTS ...)` guard on the next configure.
        file(REMOVE ${CMAKE_BINARY_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.gz)
        message(FATAL_ERROR "ARMSX3: failed to download Android ffmpeg prebuilts")
    endif()
endif()

set(FFMPEG_PATH "${CMAKE_BINARY_DIR}/ffmpeg-${FFMPEG_VERSION}")

# Unpack eagerly at configure time: the imported-library IMPORTED_LOCATION
# paths below must exist before any target links them.
if(NOT EXISTS "${FFMPEG_PATH}/libavcodec/libavcodec.a")
    file(MAKE_DIRECTORY ${FFMPEG_PATH})
    execute_process(
        COMMAND ${CMAKE_COMMAND} -E tar xzf ${CMAKE_BINARY_DIR}/ffmpeg-${FFMPEG_VERSION}.tar.gz
        WORKING_DIRECTORY ${FFMPEG_PATH}
        RESULT_VARIABLE FFMPEG_UNPACK_RESULT
    )
    if(NOT FFMPEG_UNPACK_RESULT EQUAL 0)
        message(FATAL_ERROR "ARMSX3: failed to unpack Android ffmpeg prebuilts")
    endif()
endif()

function(armsx3_import_ffmpeg_library name)
    add_library(ffmpeg::${name} STATIC IMPORTED GLOBAL)
    set_property(TARGET ffmpeg::${name} PROPERTY
        IMPORTED_LOCATION "${FFMPEG_PATH}/lib${name}/lib${name}.a")
    set_property(TARGET ffmpeg::${name} PROPERTY
        INTERFACE_INCLUDE_DIRECTORIES "${FFMPEG_PATH}" "${FFMPEG_PATH}/src")
endfunction()

armsx3_import_ffmpeg_library(avcodec)
armsx3_import_ffmpeg_library(avformat)
armsx3_import_ffmpeg_library(avfilter)
armsx3_import_ffmpeg_library(avdevice)
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
