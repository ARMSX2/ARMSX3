package com.armsx2.runtime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Window
import android.view.WindowManager
import com.armsx2.config.ConfigStore
import com.armsx3.NativeApp
import kotlin.math.abs
import kotlin.math.roundToInt

class EmulationSurface(context: Context) :
    SurfaceView(context),
    SurfaceHolder.Callback,
    DisplayManager.DisplayListener {
    private var viewWidth = 0
    private var viewHeight = 0
    private var gameActive = false
    private var lastRequestedFrameRate = Float.NaN
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val frameRateMonitor = object : Runnable {
        override fun run() {
            if (!gameActive || !isAttachedToWindow) return
            applyFrameRatePreference()
            // The VM's nominal rate becomes available after the Surface. Keep this
            // cheap poll alive so PAL/NTSC changes and disc swaps are picked up too.
            postDelayed(this, FRAME_RATE_MONITOR_MS)
        }
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        keepScreenOn = true
    }

    private fun hostWindow(): Window? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current.window
            current = current.baseContext
        }
        return null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hostWindow()?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        displayManager.registerDisplayListener(this, null)
        redeliverSurface()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) redeliverSurface()
    }

    /**
     * Hand the current Surface to native again, if we already have a usable one.
     *
     * surfaceChanged is a ONE-SHOT: Android delivers it when the surface is created or resized and
     * never repeats it. The native renderer blocks in getNativeWindow() until that single delivery
     * arrives — a 100 ms sleep loop with no timeout — so if it is ever missed, the RSX thread parks
     * forever and the game area stays black at 0% CPU with the boot log stopping dead just after
     * Vulkan device creation. That is the "launch a game the instant the app opens and get a black
     * screen" report: the SurfaceView and its compositor layer exist, the touch controls draw over
     * it, and nothing is wrong except that native was never told. Rotating the device "fixed" it
     * only because a configuration change forces a fresh surfaceChanged.
     *
     * Re-delivering is safe and idempotent: the native side compares the incoming ANativeWindow
     * against the one it holds and treats an identical pointer as a no-op, so calling this on every
     * attach and every window-visibility change costs nothing when the surface already arrived.
     */
    fun redeliverSurface() {
        // post() rather than inline: onAttachedToWindow runs before layout, so width/height are
        // still 0 here, and a 0x0 report is exactly what the native side is told to ignore.
        post {
            val current = holder.surface

            if (current != null && current.isValid && width > 0 && height > 0) {
                pushDisplayCutoutInset(width, height)
                NativeApp.onNativeSurfaceChanged(current, width, height)
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameRateMonitor)
        clearFrameRatePreference()
        displayManager.unregisterDisplayListener(this)
        hostWindow()?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestFocus()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        reportActualDisplayRefreshRate()
        applyFrameRatePreference()
        pushDisplayCutoutInset(width, height)
        NativeApp.onNativeSurfaceChanged(holder.surface, width, height)
    }

    /**
     * Tell the GS how much room a punch-hole/notch camera needs at the top.
     *
     * Portrait top-aligns the render so the bottom is free for touch controls, which put the image
     * directly under the camera — it sat on the game, reported by Isshin. Sent from here because
     * this is the one place that runs on creation AND on every rotation and resize, and the value
     * has to be in the same surface pixels the renderer works in.
     *
     * Landscape sends 0: the cutout is then on a side, and shifting vertically would not help.
     */
    private fun pushDisplayCutoutInset(width: Int, height: Int) {
        val inset = if (height > width && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { rootWindowInsets?.displayCutout?.safeInsetTop ?: 0 }.getOrDefault(0)
        } else {
            0
        }
        runCatching { NativeApp.setPortraitRenderTopInset(inset) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        lastRequestedFrameRate = Float.NaN
        // ★ onNativeSurfaceDestroyed(), NOT onNativeSurfaceChanged(null, 0, 0). Both null s_window,
        // but the latter gates its MTGS::UpdateDisplayWindow() repost on `width > 0 && height > 0`
        // — false here — so the GS thread was NEVER told the surface died. It could then sit in
        // vkAcquireNextImageKHR with a UINT64_MAX timeout on a swapchain whose window is no longer
        // composited, and because every Java->GS route is marshalled through the CPU thread, the
        // only code that could rebuild the swapchain was queued behind the CPU thread that the GS
        // thread was blocking. Nothing times out — that is the "sometimes it never unpauses" case.
        // The correct entry point existed and was fully implemented; it just had no caller.
        NativeApp.onNativeSurfaceDestroyed()
    }

    override fun onDisplayAdded(displayId: Int) = Unit

    override fun onDisplayRemoved(displayId: Int) = Unit

    override fun onDisplayChanged(displayId: Int) {
        if (displayId != currentDisplay()?.displayId) return
        reportActualDisplayRefreshRate()
    }

    /**
     * Called from the Activity's VM-state effect. The high-refresh vote only
     * exists during gameplay; menus and the library remain under Android's
     * normal power-saving policy.
     */
    fun setGameActive(active: Boolean) {
        if (gameActive == active) {
            if (active) applyFrameRatePreference()
            return
        }
        gameActive = active
        removeCallbacks(frameRateMonitor)
        if (active) {
            applyFrameRatePreference()
            postDelayed(frameRateMonitor, FRAME_RATE_RETRY_MS)
        } else {
            clearFrameRatePreference()
        }
    }

    /**
     * Low Latency Mode asks Android 11+ for a high-refresh mode which is an
     * integer multiple of the emulated game's nominal rate. Thus ~60 fps picks
     * 120 Hz and PAL 50 fps prefers 100 Hz, while a 90 Hz-only panel falls back
     * to its ~60 Hz mode instead of introducing 3:2 cadence.
     */
    fun applyFrameRatePreference() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            !gameActive ||
            !holder.surface.isValid ||
            !lowLatencyEnabled()
        ) {
            clearFrameRatePreference()
            return
        }

        val nominalRate = runCatching { NativeApp.getNominalFrameRate() }
            .getOrDefault(0f)
            .takeIf { it in MIN_GAME_RATE..MAX_GAME_RATE }
            ?: DEFAULT_GAME_RATE
        val requestedRate = preferredDisplayRefreshRate(nominalRate)
        if (!requestedRate.isFinite() || requestedRate <= 0f ||
            abs(requestedRate - lastRequestedFrameRate) < RATE_EPSILON
        ) return

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                holder.surface.setFrameRate(
                    requestedRate,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
            } else {
                @Suppress("DEPRECATION")
                holder.surface.setFrameRate(
                    requestedRate,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                )
            }
            lastRequestedFrameRate = requestedRate
        }
    }

    private fun clearFrameRatePreference() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            !holder.surface.isValid ||
            lastRequestedFrameRate.isNaN()
        ) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                holder.surface.setFrameRate(
                    0f,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
            } else {
                @Suppress("DEPRECATION")
                holder.surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
        lastRequestedFrameRate = Float.NaN
    }

    private fun lowLatencyEnabled(): Boolean = runCatching {
        val settingsKey = MainActivityRuntime.currentGame.value?.settingsKey
            ?: NativeApp.getGameSerial().takeIf { it.isNotBlank() }
        ConfigStore.resolveForGame(settingsKey)
            .vsyncQueueSize == 0
    }.getOrDefault(false)

    private fun preferredDisplayRefreshRate(nominalRate: Float): Float {
        val current = currentDisplay() ?: return 0f
        val rates = current.supportedModes
            .asSequence()
            // Avoid a refresh vote which also asks Android to change resolution.
            .filter {
                it.physicalWidth == current.mode.physicalWidth &&
                    it.physicalHeight == current.mode.physicalHeight
            }
            .map { it.refreshRate }
            .filter { it > 0f }
            .distinct()
            .toList()
        if (rates.isEmpty()) return current.refreshRate

        val integerMultiples = rates.filter { rate ->
            val multiple = (rate / nominalRate).roundToInt()
            multiple >= 2 && abs(rate - nominalRate * multiple) <= RATE_MATCH_TOLERANCE_HZ
        }
        integerMultiples.maxOrNull()?.let { return it }

        // No clean high-refresh multiple (for example 60 fps on a 90 Hz-only
        // panel): preserve even pacing by choosing the closest native-rate mode.
        return rates.minBy { abs(it - nominalRate) }
    }

    private fun reportActualDisplayRefreshRate() {
        NativeApp.setDisplayRefreshRate(currentDisplayRefreshHz())
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        viewWidth = width
        viewHeight = height
        applyOutputScale()
    }

    fun applyOutputScale() {
        if (viewWidth <= 0 || viewHeight <= 0) return
        // Scoped, not a raw pref: both of these are per-game overridable, so resolve the
        // effective Settings for whatever is running. Reading prefs here would ignore a
        // per-game value (and writing them there was the bug that made a Game-scope
        // change also move Global).
        //
        // Resolved from ConfigStore rather than InGameOverlay.settingsState on purpose:
        // that state is only populated when the overlay OPENS, so at game-boot layout it
        // still holds the previous game's (or global) values and the new game's override
        // would not apply until you opened the menu.
        val effective = runCatching {
            com.armsx2.config.ConfigStore.resolveForGame(MainActivityRuntime.currentGame.value?.settingsKey)
        }.getOrElse { com.armsx2.ui.InGameOverlay.settingsState.value }
        val multiplier = effective.hwScaler

        // Base output resolution: an explicit "WxH" override when set (fixes wrong panel detection,
        // e.g. a 1920x1080 panel mis-reported as 1920x1200 which squishes 16:9 games — issue #398),
        // otherwise the SurfaceView's laid-out size.
        val override = parseResOverride(effective.screenResOverride)
        val baseW = override?.first ?: viewWidth
        val baseH = override?.second ?: viewHeight

        // hwScaler (if > 0) is now the target short side in PIXELS (1080/720/540),
        // not a multiple of the PS2's 448-line native height -- the PS3 outputs
        // 720p, so PS2 multiples produced meaningless render targets here.
        val shortSide = minOf(baseW, baseH)
        val targetShortSide = if (multiplier > 0) multiplier else shortSide
        val scale = if (targetShortSide in 1 until shortSide) targetShortSide.toFloat() / shortSide else 1f

        // With no override and no downscale, let the surface track the layout (native panel size) —
        // the original behavior. Otherwise pin an explicit buffer size and let the compositor scale.
        if (override == null && scale == 1f) {
            holder.setSizeFromLayout()
            return
        }
        holder.setFixedSize(
            (baseW * scale).toInt().coerceAtLeast(1),
            (baseH * scale).toInt().coerceAtLeast(1),
        )
    }

    /** Parse a "WIDTHxHEIGHT" override pref (e.g. "1920x1080") to a size, or null for "auto"/unset/bad. */
    private fun parseResOverride(value: String?): Pair<Int, Int>? {
        if (value.isNullOrBlank() || value == "auto") return null
        val m = Regex("^\\s*(\\d{2,5})\\s*[xX]\\s*(\\d{2,5})\\s*$").find(value) ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: return null
        return if (w in 64..8192 && h in 64..8192) Pair(w, h) else null
    }

    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display else display

    private fun currentDisplayRefreshHz(): Float =
        runCatching { currentDisplay()?.refreshRate ?: 0f }.getOrDefault(0f)

    private companion object {
        const val DEFAULT_GAME_RATE = 59.94f
        const val MIN_GAME_RATE = 20f
        const val MAX_GAME_RATE = 125f
        const val RATE_EPSILON = 0.05f
        const val RATE_MATCH_TOLERANCE_HZ = 0.6f
        const val FRAME_RATE_RETRY_MS = 500L
        const val FRAME_RATE_MONITOR_MS = 5_000L
    }
}
