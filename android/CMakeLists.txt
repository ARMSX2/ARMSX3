# ARMSX3: the Android shared library, built on UPSTREAM RPCS3.
#
# NOTE ON LAYOUT (this is the important part):
# Other Android ports make android/ the top-level CMake project and do
# `add_subdirectory(..)` to pull the emulator in. That does not work against
# upstream, because upstream assumes CMAKE_SOURCE_DIR == repo root in at least
# four places -- buildfiles/cmake/FindWolfSSL.cmake, buildfiles/cmake/FindZLIB.cmake,
# 3rdparty/protobuf/CMakeLists.txt and 3rdparty/llvm/CMakeLists.txt all build
# paths as ${CMAKE_SOURCE_DIR}/3rdparty/... . Configuring from android/ makes
# every one of those resolve to android/3rdparty/... and fail.
#
# So here the repo ROOT stays the top-level project and this is an ordinary
# subdirectory, added from the root CMakeLists behind `if(ANDROID)`. Build
# option overrides are passed on the cmake command line (-D...) rather than
# set() here, since they must be visible before add_subdirectory(3rdparty).
#
# Upstream's core target is `rpcs3_emu`. Upstream's rpcs3/CMakeLists.txt already
# guards Qt / rpcs3qt / rpcs3_lib / the rpcs3 executable behind `if (NOT ANDROID)`
# while calling `add_subdirectory(Emu)` unconditionally, so a core-only Android
# configuration is something upstream half-supports already.

# rpcs3/Input/ is not a library upstream -- its sources are compiled straight
# into the `rpcs3` executable, which lives inside `if (NOT ANDROID)`. So on
# Android nothing builds them, yet rpcs3_emu references pad_thread and
# ps_move_tracker (cellPad's LDD pad API, cellGem's tracker), and the link fails
# with undefined symbols. Build the platform-safe subset here.
#
# Deliberately EXCLUDED, and why:
#   evdev_/xinput_/mm_/sdl_*      - Linux/Windows/SDL backends, all disabled
#   basic_keyboard_/basic_mouse_/
#   keyboard_pad_/raw_mouse_/
#   gui_pad_thread/mouse_gyro_    - Qt-dependent (rpcs3qt is not built here)
#   camera_video_sink             - Qt multimedia
set(ARMSX3_INPUT_SOURCES
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/pad_thread.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/product_info.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/hid_pad_handler.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ds3_pad_handler.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ds4_pad_handler.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/dualsense_pad_handler.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/skateboard_pad_handler.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ps_move_handler.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ps_move_config.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ps_move_calibration.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ps_move_tracker.cpp
    # pad_thread references mouse_gyro_handler directly, so it is not optional
    # despite the "mouse" name.
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/mouse_gyro_handler.cpp
    # Ours: on-screen touch controls.
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/virtual_pad_handler.cpp
    # Ours: cellKb fed from the Android IME / a physical keyboard. The desktop
    # handler is a QObject and cannot be built here.
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/virtual_keyboard_handler.cpp
)

add_library(armsx3-core SHARED
    src/armsx3-core.cpp
    # Reaches a user-picked folder through SAF on builds with no filesystem
    # permission, as an fs::device_base. See src/saf_device.h.
    src/saf_device.cpp
    # Remote play capture: hands frames and audio from RPCS3's recording hook to the app.
    src/remote_play.cpp
    # XMB mode: the boot sound and a game's SND0 and ICON1, decoded with the core's FFmpeg.
    src/xmb-media.cpp
    # XMB mode: videos Android cannot play (AVI, WMV, MPEG), FFmpeg to the surface and AAudio.
    src/xmb-player.cpp
    # Remote play online hosting: a port opened on the router (UPnP) for the session.
    src/remote_play_upnp.cpp
    # Carried from an earlier Android fork; no upstream equivalent.
    ${CMAKE_SOURCE_DIR}/rpcs3/dev/iso.cpp
    ${ARMSX3_INPUT_SOURCES}
    # rpcs3::get_version() and friends. Like Input/, this is compiled into the
    # `rpcs3` executable upstream, not into rpcs3_emu, so Android never gets it.
    ${CMAKE_SOURCE_DIR}/rpcs3/rpcs3_version.cpp
)

# Ship as libarmsx3-core.so, which is the name the app calls
# System.loadLibrary("armsx3-core") for. Setting OUTPUT_NAME (rather than
# renaming the file after the fact) also fixes the SONAME, so the file name and
# the name the dynamic linker registers it under agree -- a mismatch there works
# by luck for a plain dlopen and stops working the moment anything resolves it
# by soname.
set_target_properties(armsx3-core PROPERTIES OUTPUT_NAME armsx3-core)

