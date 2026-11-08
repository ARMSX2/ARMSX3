# SPDX-License-Identifier: GPL-2.0-only
# Added from the root CMakeLists behind if(IOS): upstream builds paths from CMAKE_SOURCE_DIR, and options must be -D flags from configure.sh.

# rpcs3_emu needs pad_thread, so build the Input subset iOS can: no HID handlers (no IOKit HID), no Qt handlers, no Linux, Windows or SDL backends.
set(ARMSX3_IOS_INPUT_SOURCES
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/pad_thread.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/product_info.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ps_move_config.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ps_move_tracker.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/mouse_gyro_handler.cpp
    # Touch-screen pad handler.
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/virtual_pad_handler.cpp
    # The core's own key-map handler.
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/virtual_keyboard_handler.cpp
    # The camera frame converter, which is not Qt-bound.
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/camera_video_sink.cpp
    # rpcs3::get_version() is compiled into the executable upstream, not into rpcs3_emu.
    ${CMAKE_SOURCE_DIR}/rpcs3/rpcs3_version.cpp
)

# ps_move_calibration.cpp stays out: its header chain reaches hidapi.h, which iOS cannot build.

target_sources(rpcs3_emu PRIVATE ${ARMSX3_IOS_INPUT_SOURCES})

# rpcs3/dev/iso.cpp stays out until a link failure asks for it.

target_link_libraries(rpcs3_emu PRIVATE
    # ps_move_tracker needs the Fusion AHRS solver, which rpcs3_lib links upstream.
    3rdparty::fusion
)

# Utilities/JIT.cpp, vm_native.cpp and the RSX surface include headers from ios/core, so the path goes on rpcs3_emu.
target_include_directories(rpcs3_emu PRIVATE
    ${CMAKE_SOURCE_DIR}/ios/core
)

# The stand-in <libusb.h> lets upstream's USB sources compile; PUBLIC because armsx3_bridge includes usb_device.h too.
target_include_directories(rpcs3_emu PUBLIC
    ${CMAKE_SOURCE_DIR}/ios/core/libusb-standin
)

# The loader header comes from the checkout build-librashader-ios.sh builds the dylib from, so the two cannot drift.
set(ARMSX3_IOS_LIBRASHADER_INCLUDE ${CMAKE_SOURCE_DIR}/ios/build-deps/librashader-src/include)
if(NOT EXISTS "${ARMSX3_IOS_LIBRASHADER_INCLUDE}/librashader_ld.h")
    message(FATAL_ERROR
        "ARMSX3 iOS: ${ARMSX3_IOS_LIBRASHADER_INCLUDE}/librashader_ld.h is absent. The librashader "
        "checkout is built out of band: run ios/scripts/build-librashader-ios.sh, then configure again.")
endif()
target_include_directories(rpcs3_emu PRIVATE
    ${ARMSX3_IOS_LIBRASHADER_INCLUDE}
)


# FrameGen is Android-only and its licence is unsettled for GPL-2.0-only, so it stays out of the iOS core.
set(ARMSX3_IOS_FRAMEGEN_EXCLUDED_SOURCES
    RSX/VK/FrameGen/FrameGen.cpp
    RSX/VK/FrameGen/FrameGenPacer.cpp
    RSX/VK/FrameGen/LosslessDll.cpp
    RSX/VK/FrameGen/LsfgAlpha.cpp
    RSX/VK/FrameGen/LsfgBeta.cpp
    RSX/VK/FrameGen/LsfgChain.cpp
    RSX/VK/FrameGen/LsfgCommon.cpp
    RSX/VK/FrameGen/LsfgDelta.cpp
    RSX/VK/FrameGen/LsfgGamma.cpp
    RSX/VK/FrameGen/LsfgGenerate.cpp
    RSX/VK/FrameGen/LsfgMipmaps.cpp
    RSX/VK/FrameGen/LsfgShaders.cpp
    RSX/VK/FrameGen/LsfgTranslate.cpp
    RSX/VK/FrameGen/LsfgUtil.cpp
    RSX/VK/FrameGen/LsfgVkCompat.cpp
)

