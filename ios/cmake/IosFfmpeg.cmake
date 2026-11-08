# SPDX-License-Identifier: GPL-2.0-only
# FFmpeg for arm64-ios: fill FindFFMPEG's two cache variables with the cross-built tree (configure.sh passes USE_SYSTEM_FFMPEG=ON); include before add_subdirectory(3rdparty).

# Derived from the source tree so a checkout anywhere resolves; not FORCEd, since a caller may point it elsewhere.
set(ARMSX3_IOS_FFMPEG_PREFIX "${CMAKE_CURRENT_SOURCE_DIR}/ios/build-deps/ffmpeg-ios"
    CACHE PATH "ARMSX3 iOS: the install prefix ios/scripts/build-ffmpeg-ios.sh writes.")

set(ARMSX3_IOS_FFMPEG_INCLUDE_DIR "${ARMSX3_IOS_FFMPEG_PREFIX}/include")

# Static-link dependency order.
set(ARMSX3_IOS_FFMPEG_NAMES avformat avcodec avutil swscale swresample)

set(ARMSX3_IOS_FFMPEG_LIBS "")
set(ARMSX3_IOS_FFMPEG_MISSING "")

foreach(name IN LISTS ARMSX3_IOS_FFMPEG_NAMES)
    set(archive "${ARMSX3_IOS_FFMPEG_PREFIX}/lib/lib${name}.a")
    if(NOT EXISTS "${archive}")
        list(APPEND ARMSX3_IOS_FFMPEG_MISSING "${archive}")
    endif()
    list(APPEND ARMSX3_IOS_FFMPEG_LIBS "${archive}")
endforeach()

if(ARMSX3_IOS_FFMPEG_MISSING)
    string(REPLACE ";" "\n  " ARMSX3_IOS_FFMPEG_MISSING_TEXT "${ARMSX3_IOS_FFMPEG_MISSING}")
    message(FATAL_ERROR
        "ARMSX3: the arm64-ios FFmpeg archives are missing:\n"
        "  ${ARMSX3_IOS_FFMPEG_MISSING_TEXT}\n"
        "Run ios/scripts/build-ffmpeg-ios.sh to build them from the SHA-256-pinned source. "
        "The configure is stopped here deliberately: without these variables primed, "
        "buildfiles/cmake/FindFFMPEG.cmake searches /usr/lib, /usr/local/lib, /opt/local/lib "
        "and /sw/lib, and can resolve a HOST macOS FFmpeg into an arm64-ios link.")
endif()

# The headers come from FFmpeg's own install step; the source tree and upstream's builtin branch lay them out differently.
if(NOT EXISTS "${ARMSX3_IOS_FFMPEG_INCLUDE_DIR}/libavcodec/avcodec.h")
    message(FATAL_ERROR
        "ARMSX3: the arm64-ios FFmpeg headers are missing:\n"
        "  ${ARMSX3_IOS_FFMPEG_INCLUDE_DIR}/libavcodec/avcodec.h\n"
        "This must be the directory FFmpeg's own install step populates, not a source path. "
        "Run ios/scripts/build-ffmpeg-ios.sh.")
endif()

# FORCE, because a plain set() would be ignored on a reconfigure and revert to a host answer.
set(FFMPEG_INCLUDE_DIR "${ARMSX3_IOS_FFMPEG_INCLUDE_DIR}" CACHE PATH
    "ARMSX3 iOS: FFmpeg headers from the cross-built arm64-ios tree." FORCE)
set(FFMPEG_LIBRARIES "${ARMSX3_IOS_FFMPEG_LIBS}" CACHE STRING
    "ARMSX3 iOS: the five cross-built arm64-ios FFmpeg archives, in link order." FORCE)

message(STATUS "ARMSX3: FFmpeg -> cross-built arm64-ios tree ${ARMSX3_IOS_FFMPEG_PREFIX}")
