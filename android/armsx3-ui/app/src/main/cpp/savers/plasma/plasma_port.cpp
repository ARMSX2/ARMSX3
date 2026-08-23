/* Android entry points for Plasma. plasma.cpp is byte-identical to upstream. */

#include "gl1.h"

namespace saver_plasma {
void setDefaults();
void initSaver();
void reshape(int width, int height);
void idleProc();
void cleanUp();
extern int readyToDraw;
extern int dZoom, dFocus, dSpeed, dResolution;
}

namespace { bool g_started = false; }

extern "C" {

/* Defined below. port_new tears a stale run down through it rather than trusting
 * g_started, so the declaration has to come first. */
void plasma_port_free();

int plasma_port_new(int preset)
{
    /* NOT "if (g_started) return 1": nativeInit calls gl1_lost() before every create, so
     * gl1 is guaranteed DOWN on entry now. Reporting success here would hand the caller a
     * saver with no shim under it. That guard was only ever safe because gl1 state
     * survived between savers -- which is exactly the property gl1_lost() removed, so it
     * went from redundant to wrong. Unreachable today (port_free always clears g_started),
     * but the rule is that a stale run is torn down and gl1_init() always runs, rather
     * than that every caller gets the ordering right. */
    if (g_started) plasma_port_free();
    if (!gl1_init()) return 0;

    saver_plasma::setDefaults();

    /* Plasma ships no preset list upstream -- every knob was a registry value -- so these are
     * ours, built from the settings its config dialog exposed. Confirmed working on device;
     * 1 is upstream's defaults untouched. */
    switch (preset) {
    case 2:  // Tight
        saver_plasma::dZoom = 25; saver_plasma::dFocus = 60; break;
    case 3:  // Wide
        saver_plasma::dZoom = 3; saver_plasma::dFocus = 12; break;
    case 4:  // Fast
        saver_plasma::dSpeed = 50; break;
    case 5:  // Slow drift
        saver_plasma::dSpeed = 6; break;
    case 6:  // Coarse, and cheapest to draw
        saver_plasma::dResolution = 12; saver_plasma::dSpeed = 25; break;
    default: break;
    }

    saver_plasma::initSaver();
    g_started = saver_plasma::readyToDraw != 0;
    if (!g_started) {
        /* Returning 0 means the JNI never calls port_free, so this is the only chance to give
         * gl1 back. Leaving it up would strand g.ready with names from a context that is about
         * to die, and gl1_init() early-returns on g.ready -- poisoning the NEXT saver. */
        gl1_shutdown();
        return 0;
    }
    return 1;
}

void plasma_port_resize(int width, int height)
{
    if (width > 0 && height > 0) saver_plasma::reshape(width, height);
}

void plasma_port_draw()
{
    if (!g_started) return;
    gl1_frame_begin();
    saver_plasma::idleProc();
}

void plasma_port_free()
{
    /* NOT "if (!g_started) return": port_new releases gl1 itself on the path where it
     * returns 0, so this is unreachable with g_started false today. It is written this
     * way so that stays true by construction rather than by that argument -- the rule is
     * that gl1 goes back on every exit, in every port, without a caller having to reason
     * about which ones can be skipped. */
    if (g_started) {
        saver_plasma::cleanUp();
        saver_plasma::readyToDraw = 0;
        g_started = false;
    }
    /* Belongs to the EGL context that is about to be destroyed; leaving g.ready set means
     * gl1_init() early-returns for the NEXT saver and hands it dead GL names. Idempotent,
     * and a no-op if gl1 was never up. */
    gl1_shutdown();
}

}
