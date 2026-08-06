package net.rpcsx

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import net.rpcsx.utils.GeneralSettings

/**
 * Android-side power/perf controls.
 *
 * Deliberately scoped to what the CORE does not already provide. RPCS3's own
 * config already exposes -- and the generated settings screen already renders --
 * Frame limit (the fps cap), Second Frame Limit, Thread Scheduler Mode, Sleep
 * Timers Accuracy, PPU/SPURS thread counts, Vblank Rate and Driver Wake-Up
 * Delay. Duplicating those here would create two controls for one value, which
 * is the classic "I changed the setting and nothing happened" bug.
 *
 * What is left is genuinely platform-only:
 *  - Sustained performance mode: asks the platform for a LOWER but flat clock
 *    ceiling. Counter-intuitive but usually a win for a long session: it trades
 *    a hot burst for a frame rate that does not collapse once the device
 *    thermally throttles.
 *  - ADPF CPU clock hint: tells the platform how long our frame work actually
 *    took, so the governor can pick a clock instead of guessing from load. On
 *    an emulator the guess is usually wrong -- a busy-waiting SPU thread reads
 *    as 100% load whether or not it needs the clock.
 */
object AndroidPerformance {
    private const val SustainedKey = "perf.sustained"
    private const val ClockHintKey = "perf.clockHint"
    private const val TargetFpsKey = "perf.clockHint.targetFps"

    /** Flat-clock mode. Off by default -- it lowers peak clocks. */
    val sustainedMode = mutableStateOf(false)

    /**
     * ADPF. Off by default and EXPERIMENTAL: reporting bad numbers is worse
     * than reporting none, because the governor then actively fights you.
     */
    val clockHint = mutableStateOf(false)

    /** Target frame rate the hint session is built around. */
    val clockHintTargetFps = mutableIntStateOf(60)

    private var hintSession: PerformanceHintManager.Session? = null

    val isSustainedSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    val isClockHintSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun load() {
        if (!GeneralSettings.isInitialized()) return
        sustainedMode.value = GeneralSettings.raw.getBoolean(SustainedKey, false)
        clockHint.value = GeneralSettings.raw.getBoolean(ClockHintKey, false)
        clockHintTargetFps.intValue = GeneralSettings.raw.getInt(TargetFpsKey, 60)
    }

    fun setSustained(activity: Activity, enabled: Boolean) {
        sustainedMode.value = enabled
        GeneralSettings.raw.edit().putBoolean(SustainedKey, enabled).apply()
        applySustained(activity)
    }

    /** Call from the emulator Activity's onCreate/onResume. */
    fun applySustained(activity: Activity) {
        if (!isSustainedSupported) return
        runCatching {
            activity.window.setSustainedPerformanceMode(sustainedMode.value)
        }
    }

    fun setClockHint(context: Context, enabled: Boolean) {
        clockHint.value = enabled
        GeneralSettings.raw.edit().putBoolean(ClockHintKey, enabled).apply()
        if (enabled) startHintSession(context) else stopHintSession()
    }

    fun setClockHintTargetFps(fps: Int) {
        val clamped = fps.coerceIn(15, 240)
        clockHintTargetFps.intValue = clamped
        GeneralSettings.raw.edit().putInt(TargetFpsKey, clamped).apply()
    }

    private fun targetDurationNanos(): Long =
        1_000_000_000L / clockHintTargetFps.intValue.coerceAtLeast(1)

    /**
     * Open an ADPF session for the CURRENT thread.
     *
     * Must be called from the thread whose work we are reporting -- the session
     * is bound to specific tids. Calling it from a UI thread and then reporting
     * emulator frame times would describe the wrong thread entirely.
     */
    fun startHintSession(context: Context) {
        if (!isClockHintSupported || !clockHint.value) return
        if (hintSession != null) return

        runCatching {
            val manager = context.getSystemService(PerformanceHintManager::class.java)
                ?: return
            hintSession = manager.createHintSession(
                intArrayOf(android.os.Process.myTid()),
                targetDurationNanos()
            )
        }
    }

    fun stopHintSession() {
        runCatching { hintSession?.close() }
        hintSession = null
    }

    /**
     * Report the wall time one frame's work actually took.
     *
     * Only meaningful if it reflects real work. Reporting a fabricated or
     * frame-pacing-derived number makes the governor's model worse than having
     * no hint at all.
     */
    fun reportActualFrameTime(nanos: Long) {
        val session = hintSession ?: return
        if (nanos <= 0) return
        runCatching { session.reportActualWorkDuration(nanos) }
    }

    /** Retarget an existing session after the user changes the fps goal. */
    fun updateTargetWorkDuration() {
        val session = hintSession ?: return
        runCatching { session.updateTargetWorkDuration(targetDurationNanos()) }
    }
}
