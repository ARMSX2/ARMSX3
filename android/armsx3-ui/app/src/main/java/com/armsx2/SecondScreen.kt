package com.armsx2

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.mutableStateOf
import com.armsx2.i18n.I18n
import com.armsx2.runtime.MainActivityRuntime
import com.armsx3.NativeApp

/**
 * Utility panel on a SECOND display — Ayn Thor, the Retroid dual-screen add-on, or anything else
 * Android reports as an extra display (requested by Mike22). Shows live stats and the actions you
 * otherwise have to pause the game to reach.
 *
 * ★ Built from plain Views, not Compose, on purpose. A [Presentation] is its own Window with its
 * own decor view, and a ComposeView inside one only works after the ViewTree lifecycle/saved-state
 * owners are attached to that decor view — get it wrong and it throws at inflate time, on hardware
 * almost nobody testing this has. A handful of buttons does not justify that risk.
 *
 * Everything it calls is already thread-safe and already used by the on-screen equivalents, so the
 * panel adds no new emulator surface — it is a second set of buttons for existing actions.
 */
object SecondScreen {

    private const val PREF_KEY = "secondScreen.enabled"
    private const val PREF_OSD_KEY = "secondScreen.moveOsd"
    private const val TICK_MS = 500L

    /** Move the performance OSD off the game and onto this panel while it is showing (Shane [TDD]:
     *  "OSD down there instead of up top"). Uses the LIVE-only flag apply, so the user's saved
     *  per-stat OSD selection is never overwritten — it is restored the moment the panel goes away. */
    val moveOsd = mutableStateOf(true)

    fun setMoveOsd(value: Boolean) {
        moveOsd.value = value
        runCatching { MainActivityRuntime.prefs.edit().putBoolean(PREF_OSD_KEY, value).apply() }
        applyOsdRouting()
    }

    /** Suppress the on-game OSD while the panel is up; restore the user's own flags when it isn't. */
    private fun applyOsdRouting() {
        val suppress = moveOsd.value && presentation?.isShowing == true
        runCatching {
            if (suppress) {
                NativeApp.osdApplyFlags(
                    false, false, false, false, false, false, false, false, false, false, false, false,
                )
            } else {
                // Re-assert the user's own OSD mode rather than blanket-true, so someone who had
                // most stats off doesn't get them all switched on when the panel goes away.
                com.armsx2.ui.InGameOverlay.reapplyOsdMode()
            }
        }
    }

    /** User toggle (App settings). Default OFF — dual-screen owners turn it on themselves, and it
     *  should never surprise someone who plugs into a TV or casts (asked for by Shane [TDD]). */
    val enabled = mutableStateOf(false)

    private var presentation: Panel? = null
    private var listener: DisplayManager.DisplayListener? = null
    private val handler = Handler(Looper.getMainLooper())

    fun load() {
        runCatching {
            enabled.value = MainActivityRuntime.prefs.getBoolean(PREF_KEY, false)
            moveOsd.value = MainActivityRuntime.prefs.getBoolean(PREF_OSD_KEY, true)
        }
    }

    fun set(context: Context, value: Boolean) {
        enabled.value = value
        runCatching { MainActivityRuntime.prefs.edit().putBoolean(PREF_KEY, value).apply() }
        if (value) attach(context) else detach()
    }

    /** Whether ARMSX2 is in the foreground. A Presentation belongs to the app's window token but
     *  is NOT torn down when the activity stops, so the panel stayed up on the second display while
     *  the user was off doing something else entirely — reported, and it also meant a stale FPS
     *  reading sitting on screen. Driven from the activity's onResume/onPause. */
    @Volatile private var foreground: Boolean = true

