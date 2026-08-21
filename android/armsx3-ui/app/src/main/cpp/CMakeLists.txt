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
# Flurry -> libflurry.so
#
# Calum Robinson's Flurry screensaver (2002), offered as a live library background. Its own
# target rather than folded into the glue for two reasons: it is BSD-3-clause next to GPL code
# and the boundary should be visible, and it is plain C from 2002 that wants none of the C++20
# and adrenotools the glue is built with.
#
# gl_compat.c answers the GL 1.x calls the sources make -- client-side vertex arrays, a
# fixed-function ortho and GL_QUADS -- with a GLES2 shader, so the renderer itself stays
# unmodified. See its header for what is and is not emulated.
# ---------------------------------------------------------------------------
add_library(flurry SHARED
    flurry/flurry_jni.c
    flurry/gl_compat.c
    flurry/flurry.c
    flurry/flurry-smoke.c
    flurry/flurry-spark.c
    flurry/flurry-star.c
    flurry/flurry-texture.c
)

target_include_directories(flurry PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/flurry)
set_target_properties(flurry PROPERTIES C_STANDARD 99)

# -ffast-math: a particle simulation judged by eye, running thousands of sqrt per frame.
# Upstream shipped hand-written frsqrte paths for exactly this reason.
target_compile_options(flurry PRIVATE -O2 -ffast-math -Wall -Wno-unused-parameter)
target_link_libraries(flurry log GLESv2 m)

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
