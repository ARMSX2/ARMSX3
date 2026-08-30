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
# Really Slick Screensavers -> libsavers.so
#
# Terry Welsh's savers (GPL-2.0-or-later), compiled against a GLES2 shim rather than rewritten.
# The saver sources are BYTE-IDENTICAL to upstream: they are built with RS_XSCREENSAVER, which
# selects their platform-neutral path, and everything that path expects is answered by
# compat/. That keeps them re-pullable and keeps them recognisably his code.
#
# Separate from the flurry target because these are C++ and Flurry is C99, and because Flurry's
# shim is a different, smaller one. If a saver ever needs Flurry's, the two can merge.

add_library(savers SHARED
    savers/savers_jni.cpp
    savers/savers_platform.cpp
    savers/gl1.c

    # Each saver is compiled through a *_unit.cpp that wraps it in a namespace: they all
    # declare the same globals (draw, idleProc, cleanUp, readyToDraw) because each was built
    # as its own executable, and two in one .so collide at link time.
    savers/flux/flux_unit.cpp
    savers/flux/flux_port.cpp
    savers/plasma/plasma_unit.cpp
    savers/plasma/plasma_port.cpp
    savers/solarwinds/solarwinds_unit.cpp
    savers/solarwinds/solarwinds_port.cpp

    # Hyperspace. extensions.cpp is deliberately NOT built: it is the WIN32/GLX loader for the
    # ARB shader path, and the port takes the non-shader path instead. The entry points it
    # would have resolved are declared in compat/arb_shaders.h and defined in savers_platform.
    savers/hyperspace/unit_causticTextures.cpp
    savers/hyperspace/unit_flare.cpp
    savers/hyperspace/unit_goo.cpp
    savers/hyperspace/unit_hyperspace.cpp
    savers/hyperspace/unit_splinePath.cpp
    savers/hyperspace/unit_starBurst.cpp
    savers/hyperspace/unit_stretchedParticle.cpp
    savers/hyperspace/unit_tunnel.cpp
    savers/hyperspace/unit_wavyNormalCubeMaps.cpp
    savers/hyperspace/hyperspace_port.cpp

    savers/lattice/lattice_unit.cpp
    savers/lattice/lattice_port.cpp

    # Skyrocket. soundEngine.cpp is NOT built and its ~7MB of samples are not shipped: upstream
    # drives OpenAL, the port runs with dSound = 0, and soundEngine.h is a stub.
    savers/skyrocket/unit_flare.cpp
    savers/skyrocket/unit_particle.cpp
    savers/skyrocket/unit_shockwave.cpp
    savers/skyrocket/unit_skyrocket.cpp
    savers/skyrocket/unit_smoke.cpp
    savers/skyrocket/unit_world.cpp
    savers/skyrocket/skyrocket_port.cpp

    savers/rslibs/rsMath/rsVec.cpp
    savers/rslibs/rsMath/rsVec4.cpp
    savers/rslibs/rsMath/rsMatrix.cpp
    savers/rslibs/rsMath/rsQuat.cpp
    savers/rslibs/Rgbhsl/Rgbhsl.cpp

    # Implicit surfaces: Hyperspace's "goo" is a marching-cubes isosurface.
    savers/rslibs/Implicit/impSurface.cpp
    savers/rslibs/Implicit/impCubeVolume.cpp
    savers/rslibs/Implicit/impCubeTables.cpp
    savers/rslibs/Implicit/impShape.cpp
    savers/rslibs/Implicit/impSphere.cpp
    savers/rslibs/Implicit/impCapsule.cpp
    savers/rslibs/Implicit/impEllipsoid.cpp
    savers/rslibs/Implicit/impHexahedron.cpp
    savers/rslibs/Implicit/impRoundedHexahedron.cpp
    savers/rslibs/Implicit/impKnot.cpp
    savers/rslibs/Implicit/impTorus.cpp

)

target_include_directories(savers PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/savers
    ${CMAKE_CURRENT_SOURCE_DIR}/savers/compat
    ${CMAKE_CURRENT_SOURCE_DIR}/savers/rslibs
    ${CMAKE_CURRENT_SOURCE_DIR}/savers/flux
    ${CMAKE_CURRENT_SOURCE_DIR}/savers/plasma
    ${CMAKE_CURRENT_SOURCE_DIR}/savers/solarwinds
    ${CMAKE_CURRENT_SOURCE_DIR}/savers/hyperspace
    ${CMAKE_CURRENT_SOURCE_DIR}/savers/lattice
    ${CMAKE_CURRENT_SOURCE_DIR}/savers/skyrocket
)

# RS_XSCREENSAVER picks the savers' platform-neutral path. Without it they compile their Win32
# shell and nothing links.
target_compile_definitions(savers PRIVATE RS_XSCREENSAVER)
set_target_properties(savers PROPERTIES C_STANDARD 99 CXX_STANDARD 17)

# -ffast-math for the same reason as Flurry: particle simulation judged entirely by eye.
target_compile_options(savers PRIVATE -O2 -ffast-math -Wall -Wno-unused-parameter)
target_link_libraries(savers log GLESv2 m)

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
