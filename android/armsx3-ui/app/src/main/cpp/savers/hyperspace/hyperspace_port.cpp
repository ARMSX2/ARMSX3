/*
 * Android entry points for Hyperspace. hyperspace.cpp and its nine companions are
 * byte-identical to upstream.
 *
 * Hyperspace has two rendering paths and chooses with dShaders. The shader one uses ARB
 * shader objects, which GLES2 does not have; the port forces the other, which is a supported
 * upstream configuration rather than something improvised -- upstream exposes it as -shaders 0
 * and falls back to it on any card lacking the extensions.
 */

#include "gl1.h"

namespace saver_hyperspace {
void setDefaults();
void initSaver();
void reshape(int width, int height);
void idleProc();
void cleanUp();
extern int readyToDraw;
extern int dShaders;
}

namespace { bool g_started = false; }

extern "C" {

/* Defined below. port_new tears a stale run down through it rather than trusting
 * g_started, so the declaration has to come first. */
void hyperspace_port_free();

int hyperspace_port_new(int preset)
{
    (void) preset;  /* No presets upstream; every knob was a registry value. */
    /* NOT "if (g_started) return 1": nativeInit calls gl1_lost() before every create, so
     * gl1 is guaranteed DOWN on entry now. Reporting success here would hand the caller a
     * saver with no shim under it. That guard was only ever safe because gl1 state
     * survived between savers -- which is exactly the property gl1_lost() removed, so it
     * went from redundant to wrong. Unreachable today (port_free always clears g_started),
     * but the rule is that a stale run is torn down and gl1_init() always runs, rather
     * than that every caller gets the ordering right. */
    if (g_started) hyperspace_port_free();
    if (!gl1_init()) return 0;

    saver_hyperspace::setDefaults();

    /* Must be off BEFORE initSaver: with it on, initSaver builds the wavy normal cube maps and
     * the draw path then calls ARB entry points that are null here. */
    saver_hyperspace::dShaders = 0;

    saver_hyperspace::initSaver();

    /* Like Helios, Hyperspace leaves this to its Win32 shell. Unlike Helios, that is all it
     * leaves out. */
    saver_hyperspace::readyToDraw = 1;
    g_started = true;
    return 1;
}

void hyperspace_port_resize(int width, int height)
{
    if (width > 0 && height > 0) saver_hyperspace::reshape(width, height);
}

void hyperspace_port_draw()
{
    if (!g_started) return;
    gl1_frame_begin();
    saver_hyperspace::idleProc();
}

void hyperspace_port_free()
{
    /* NOT "if (!g_started) return": port_new sets g_started on every path that returns 1 today, so this
     * is defensive -- but the point is that gl1 is released regardless of it. See the note below. */
    if (g_started) {
        saver_hyperspace::cleanUp();
        saver_hyperspace::readyToDraw = 0;
        g_started = false;
    }
    /* gl1_init() ran in port_new, and everything it holds -- the shader program, the vertex
     * buffers -- belongs to the EGL context that is about to be destroyed. Returning without
     * gl1_shutdown() leaves gl1's g.ready set with GL names from a DEAD context, and gl1_init()
     * early-returns on g.ready. The next saver, in a NEW context, would then run against those
     * dead names: undefined behaviour that some drivers answer with a segfault rather than a GL
     * error, which takes the whole app down. So gl1 is torn down whether or not this saver's own
     * init ever got as far as running. gl1_shutdown() is idempotent. */
    gl1_shutdown();
}

}  /* extern "C" */
