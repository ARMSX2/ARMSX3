# SPDX-License-Identifier: GPL-2.0-only
# The renderer-selection body, kept out of armsx3_bridge so that library links rpcs3_emu and nothing else.
add_library(armsx3_ios_render STATIC
    ios_render.cpp
)

set_target_properties(armsx3_ios_render PROPERTIES
    CXX_STANDARD 23
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)

target_include_directories(armsx3_ios_render PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Explicit include roots, plus ios/bridge by path because armsx3_bridge is added after this directory.
target_include_directories(armsx3_ios_render PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/rpcs3
    ${CMAKE_SOURCE_DIR}/ios/bridge
)

# Vulkan is why this target exists: the VK headers arrive through 3rdparty::vulkan.
target_link_libraries(armsx3_ios_render PUBLIC
    rpcs3_emu
    3rdparty::vulkan
)

# The compiler ID for the capture sidecar.
target_compile_definitions(armsx3_ios_render PRIVATE
    ARMSX3_COMPILER_ID="${CMAKE_CXX_COMPILER_ID}"
)