target_compile_features(armsx3-core PRIVATE cxx_std_23)

# The librashader upscaler lives in rpcs3/Emu/RSX/VK/, i.e. it compiles into
# rpcs3_emu -- not into this target -- so the header search path has to be added
# there. This directory is processed after add_subdirectory(rpcs3), so the target
# already exists. librashader_ld.h is a header-only permissive loader that
# dlopen()s librashader.so at runtime; nothing MPL-licensed is linked in. See the
# licensing note at the top of upscalers/librashader_pass.h.
target_include_directories(rpcs3_emu PRIVATE
    ${CMAKE_SOURCE_DIR}/3rdparty/librashader/include
)

# The SPU object cache key's code identity (spu_codegen_identity.py, which says what it covers).
# Worked out on every build before anything in rpcs3_emu compiles, so no core can be built with an
# identity its own sources did not produce, whatever builds it. Each build directory has its own,
# since each variant compiles with its own flags; only SPUCommonRecompiler.cpp includes it, and
# the header is rewritten only when the identity changes.
find_package(Python3 REQUIRED COMPONENTS Interpreter)
set(ARMSX3_SPU_IDENTITY_DIR "${CMAKE_CURRENT_BINARY_DIR}/spu_codegen_identity")
add_custom_target(armsx3_spu_codegen_identity
    COMMAND "${Python3_EXECUTABLE}" -I "${CMAKE_CURRENT_SOURCE_DIR}/spu_codegen_identity.py"
        "${CMAKE_SOURCE_DIR}" "${CMAKE_BINARY_DIR}" "${ARMSX3_SPU_IDENTITY_DIR}/spu_codegen_identity_android.h"
    BYPRODUCTS "${ARMSX3_SPU_IDENTITY_DIR}/spu_codegen_identity_android.h"
    VERBATIM)
add_dependencies(rpcs3_emu armsx3_spu_codegen_identity)
# A per-source include directory, so no other translation unit's command line changes.
set_property(SOURCE "${CMAKE_SOURCE_DIR}/rpcs3/Emu/Cell/SPUCommonRecompiler.cpp"
    TARGET_DIRECTORY rpcs3_emu APPEND PROPERTY INCLUDE_DIRECTORIES "${ARMSX3_SPU_IDENTITY_DIR}")
# ---------------------------------------------------------------------------
# ZeroFG: frame generation on its own Vulkan 1.3 device (rpcs3/Emu/RSX/VK/ZeroFG/)
#
# The engine is the ZeroFG library (https://github.com/hy300leosquizz-ctrl/ZeroFG,
# Apache-2.0 OR GPL-2.0-or-later, taken here under the GPL). ZEROFG_SOURCE_DIR points at a local
# checkout; without one the pinned commit is fetched. The presenter side is the same repository's
# portable host kit (host/portable), which builds the ZeroFG Independent Presenter unchanged and
# needs a {fmt} target for its logging. RPCS3 has its own fmt namespace (Utilities/StrFmt.h), so
# {fmt} is header-only and only the kit's sources and the adapter see it, never the rest of the
# core. The engine's shaders ship compiled: nothing beyond the NDK is needed.
# ---------------------------------------------------------------------------
option(ARMSX3_ENABLE_ZEROFG "Build ZeroFG frame generation" ON)
# The adapter is always built: without ARMSX3_HAS_ZEROFG every entry point is a no-op, so the
# renderer calls it with no #ifdef of its own. It needs nothing of stdafx.h.
set(_zerofg_adapter "${CMAKE_SOURCE_DIR}/rpcs3/Emu/RSX/VK/ZeroFG/VKZeroFG.cpp")
target_sources(rpcs3_emu PRIVATE "${_zerofg_adapter}"
    "${CMAKE_SOURCE_DIR}/rpcs3/Emu/RSX/VK/ZeroFG/VKZeroFGLog.cpp")
set_source_files_properties("${_zerofg_adapter}"
    DIRECTORY "${CMAKE_SOURCE_DIR}/rpcs3/Emu"
    PROPERTIES SKIP_PRECOMPILE_HEADERS ON)
