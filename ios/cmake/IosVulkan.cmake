# SPDX-License-Identifier: GPL-2.0-only
# Vulkan for arm64-ios: fill FindVulkan's two cache variables with the static MoltenVK slice; include before add_subdirectory(3rdparty).

set(ARMSX3_MOLTENVK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ios/3rdparty/MoltenVK/ios-pkg/MoltenVK")
set(ARMSX3_MOLTENVK_INCLUDE_DIR "${ARMSX3_MOLTENVK_DIR}/include")
set(ARMSX3_MOLTENVK_LIBRARY
    "${ARMSX3_MOLTENVK_DIR}/static/MoltenVK.xcframework/ios-arm64/libMoltenVK.a")

# Fail here rather than let find_package fall through to a host MoltenVK.
if(NOT EXISTS "${ARMSX3_MOLTENVK_LIBRARY}")
    message(FATAL_ERROR
        "ARMSX3: the iOS MoltenVK static slice is missing:\n"
        "  ${ARMSX3_MOLTENVK_LIBRARY}\n"
        "Run ios/scripts/fetch-moltenvk.sh to fetch and SHA-256-verify it. "
        "The configure is stopped here deliberately: without it, find_package(Vulkan) "
        "would search the host and can resolve a macOS Homebrew molten-vk into an "
        "arm64-ios link.")
endif()

if(NOT EXISTS "${ARMSX3_MOLTENVK_INCLUDE_DIR}/vulkan/vulkan.h")
    message(FATAL_ERROR
        "ARMSX3: the iOS MoltenVK headers are missing:\n"
        "  ${ARMSX3_MOLTENVK_INCLUDE_DIR}/vulkan/vulkan.h\n"
        "Run ios/scripts/fetch-moltenvk.sh. CMake's FindVulkan also reads "
        "vulkan/vulkan_core.h from this directory to derive Vulkan_VERSION.")
endif()

# FORCE, because a plain set() would be ignored on a reconfigure and revert to a host answer.
set(Vulkan_INCLUDE_DIR "${ARMSX3_MOLTENVK_INCLUDE_DIR}" CACHE PATH
    "ARMSX3 iOS: MoltenVK headers from the SHA-256-pinned iOS package fetch-moltenvk.sh names." FORCE)
set(Vulkan_LIBRARY "${ARMSX3_MOLTENVK_LIBRARY}" CACHE FILEPATH
    "ARMSX3 iOS: the static MoltenVK ios-arm64 xcframework slice." FORCE)

message(STATUS "ARMSX3: Vulkan -> static MoltenVK ios-arm64 slice ${Vulkan_LIBRARY}")