# Remove them by rewriting SOURCES instead of editing rpcs3/Emu/CMakeLists.txt.
get_target_property(ARMSX3_IOS_EMU_SOURCES rpcs3_emu SOURCES)

# A name that matches nothing stops the configure, so an upstream rename cannot leave a dead entry.
foreach(excluded IN LISTS ARMSX3_IOS_FRAMEGEN_EXCLUDED_SOURCES)
    set(matched "")
    foreach(candidate IN LISTS ARMSX3_IOS_EMU_SOURCES)
        # Suffix match on a path boundary, because sources may be stored relative or absolute.
        if(candidate STREQUAL "${excluded}" OR candidate MATCHES "(^|/)${excluded}$")
            list(APPEND matched "${candidate}")
        endif()
    endforeach()

    if(NOT matched)
        message(FATAL_ERROR
            "ARMSX3 iOS: the USB or FrameGen exclusion names ${excluded}, which is not among "
            "rpcs3_emu's sources. Either upstream moved or renamed it, or it is already "
            "gone. Re-derive the exclusion list from a build of the iOS configuration "
            "rather than deleting this entry -- see the note above this block.")
    endif()

    list(REMOVE_ITEM ARMSX3_IOS_EMU_SOURCES ${matched})
endforeach()

set_property(TARGET rpcs3_emu PROPERTY SOURCES ${ARMSX3_IOS_EMU_SOURCES})

# asmjit includes OSCacheControl.h only on macOS but calls sys_icache_invalidate on all Apple; force the include rather than edit the submodule.
if(TARGET asmjit)
    target_compile_options(asmjit PRIVATE -include libkern/OSCacheControl.h)
else()
    message(FATAL_ERROR
        "ARMSX3 iOS: target 'asmjit' does not exist, so the sys_icache_invalidate "
        "include fix could not be applied. asmjit is an unconditional dependency of "
        "rpcs3_emu; if it has been made optional upstream, re-check whether "
        "3rdparty/asmjit/.../core/virtmem.cpp still needs this.")
endif()