if(ARMSX3_ENABLE_ZEROFG AND CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    include(FetchContent)

    # The NDK's Vulkan headers, which the core builds with too (VulkanAPI.h), staged alone: an
    # include path on the whole sysroot would put libc's headers ahead of libc++'s wrappers.
    set(_zerofg_ndk_include "${CMAKE_SYSROOT}/usr/include")
    if(NOT EXISTS "${_zerofg_ndk_include}/vulkan/vulkan_core.h")
        message(FATAL_ERROR "ZeroFG: no Vulkan headers in the NDK sysroot (${_zerofg_ndk_include})")
    endif()
    set(_zerofg_vulkan_headers "${CMAKE_BINARY_DIR}/zerofg_vulkan_headers")
    file(COPY "${_zerofg_ndk_include}/vulkan" "${_zerofg_ndk_include}/vk_video"
        DESTINATION "${_zerofg_vulkan_headers}")
    set(ZEROFG_VULKAN_HEADERS_DIR "${_zerofg_vulkan_headers}" CACHE PATH "" FORCE)
    set(ZEROFG_HOST_VULKAN_HEADERS_DIR "${_zerofg_vulkan_headers}" CACHE PATH "" FORCE)

    # The SPIR-V the release ships, not whatever glslang this machine happens to have.
    set(ZEROFG_PREBUILT_SHADERS ON CACHE STRING "" FORCE)
    set(ZEROFG_SOURCE_DIR "" CACHE PATH "Local ZeroFG checkout (empty: fetch the pinned commit)")
    if(ZEROFG_SOURCE_DIR)
        add_subdirectory("${ZEROFG_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/zerofg")
        set(_zerofg_root "${ZEROFG_SOURCE_DIR}")
    else()
        FetchContent_Declare(zerofg
            GIT_REPOSITORY https://github.com/hy300leosquizz-ctrl/ZeroFG.git
            # ZeroFG 1.2.0 (RZ+, x2 to x4), dual-licensed Apache-2.0 OR GPL-2.0-or-later.
            GIT_TAG v1.2.0)
        FetchContent_MakeAvailable(zerofg)
        set(_zerofg_root "${zerofg_SOURCE_DIR}")
    endif()

    FetchContent_Declare(zerofg_fmt
        GIT_REPOSITORY https://github.com/fmtlib/fmt.git
        GIT_TAG 11.1.4
        GIT_SHALLOW TRUE)
    set(FMT_INSTALL OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(zerofg_fmt)
    set(ZEROFG_HOST_FMT_TARGET "fmt::fmt-header-only" CACHE STRING "" FORCE)
    add_subdirectory("${_zerofg_root}/host/portable" "${CMAKE_BINARY_DIR}/zerofg_host")

    # Only the adapter sees the kit's and the engine's headers, and only it is told ZeroFG is in.
    # Link only, so none of their usage requirements reach the rest of rpcs3_emu (whose objects
    # then keep their command lines and are not rebuilt). rpcs3_emu is static: they land after it
    # on the core's link line.
    set_source_files_properties("${_zerofg_adapter}"
        DIRECTORY "${CMAKE_SOURCE_DIR}/rpcs3/Emu"
        PROPERTIES
            INCLUDE_DIRECTORIES "$<TARGET_PROPERTY:zerofg_host,INTERFACE_INCLUDE_DIRECTORIES>;$<TARGET_PROPERTY:zerofg,INTERFACE_INCLUDE_DIRECTORIES>;$<TARGET_PROPERTY:fmt-header-only,INTERFACE_INCLUDE_DIRECTORIES>"
            COMPILE_DEFINITIONS "ARMSX3_HAS_ZEROFG=1;FMT_HEADER_ONLY=1"
            # Written to ZeroFG's and Xenia's conventions, and through the Vulkan headers' own
            # macros (C casts) included here as ordinary headers: RPCS3's -Werror set stays
            # warnings for this one file.
            COMPILE_OPTIONS "-Wno-error;-Wno-old-style-cast")
    target_link_libraries(rpcs3_emu PRIVATE "$<LINK_ONLY:zerofg>" "$<LINK_ONLY:zerofg_host>")
endif()

target_include_directories(armsx3-core PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/rpcs3
    ${CMAKE_SOURCE_DIR}/3rdparty/librashader/include
    # armsx3-core.cpp pulls in Input/hid_pad_handler.h (DS3/DS4/DualSense
    # over USB) and hidapi_libusb.h/libusb.h directly. rpcs3_emu links these
    # PRIVATE, so their include dirs do not propagate to us.
    ${CMAKE_SOURCE_DIR}/3rdparty/hidapi/hidapi/hidapi
    ${CMAKE_SOURCE_DIR}/3rdparty/hidapi/hidapi/libusb
    ${CMAKE_SOURCE_DIR}/3rdparty/libusb/libusb/libusb
    # Utilities/bin_patch.h (patch_engine) includes util/yaml.hpp, which includes
    # yaml-cpp. rpcs3_emu links yaml-cpp PRIVATE, so it does not propagate here.
    ${CMAKE_SOURCE_DIR}/3rdparty/yaml-cpp/yaml-cpp/include
)

target_link_libraries(armsx3-core
    rpcs3_emu
    # ps_move_tracker/ps_move_calibration use the Fusion AHRS solver. rpcs3_lib
    # links this upstream; we build those Input sources ourselves, so we need it.
    3rdparty::fusion
    # The HID pad handlers (DS3/DS4/DualSense/skateboard/PS Move) are ours to
    # build here, so hidapi + libusb are ours to link too.
    3rdparty::hidapi
    3rdparty::libusb
    android
    log
    # xmb-player.cpp's sound.
    aaudio
)

# ---------------------------------------------------------------------------
# Profile-guided optimisation
# ---------------------------------------------------------------------------
#
# Scoped to rpcs3_emu and armsx3-core deliberately, NOT to the whole build.
# 3rdparty is mostly LLVM, which is only hot while compiling guest code; adding
# instrumentation to it would multiply an already 1.2GB unstripped artifact for
# a payoff in compile speed rather than in frame time. What we measured as hot
# lives in these two: a simpleperf profile of the RSX thread during gameplay put
# 24.9% in vk::query_pool_manager::get_query_result, 9.4% in
# FIFO_control::fetch_u32_refill and the rest across the VK backend and the FIFO
# dispatch, all of which are rpcs3_emu.
#
#   -DARMSX3_PGO=generate                       instrumented build, writes .profraw
#   -DARMSX3_PGO=use -DARMSX3_PGO_PROFILE=<abs> optimised build against a profile
#
# Configure these into a SEPARATE build directory (BUILD_DIR=... android/configure.sh)
# so the instrumented objects never mix with the normal ones.
set(ARMSX3_PGO "off" CACHE STRING "Profile-guided optimisation: off, generate, or use")
set(ARMSX3_PGO_PROFILE "" CACHE FILEPATH "Merged .profdata, required when ARMSX3_PGO=use")

if(ARMSX3_PGO STREQUAL "generate")
    # -fprofile-generate on BOTH compile and link: the link is what pulls in the
    # profile runtime that writes the file.
    foreach(pgo_target rpcs3_emu armsx3-core)
        target_compile_options(${pgo_target} PRIVATE -fprofile-generate)
        target_link_options(${pgo_target} PRIVATE -fprofile-generate)
    endforeach()

    # Tells src/armsx3-core.cpp to name the profile somewhere the app can
    # actually write, and to flush it when the app goes to the background.
    # Without that the runtime writes to the process CWD, which on Android is /
    # and is not writable, so a whole play session produces nothing.
    target_compile_definitions(armsx3-core PRIVATE ARMSX3_PGO_GENERATE=1)

    message(STATUS "ARMSX3: PGO instrumentation ON for rpcs3_emu and armsx3-core")
elseif(ARMSX3_PGO STREQUAL "use")
    if(NOT ARMSX3_PGO_PROFILE)
        message(FATAL_ERROR "ARMSX3_PGO=use requires -DARMSX3_PGO_PROFILE=<absolute .profdata>")
    endif()
    if(NOT EXISTS "${ARMSX3_PGO_PROFILE}")
        message(FATAL_ERROR "ARMSX3_PGO_PROFILE does not exist: ${ARMSX3_PGO_PROFILE}")
    endif()

    foreach(pgo_target rpcs3_emu armsx3-core)
        # Drift is tolerated rather than fatal: the profile ages the moment the
        # code changes, and a warning per stale function would otherwise turn
        # into thousands of errors under -Werror. Stale enough to matter shows up
        # as a regression, which is why the profile gets regenerated rather than
        # carried forward. A stale profile has actively pessimised this codebase's
        # sibling project before, so treat age as a real risk, not a formality.
        target_compile_options(${pgo_target} PRIVATE
            -fprofile-use=${ARMSX3_PGO_PROFILE}
            -Wno-error=profile-instr-out-of-date
            -Wno-error=profile-instr-unprofiled
            -Wno-profile-instr-out-of-date
            -Wno-profile-instr-unprofiled)
    endforeach()

    message(STATUS "ARMSX3: PGO optimising against ${ARMSX3_PGO_PROFILE}")
endif()
