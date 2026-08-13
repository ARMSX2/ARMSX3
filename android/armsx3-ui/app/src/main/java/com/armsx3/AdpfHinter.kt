package com.armsx3

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.rpcsx.RPCSX

/**
 * Drives Android's PerformanceHintManager from the emulator's own frame timing.
 *
 * The point is to tell the scheduler what deadline we are actually trying to hit and how much
 * CPU the last frame really needed. Without a reported *period* the platform assumes a 60fps
 * target, so a 30fps-locked game looks permanently late and the governor boosts clocks for
 * nothing -- more heat, worse sustained performance, no extra frames.
 *
 * The session is created against the RSX thread's OS tid, because that is the thread whose
 * deadline actually matters; the tid is re-read each tick so a restart (which spawns a new RSX
 * thread) re-targets instead of hinting for a dead thread.
 *
 * API 31+. Below that this is inert, which is correct -- the API does not exist.
 */
object AdpfHinter {
    private var job: Job? = null
    private var session: PerformanceHintManager.Session? = null
    private var sessionTid: Int = 0

    private const val TICK_MS = 250L

    fun setEnabled(context: Context, enabled: Boolean) {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            stop()
            return
        }

        if (job != null) return

        val manager = runCatching {
            context.getSystemService(PerformanceHintManager::class.java)
        }.getOrNull() ?: return

        job = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                runCatching { tick(manager) }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { session?.close() }
        session = null
        sessionTid = 0
    }

    private fun tick(manager: PerformanceHintManager) {
        val periodNs = RPCSX.instance.getFramePeriodNs()
        val workNs = RPCSX.instance.getFrameWorkNs()
        val tid = RPCSX.instance.getRsxThreadTid()

        // 0 means the core has not measured a frame yet (or is too old to export this). Feeding
        // that to the OS would be worse than staying quiet.
        if (periodNs <= 0L || workNs <= 0L || tid == 0) return

        // Rebuild when the RSX thread changes: a session is bound to specific tids at creation,
        // and after a restart the old ones are dead.
        if (session == null || sessionTid != tid) {
            runCatching { session?.close() }
            session = runCatching {
                manager.createHintSession(intArrayOf(tid), periodNs)
            }.getOrNull()
            sessionTid = tid
            if (session == null) return
        }

        // Period first: reportActualWorkDuration is judged against the current target, so
        // updating work against a stale target is what produces the over-boost this exists
        // to avoid.
        runCatching {
            session?.updateTargetWorkDuration(periodNs)
            session?.reportActualWorkDuration(workNs)
        }
    }
}
