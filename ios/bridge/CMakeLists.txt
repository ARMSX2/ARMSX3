# SPDX-License-Identifier: GPL-2.0-only
# The callback plane both frontends link; no framework is linked here, hosts inject theirs through host_services.

# Compiled on iOS and the macOS leg, so the leg's fast build catches a break in any of these first.
add_library(armsx3_bridge STATIC
    callbacks.cpp
    diag_log.cpp
    input_config_engine.cpp
    ios_camera_handler.cpp
    ios_dispatcher.cpp
    ios_video_sink.cpp
    portal_figures.cpp
    rpcn_bridge.cpp
    rpcs3_ios_abi_common.cpp
    rpcs3_ios_abi_diagnostics.cpp
    rpcs3_ios_abi_input.cpp
    rpcs3_ios_abi_install.cpp
    rpcs3_ios_abi_library.cpp
    rpcs3_ios_abi_lifecycle.cpp
    rpcs3_ios_abi_media.cpp
    rpcs3_ios_abi_rpcn.cpp
    rpcs3_ios_abi_saves.cpp
    rpcs3_ios_abi_settings.cpp
    settings_engine.cpp
    stderr_log_listener.cpp
    xmb_media.cpp
)

# These import frameworks the macOS SDK lacks, so they build for iOS only; the app target links the frameworks.
if(IOS)
    target_sources(armsx3_bridge PRIVATE
        ios_capture.mm
        ios_frame.mm
        ios_frame_impl.cpp
        ios_video_writer.mm
    )
    set_source_files_properties(ios_video_writer.mm PROPERTIES COMPILE_OPTIONS -fobjc-arc)
endif()

set_target_properties(armsx3_bridge PROPERTIES
    CXX_STANDARD 23
    CXX_STANDARD_REQUIRED ON
    # The app links this through the merged archive, so the objects must be position-independent.
    POSITION_INDEPENDENT_CODE ON
)

# PUBLIC, because both consumers include host_services.h and callbacks.h by name.
target_include_directories(armsx3_bridge PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# State the include roots here instead of relying on rpcs3_emu's interface, which another change could narrow.
target_include_directories(armsx3_bridge PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/rpcs3
)

# Link rpcs3_emu and nothing else; if this target ever needs Vulkan, move a body to the consumer instead.
target_link_libraries(armsx3_bridge PUBLIC
    rpcs3_emu
)

# yaml-cpp's include path, for settings_engine.cpp's yaml_load.
target_link_libraries(armsx3_bridge PRIVATE
    3rdparty::yaml-cpp
)

target_compile_definitions(armsx3_bridge PRIVATE
    ARMSX3_COMPILER_ID="${CMAKE_CXX_COMPILER_ID}"
)

# Upstream sets -Werror=reorder in rpcs3/CMakeLists.txt, which does not reach this target; it fails the build on an out-of-order designator in the callback table.
target_compile_options(armsx3_bridge PRIVATE
    -Werror=reorder
)