# cubeb_osx_run_loop.cpp needs CoreAudio/AudioHardware.h, absent on iOS, and its only caller is under !TARGET_OS_IPHONE.
if(TARGET cubeb)
    get_target_property(ARMSX3_IOS_CUBEB_SOURCES cubeb SOURCES)
    set(ARMSX3_IOS_CUBEB_MATCHED "")
    foreach(candidate IN LISTS ARMSX3_IOS_CUBEB_SOURCES)
        # Suffix match on a path boundary, as above.
        if(candidate MATCHES "(^|/)src/cubeb_osx_run_loop\\.cpp$")
            list(APPEND ARMSX3_IOS_CUBEB_MATCHED "${candidate}")
        endif()
    endforeach()

    if(NOT ARMSX3_IOS_CUBEB_MATCHED)
        message(FATAL_ERROR
            "ARMSX3 iOS: src/cubeb_osx_run_loop.cpp is not among target 'cubeb''s "
            "sources, so the exclusion did nothing. Either the cubeb submodule stopped "
            "adding it -- in which case delete this block -- or USE_AUDIOUNIT is off "
            "again, which would mean cubeb has no backend at all on iOS. Re-read "
            "ios/configure.sh's USE_AUDIOUNIT setting before deleting this entry.")
    endif()

    list(REMOVE_ITEM ARMSX3_IOS_CUBEB_SOURCES ${ARMSX3_IOS_CUBEB_MATCHED})
    set_property(TARGET cubeb PROPERTY SOURCES ${ARMSX3_IOS_CUBEB_SOURCES})

    # Upstream's AudioUnit backend does not build for iOS, so compile a patched copy instead.
    set(ARMSX3_IOS_CUBEB_AU_SRC "${CMAKE_SOURCE_DIR}/3rdparty/cubeb/cubeb/src/cubeb_audiounit.cpp")
    set(ARMSX3_IOS_CUBEB_AU_PATCH "${CMAKE_SOURCE_DIR}/ios/core/cubeb-audiounit.patch")
    set(ARMSX3_IOS_CUBEB_AU_DIR "${CMAKE_BINARY_DIR}/cubeb-ios")
    file(REMOVE_RECURSE "${ARMSX3_IOS_CUBEB_AU_DIR}")
    file(MAKE_DIRECTORY "${ARMSX3_IOS_CUBEB_AU_DIR}/src")
    file(COPY_FILE "${ARMSX3_IOS_CUBEB_AU_SRC}" "${ARMSX3_IOS_CUBEB_AU_DIR}/src/cubeb_audiounit.cpp" RESULT ARMSX3_IOS_CUBEB_AU_COPY)
    if(NOT ARMSX3_IOS_CUBEB_AU_COPY EQUAL 0)
        message(FATAL_ERROR "Could not copy ${ARMSX3_IOS_CUBEB_AU_SRC}: ${ARMSX3_IOS_CUBEB_AU_COPY}")
    endif()
    execute_process(
        COMMAND patch -s -N -F 0 -p1 -d "${ARMSX3_IOS_CUBEB_AU_DIR}" -i "${ARMSX3_IOS_CUBEB_AU_PATCH}"
        RESULT_VARIABLE ARMSX3_IOS_CUBEB_AU_RC)
    if(NOT ARMSX3_IOS_CUBEB_AU_RC EQUAL 0)
        message(FATAL_ERROR
            "ios/core/cubeb-audiounit.patch no longer applies to the pinned cubeb "
            "(patch exit ${ARMSX3_IOS_CUBEB_AU_RC}). Refresh the patch for the new cubeb.")
    endif()
    set_source_files_properties("${ARMSX3_IOS_CUBEB_AU_SRC}" TARGET_DIRECTORY cubeb
        PROPERTIES HEADER_FILE_ONLY ON)
    target_sources(cubeb PRIVATE "${ARMSX3_IOS_CUBEB_AU_DIR}/src/cubeb_audiounit.cpp")
    target_include_directories(cubeb PRIVATE "${CMAKE_SOURCE_DIR}/3rdparty/cubeb/cubeb/src")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
        "${ARMSX3_IOS_CUBEB_AU_SRC}" "${ARMSX3_IOS_CUBEB_AU_PATCH}")
else()
    message(FATAL_ERROR
        "ARMSX3 iOS: target 'cubeb' does not exist. USE_SYSTEM_CUBEB defaults OFF and "
        "3rdparty::cubeb is on rpcs3_emu's link line, so this is not a configuration "
        "this port has; if cubeb has genuinely been made optional upstream, re-check "
        "whether the audio path still exists at all.")
endif()

# OpenAL's aligned operator new replacement binds on iOS and makes LLVM's aligned allocations fail, so do not compile it.
if(NOT TARGET OpenAL)
    message(FATAL_ERROR
        "ARMSX3 iOS: target 'OpenAL' does not exist, so the almalloc.cpp exclusion could "
        "not be applied. Without it every PPU LLVM compile fails with \"Buffer allocation "
        "failed\" (see the note above); check that before deleting this block.")
endif()

get_target_property(ARMSX3_IOS_OPENAL_SOURCES OpenAL SOURCES)
list(FILTER ARMSX3_IOS_OPENAL_SOURCES INCLUDE REGEX "(^|/)common/almalloc\\.cpp$")
if(NOT ARMSX3_IOS_OPENAL_SOURCES)
    message(FATAL_ERROR
        "ARMSX3 iOS: common/almalloc.cpp is not among target 'OpenAL''s sources, so the "
        "exclusion would do nothing. Either the OpenAL-soft submodule renamed the file or "
        "it no longer compiles it. Check the note above before deleting this block.")
endif()

set_source_files_properties(
    "${CMAKE_SOURCE_DIR}/3rdparty/OpenAL/openal-soft/common/almalloc.cpp"
    TARGET_DIRECTORY OpenAL
    PROPERTIES HEADER_FILE_ONLY ON)

# Added last: armsx3_ios_render links rpcs3_emu and 3rdparty::vulkan, which must already exist.
add_subdirectory(core/render)