    fun setForeground(context: Context, value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) attach(context) else detach()
    }

    /** Start watching for a second display and show the panel on one if present. */
    fun attach(context: Context) {
        if (!enabled.value || !foreground) return
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        if (listener == null) {
            val l = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = refresh(context)
                override fun onDisplayRemoved(displayId: Int) = refresh(context)
                override fun onDisplayChanged(displayId: Int) = Unit
            }
            runCatching { dm.registerDisplayListener(l, handler) }.onSuccess { listener = l }
        }
        refresh(context)
    }

    fun detach() {
        runCatching { presentation?.dismiss() }
        presentation = null
        // Hand the OSD back to the game screen the moment the panel is gone.
        applyOsdRouting()
    }

    /** Fully release (activity destroy). */
    fun release(context: Context) {
        detach()
        listener?.let { l ->
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            runCatching { dm?.unregisterDisplayListener(l) }
        }
        listener = null
    }

    private fun secondaryDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        // PRESENTATION category is the one Android intends for this; fall back to "any display that
        // isn't the built-in one" because some handhelds don't tag their second panel.
        val presentationDisplays = runCatching {
            dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        }.getOrNull()
        if (!presentationDisplays.isNullOrEmpty()) return presentationDisplays.first()
        return runCatching {
            dm.displays?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        }.getOrNull()
    }

    private fun refresh(context: Context) {
        if (!enabled.value || !foreground) { detach(); return }
        val target = secondaryDisplay(context)
        if (target == null) { detach(); return }
        // Already showing on this display? Leave it alone.
        presentation?.let { if (it.display?.displayId == target.displayId && it.isShowing) return }
        detach()
        runCatching {
            val p = Panel(context, target)
            p.show()
            presentation = p
            applyOsdRouting()
        }
    }

    /**
     * The XMB's colour for now, for the panel in XMB mode: the month's gradient at its angle, dimmed
     * at night, as the XMB's background draws it (XmbOriginalColors). Read again at every draw, so
     * the panel's tick keeps it with the XMB's, Theme Settings' Cycle included.
     */
    private class XmbBackdrop : Drawable() {
        private val paint = Paint()

        override fun draw(canvas: Canvas) {
            val g = com.armsx2.ui.xmb.XmbOriginalColors.now()
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            // A CSS angle, as the XMB's: 0 points up, turning clockwise, spanning the projection of
            // the whole panel onto that direction.
            val a = Math.toRadians(g.angleDeg.toDouble())
            val dx = Math.sin(a).toFloat()
            val dy = -Math.cos(a).toFloat()
            val half = (Math.abs(w * dx) + Math.abs(h * dy)) / 2f
            fun color(c: FloatArray) = Color.rgb(
                (c[0].coerceIn(0f, 1f) * 255).toInt(),
                (c[1].coerceIn(0f, 1f) * 255).toInt(),
                (c[2].coerceIn(0f, 1f) * 255).toInt(),
            )
            paint.shader = LinearGradient(
                bounds.exactCenterX() - dx * half, bounds.exactCenterY() - dy * half,
                bounds.exactCenterX() + dx * half, bounds.exactCenterY() + dy * half,
                color(g.start), color(g.end), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(bounds, paint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Drawable.getOpacity", ReplaceWith(""))
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    /** The panel itself. */
    private class Panel(context: Context, display: Display) : Presentation(context, display) {

        private lateinit var stats: TextView
        private lateinit var idleLabel: TextView
        private lateinit var rootView: LinearLayout
        /** Rows that only make sense with a game running; hidden in the library. */
        private val gameRows = mutableListOf<View>()
        private var ticking = false

        /** In XMB mode the panel takes the XMB's look: its colour, the system font, glass buttons. */
        private val xmb = com.armsx2.ui.xmb.XmbMode.active.value
        private val face: android.graphics.Typeface? = if (xmb) com.armsx2.ui.xmb.XmbFirmware.typeface() else null
        private val dp = context.resources.displayMetrics.density

        private val tick = object : Runnable {
            override fun run() {
                if (!ticking) return
                if (com.armsx2.ui.xmb.XmbMode.active.value != xmb) {
                    // XMB mode switched while it was up: built again in the other look.
                    ticking = false
                    handler.post { MainActivityRuntime.instance?.let { activity -> detach(); attach(activity) } }
                    return
                }
                updateStats()
                if (xmb) rootView.background?.invalidateSelf()
                handler.postDelayed(this, TICK_MS)
            }
        }

        /** White words in the XMB's font and shadow, at [sizeSp] and [alpha]. */
        private fun TextView.asXmbText(sizeSp: Float, alpha: Float = 1f) {
            face?.let { typeface = it }
            textSize = sizeSp
            setTextColor(Color.argb((alpha * 255).toInt(), 255, 255, 255))
            setShadowLayer(6f, 0f, 2f, Color.argb(153, 0, 0, 0))
        }

        /** The XMB's glass button: a faint fill and a light edge, brighter while held. */
        private fun Button.asXmbButton() {
            fun glass(fill: Int) = GradientDrawable().apply {
                cornerRadius = 6 * dp
                setColor(fill)
                setStroke(maxOf(1, dp.toInt()), Color.argb(87, 255, 255, 255))
            }
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), glass(Color.argb(56, 255, 255, 255)))
                addState(intArrayOf(), glass(Color.argb(20, 255, 255, 255)))
            }
            stateListAnimator = null
            minHeight = (52 * dp).toInt()
            asXmbText(17f)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            // ★ A Presentation is a Dialog, so BACK dismissed it — and nothing re-showed it, since
            // the panel is only (re)created when a display is added or removed. Reported by Shane
            // [TDD]: "hit back on the bottom screen and I can't get it back". The panel is not a
            // dialog the user opened, so it should not be dismissable; the App-settings toggle and
            // unplugging the display are the ways out.
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            val pad = (resources.displayMetrics.density * 12).toInt()
            rootView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                if (xmb) background = XmbBackdrop() else setBackgroundColor(Color.BLACK)
                setPadding(pad, pad, pad, pad)
            }

            stats = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER_HORIZONTAL
                if (xmb) asXmbText(20f)
            }
            rootView.addView(stats, lp())

            // Shown in the library, where the game actions below would all be dead buttons.
            idleLabel = TextView(context).apply {
                text = I18n.get("secondScreen.noGame")
                setTextColor(Color.parseColor("#9AA0A6"))
                textSize = 14f
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, pad, 0, 0)
                if (xmb) asXmbText(16f, 0.75f)
            }
            rootView.addView(idleLabel, lp())

            // Two rows of actions, so a wide strip display stays readable.
            val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row1.addView(action(I18n.get("touch.stateAction.save")) {
                MainActivityRuntime.instance?.saveState()
            }, rowLp())
            row1.addView(action(I18n.get("touch.stateAction.load")) {
                MainActivityRuntime.instance?.loadState()
            }, rowLp())
            row2.addView(action(I18n.get("secondScreen.pause")) {
                // Toggle on the core's own state, not eState. Nothing ever sets eState to PAUSED
                // (the core does not report pauses back), so this button used to pause again on
                // every press and could never resume. Only an exact Paused resumes: the core's
                // Resume() ignores Frozen. Setting eState to PAUSED instead would wake the
                // stuck-paused backstop in MainActivityRuntime, which resumes any pause that has
                // no menu over it — including this one.
                val paused = com.armsx3.core.ARMSX3.getState() == com.armsx3.core.EmulatorState.Paused
                if (paused) MainActivityRuntime.resume() else MainActivityRuntime.pause()
            }, rowLp())
            row2.addView(action(I18n.get("touch.stateAction.screenshot")) {
                MainActivityRuntime.instance?.applicationContext?.let { Screenshots.capture(it) }
            }, rowLp())
            rootView.addView(row1, lp())
            rootView.addView(row2, lp())
            gameRows += row1
            gameRows += row2

            // On-screen macros (M1-M4), so the second screen can fire the same combos the touch
            // layout does — the natural home for them when the pad is on the main screen. Only the
            // macros that actually have buttons assigned are shown; an empty one is a no-op button.
            val macroRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            var macroCount = 0
            listOf(
                com.armsx2.ui.touch.TouchButtonId.MACRO1,
                com.armsx2.ui.touch.TouchButtonId.MACRO2,
                com.armsx2.ui.touch.TouchButtonId.MACRO3,
                com.armsx2.ui.touch.TouchButtonId.MACRO4,
            ).forEach { id ->
                if (runCatching { com.armsx2.ui.touch.TouchControls.macroCodes(id).isEmpty() }
                        .getOrDefault(true)
                ) return@forEach
                macroCount++
                macroRow.addView(macroAction(id), rowLp())
            }
            if (macroCount > 0) {
                rootView.addView(macroRow, lp())
                gameRows += macroRow
            }

            setContentView(rootView)
            updateStats()
        }

        /** A macro button: press fires every assigned pad button (honouring its turbo Frequency),
         *  release drops them — the same fireMacro path the on-screen macro widget uses. */
        private fun macroAction(id: com.armsx2.ui.touch.TouchButtonId): View =
            Button(context).apply {
                text = id.label
                isAllCaps = false
                if (xmb) asXmbButton()
                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            runCatching {
                                com.armsx2.ui.touch.TouchControls.fireMacro(id, "secondScreen", true) { code, pressed ->
                                    NativeApp.setPadButton(code, 0, pressed)
                                }
                            }
                            v.isPressed = true
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            runCatching {
                                com.armsx2.ui.touch.TouchControls.fireMacro(id, "secondScreen", false) { code, pressed ->
                                    NativeApp.setPadButton(code, 0, pressed)
                                }
                            }
                            v.isPressed = false
                            v.performClick()
                        }
                    }
                    true
                }
            }

        private fun lp() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        private fun rowLp() = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ).apply {
            // Glass buttons need air between them; the stock ones bring their own inset.
            if (xmb) (6 * dp).toInt().let { setMargins(it, it, it, it) }
        }

        private fun action(label: String, onClick: () -> Unit): View =
            Button(context).apply {
                text = label
                isAllCaps = false
                if (xmb) asXmbButton()
                setOnClickListener { runCatching { onClick() } }
            }

        private fun updateStats() {
            // In the library there is no VM, so save/load/pause/FF/screenshot and the macros are
            // all dead buttons — hide them and say so rather than showing controls that do nothing.
            val inGame = MainActivityRuntime.eState.value == EmuState.RUNNING ||
                MainActivityRuntime.eState.value == EmuState.PAUSED
            gameRows.forEach { it.visibility = if (inGame) View.VISIBLE else View.GONE }
            idleLabel.visibility = if (inGame) View.GONE else View.VISIBLE

            val fps = runCatching { NativeApp.getFPS() }.getOrDefault(0f)
            val title = MainActivityRuntime.currentGame.value?.title.orEmpty()
            // Read charge straight from BatteryManager rather than plumbing state over from the
            // main-display status cluster — this panel ticks on its own and the call is cheap.
            val battery = runCatching {
                (context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager)
                    ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            }.getOrDefault(-1)
            // Charging state, so the icon can show a bolt rather than a misleading empty cell.
            val charging = runCatching {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                bm?.isCharging == true
            }.getOrDefault(false)
            val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(System.currentTimeMillis()))
            stats.text = buildString {
                if (title.isNotBlank()) append(title).append('\n')
                // FPS is meaningless with no VM — the reading would just sit at the last value.
                if (inGame) {
                    append("FPS ").append(String.format(java.util.Locale.US, "%.1f", fps))
                    // Carrying the OSD for the game screen: add the speed vs the game's own target,
                    // so the panel replaces what was hidden up top rather than showing less.
                    if (moveOsd.value) {
                        val nominal = runCatching { NativeApp.getNominalFrameRate() }.getOrDefault(0f)
                        if (nominal > 1f) {
                            append("   ").append((fps / nominal * 100f).toInt()).append('%')
                        }
                        if (runCatching { MainActivityRuntime.isFastForwardActive() }
                                .getOrDefault(false)
                        ) append("   ▶▶")
                    }
                }
                if (battery >= 0) {
                    if (inGame) append("   ")
                    append(batteryIcon(battery, charging)).append(' ').append(battery).append('%')
                }
                append("   ").append(clock)
            }
        }

        /** A battery ICON that tracks the level, not just a number (asked for on the panel).
         *  Uses the block glyphs rather than an emoji so it renders in the same weight as the
         *  surrounding text on every device, and a bolt while charging. */
        private fun batteryIcon(pct: Int, charging: Boolean): String = when {
            charging -> "⚡"
            pct >= 80 -> "▰▰▰▰"
            pct >= 60 -> "▰▰▰▱"
            pct >= 40 -> "▰▰▱▱"
            pct >= 20 -> "▰▱▱▱"
            else -> "▱▱▱▱"
        }

        /** Belt-and-braces with setCancelable(false): some OEM shells still route BACK here. */
        @Deprecated("Dialog.onBackPressed", ReplaceWith(""))
        override fun onBackPressed() {
            // Intentionally nothing — the panel is not dismissable from the second screen.
        }

        override fun onStart() {
            super.onStart()
            ticking = true
            handler.post(tick)
        }

        override fun onStop() {
            ticking = false
            handler.removeCallbacks(tick)
            super.onStop()
        }
    }
}
