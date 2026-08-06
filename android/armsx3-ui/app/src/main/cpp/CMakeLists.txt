cmake_minimum_required(VERSION 3.10)
# The JNI GLUE, not the emulator core. Produces libarmsx3-jni.so, which
# System.loadLibrary("armsx3-jni") brings up; it then dlopen()s the core
# (libarmsx3-core.so, shipped as a prebuilt in jniLibs) and forwards to its
# _rpcsx_* entry points. adrenotools lives HERE rather than in the core, so the
# emulator stays free of a Qualcomm-specific dependency.
project("armsx3-jni")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE on)

if (CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64")
	add_subdirectory(libadrenotools)
else()
	add_library(adrenotools INTERFACE)
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED native-lib.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    adrenotools
)

# ---------------------------------------------------------------------------
# Discord Social SDK bridge -> libarmsx2_discord.so
#
# Separate target, and the manifest runs it in its own process. That is a
# LICENSING boundary, not a performance one: the Social SDK is proprietary with
# no published licence and the emulator core is GPL, so they must not share an
# address space.
#
# The prebuilt libdiscord_partner_sdk.so ships in the .aar too; it is referenced
# here as an IMPORTED target so the bridge links, and the .aar is what actually
# packages it.
# ---------------------------------------------------------------------------
if (CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64")
    add_library(discord_partner_sdk SHARED IMPORTED)
    set_target_properties(discord_partner_sdk PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/discord_sdk/arm64-v8a/libdiscord_partner_sdk.so)

    add_library(armsx2_discord SHARED discord_bridge.cpp)
    target_include_directories(armsx2_discord PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/discord_sdk/include)
    target_compile_features(armsx2_discord PRIVATE cxx_std_17)
    target_link_libraries(armsx2_discord discord_partner_sdk log android)
endif()
