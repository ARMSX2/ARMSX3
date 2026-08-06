package com.armsx3;

import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Records native calls that ARMSX2's UI makes but ARMSX3 does not implement.
 *
 * The point is visibility. A stubbed method that quietly returns a default is
 * indistinguishable from a working one until a user reports "this setting does
 * nothing" - and then it costs a debugging cycle to find. Anything routed
 * through here shows up in logcat the first time it is hit:
 *
 *     adb logcat -s ARMSX3-Unsupported
 *
 * Use that list to decide what to WIRE UP versus what screen to REMOVE. A
 * control with no PS3 meaning (PS2 memory cards, PNACH patches, GUNCON,
 * RetroAchievements) should be deleted from the UI, not left calling a stub.
 */
final class Unsupported {
    private static final String TAG = "ARMSX3-Unsupported";

    // Log each name once. These fire from UI callbacks that can run every
    // frame or every recomposition; unbounded logging would drown logcat.
    private static final Set<String> SEEN =
        Collections.synchronizedSet(new HashSet<String>());

    private Unsupported() {}

    static void note(String what) {
        if (SEEN.add(what)) {
            Log.w(TAG, "NativeApp." + what + "() is not implemented on the RPCS3 core "
                + "- the UI element driving it should be wired up or removed");
        }
    }

    /** Everything hit so far this process, for an in-app diagnostics screen. */
    static Set<String> seen() {
        synchronized (SEEN) {
            return new HashSet<>(SEEN);
        }
    }
}
