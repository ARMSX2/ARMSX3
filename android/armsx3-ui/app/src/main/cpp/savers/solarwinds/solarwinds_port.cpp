/* Android entry points for SolarWinds. solarWinds.cpp is byte-identical to upstream. */

#include "gl1.h"

namespace saver_solarwinds {
void setDefaults(int which);
void initSaver();
void reshape(int width, int height);
void idleProc();
void cleanUp();
extern int readyToDraw;
}

namespace { bool g_started = false; }

extern "C" {

/* Defined below. port_new tears a stale run down through it rather than trusting
 * g_started, so the declaration has to come first. */
void solarwinds_port_free();

/* preset is 1..6 from the UI. Upstream's DEFAULTS1..DEFAULTS6 is a zero-based ENUM here, not
 * the 1-based #defines Flux uses, so the UI value is shifted down. */
int solarwinds_port_new(int preset)
{
    /* NOT "if (g_started) return 1": nativeInit calls gl1_lost() before every create, so
     * gl1 is guaranteed DOWN on entry now. Reporting success here would hand the caller a
     * saver with no shim under it. That guard was only ever safe because gl1 state
     * survived between savers -- which is exactly the property gl1_lost() removed, so it
     * went from redundant to wrong. Unreachable today (port_free always clears g_started),
     * but the rule is that a stale run is torn down and gl1_init() always runs, rather
     * than that every caller gets the ordering right. */
    if (g_started) solarwinds_port_free();
    if (!gl1_init()) return 0;

    if (preset < 1 || preset > 6) preset = 1;
    saver_solarwinds::setDefaults(preset - 1);

    saver_solarwinds::initSaver();
    g_started = saver_solarwinds::readyToDraw != 0;
    if (!g_started) {
        /* Returning 0 means the JNI never calls port_free, so this is the only chance to give
         * gl1 back. Leaving it up would strand g.ready with names from a context that is about
         * to die, and gl1_init() early-returns on g.ready -- poisoning the NEXT saver. */
        gl1_shutdown();
        return 0;
    }
    return 1;
}

void solarwinds_port_resize(int width, int height)
{
    if (width > 0 && height > 0) saver_solarwinds::reshape(width, height);
}

void solarwinds_port_draw()
{
    if (!g_started) return;
    gl1_frame_begin();
    saver_solarwinds::idleProc();

}

void solarwinds_port_free()
{
    /* NOT "if (!g_started) return": port_new releases gl1 itself on the path where it
     * returns 0, so this is unreachable with g_started false today. It is written this
     * way so that stays true by construction rather than by that argument -- the rule is
     * that gl1 goes back on every exit, in every port, without a caller having to reason
     * about which ones can be skipped. */
    if (g_started) {
        saver_solarwinds::cleanUp();
        saver_solarwinds::readyToDraw = 0;
        g_started = false;
    }
    /* Belongs to the EGL context that is about to be destroyed; leaving g.ready set means
     * gl1_init() early-returns for the NEXT saver and hands it dead GL names. Idempotent,
     * and a no-op if gl1 was never up. */
    gl1_shutdown();
}

}
