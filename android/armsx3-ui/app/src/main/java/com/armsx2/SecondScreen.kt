package com.armsx2

import android.app.Presentation
import android.content.Context
import android.graphics.BitmapFactory
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
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.mutableStateOf
import com.armsx2.i18n.I18n
import com.armsx2.runtime.MainActivityRuntime
import com.armsx3.NativeApp
import java.io.File

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

    /**
     * The panel itself. ARMSX3's mark at the top left, the time and battery at the top right, and in
     * the middle the game being played as the library shows it (its icon and title) with how it runs,
     * over Pause and Screenshot and any macros. With no game, the middle says so. Save and load are
     * left to the in-game menu, where their state is shown.
     *
     * In XMB mode it takes the XMB's look: its colour, its font, glass buttons and the clock's bar.
     * Otherwise it is plain: black, the app's font and the app's own buttons.
     */
    private class Panel(context: Context, display: Display) : Presentation(context, display) {

        private val xmb = com.armsx2.ui.xmb.XmbMode.active.value
        private val face: android.graphics.Typeface? = if (xmb) com.armsx2.ui.xmb.XmbFirmware.typeface() else null
        private val dp = context.resources.displayMetrics.density

        private lateinit var root: FrameLayout
        private lateinit var game: View
        private lateinit var idle: TextView
        private lateinit var icon: ImageView
        private lateinit var title: TextView
        private lateinit var running: TextView
        private lateinit var status: TextView
        private lateinit var pause: Button
        /** The game whose icon is up, so it is read once per game rather than every tick. */
        private var iconFor: String? = null
        private var ticking = false

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
                if (xmb) root.background?.invalidateSelf()
                handler.postDelayed(this, TICK_MS)
            }
        }

        private fun px(v: Float) = (v * dp).toInt()

        /** White words at [sizeSp] and [alpha]; in XMB mode in its font, with its shadow. */
        private fun TextView.words(sizeSp: Float, alpha: Float = 1f) {
            textSize = sizeSp
            setTextColor(Color.argb((alpha * 255).toInt(), 255, 255, 255))
            if (xmb) {
                face?.let { typeface = it }
                setShadowLayer(6f, 0f, 2f, Color.argb(153, 0, 0, 0))
            }
        }

        /** In XMB mode the XMB's glass button: a faint fill and a light edge, brighter while held. */
        private fun Button.styled() {
            isAllCaps = false
            if (!xmb) return
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
            minHeight = px(52f)
            words(17f)
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

            val metrics = context.resources.displayMetrics
            val widthDp = metrics.widthPixels / dp
            val heightDp = metrics.heightPixels / dp
            // The buttons' width, and the icon, fitted to the display: a strip-shaped second screen
            // has no room for the icon over the rest.
            val rowWidth = px(minOf(460f, widthDp - 48f))
            val iconWidth = minOf(300f, widthDp * 0.5f)

            root = FrameLayout(context).apply {
                if (xmb) background = XmbBackdrop() else setBackgroundColor(Color.BLACK)
            }

            // ARMSX3's mark, top left.
            root.addView(
                ImageView(context).apply { setImageBitmap(com.armsx2.ui.xmb.XmbWordmark.markIcon(px(48f))) },
                FrameLayout.LayoutParams(px(48f), px(48f), Gravity.TOP or Gravity.START).apply {
                    setMargins(px(22f), px(18f), 0, 0)
                },
            )

            // The time and battery, top right; in XMB mode in the clock's bar, open to the edge of
            // the screen as the XMB's is.
            status = TextView(context).apply {
                words(17f)
                if (xmb) {
                    setPadding(px(18f), px(6f), px(24f), px(6f))
                    background = GradientDrawable().apply {
                        val r = 4 * dp
                        cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                        setColor(Color.argb(20, 255, 255, 255))
                        setStroke(maxOf(1, dp.toInt()), Color.argb(87, 255, 255, 255))
                    }
                } else {
                    setPadding(0, 0, px(24f), 0)
                }
            }
            root.addView(
                status,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
                ).apply { topMargin = px(28f) },
            )

            // The game in the middle, as the library shows it.
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            if (heightDp >= 400f) {
                column.addView(icon, LinearLayout.LayoutParams(px(iconWidth), px(iconWidth * 176f / 320f)))
            }
            title = TextView(context).apply {
                words(24f)
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, px(12f), 0, 0)
            }
            column.addView(title, lp())
            running = TextView(context).apply {
                words(16f, 0.8f)
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, px(4f), 0, px(16f))
            }
            column.addView(running, lp())

            val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            pause = action(I18n.get("secondScreen.pause")) {
                // Toggle on the core's own state, not eState. Nothing ever sets eState to PAUSED
                // (the core does not report pauses back), so this button used to pause again on
                // every press and could never resume. Only an exact Paused resumes: the core's
                // Resume() ignores Frozen. Setting eState to PAUSED instead would wake the
                // stuck-paused backstop in MainActivityRuntime, which resumes any pause that has
                // no menu over it — including this one.
                val paused = com.armsx3.core.ARMSX3.getState() == com.armsx3.core.EmulatorState.Paused
                if (paused) MainActivityRuntime.resume() else MainActivityRuntime.pause()
            }
            actions.addView(pause, rowLp())
            actions.addView(action(I18n.get("secondScreen.screenshot")) {
                MainActivityRuntime.instance?.applicationContext?.let { Screenshots.capture(it) }
            }, rowLp())
            column.addView(actions, LinearLayout.LayoutParams(rowWidth, ViewGroup.LayoutParams.WRAP_CONTENT))

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
                column.addView(macroRow, LinearLayout.LayoutParams(rowWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            game = column
            root.addView(
                column,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                    .apply { setMargins(px(24f), px(64f), px(24f), px(16f)) },
            )

            // Shown in the library, where the buttons above would all be dead.
            idle = TextView(context).apply {
                text = I18n.get("secondScreen.idle")
                words(18f, 0.75f)
                gravity = Gravity.CENTER
            }
            root.addView(
                idle,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )

            setContentView(root)
            updateStats()
        }

        /** A macro button: press fires every assigned pad button (honouring its turbo Frequency),
         *  release drops them — the same fireMacro path the on-screen macro widget uses. */
        private fun macroAction(id: com.armsx2.ui.touch.TouchButtonId): View =
            Button(context).apply {
                text = id.label
                styled()
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
            if (xmb) px(6f).let { setMargins(it, it, it, it) }
        }

        private fun action(label: String, onClick: () -> Unit): Button =
            Button(context).apply {
                text = label
                styled()
                setOnClickListener { runCatching { onClick() } }
            }

        /** The game's ICON0, as the library and the XMB's game list show it, read off the main thread. */
        private fun loadIcon(current: GameInfo?) {
            val key = iconFor
            icon.setImageDrawable(null)
            icon.visibility = View.GONE
            if (current == null) return
            Thread {
                val bitmap = iconFile(current)?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
                handler.post {
                    if (iconFor != key || bitmap == null) return@post
                    icon.setImageBitmap(bitmap)
                    icon.visibility = View.VISIBLE
                }
            }.apply { isDaemon = true }.start()
        }

        /** Read off the disc when the library scanned it, else in the game's own folder or install. */
        private fun iconFile(current: GameInfo): File? =
            current.discIconFile
                ?: current.discGameDir?.let { File(it, "ICON0.PNG") }?.takeIf { it.isFile }
                ?: current.serial?.takeIf { it.isNotBlank() }
                    ?.let { File(com.armsx3.core.ARMSX3.rootDirectory, "config/dev_hdd0/game/$it/ICON0.PNG") }
                    ?.takeIf { it.isFile }

        private fun updateStats() {
            // In the library there is no VM, so pause, screenshot and the macros are all dead
            // buttons — hide them and say so rather than showing controls that do nothing.
            val inGame = MainActivityRuntime.eState.value == EmuState.RUNNING ||
                MainActivityRuntime.eState.value == EmuState.PAUSED
            game.visibility = if (inGame) View.VISIBLE else View.GONE
            idle.visibility = if (inGame) View.GONE else View.VISIBLE

            val current = MainActivityRuntime.currentGame.value
            title.text = current?.displayTitle(EnglishTitles.enabled.value).orEmpty()
            val key = current?.uri?.toString()
            if (key != iconFor) {
                iconFor = key
                loadIcon(current)
            }

            // Named for what a press does now, read from the core as the press reads it.
            val paused = inGame && runCatching { com.armsx3.core.ARMSX3.getState() == com.armsx3.core.EmulatorState.Paused }.getOrDefault(false)
            pause.text = I18n.get(if (paused) "action.resume" else "secondScreen.pause")

            val fps = runCatching { NativeApp.getFPS() }.getOrDefault(0f)
            running.text = buildString {
                append("FPS ").append(String.format(java.util.Locale.US, "%.1f", fps))
                // Carrying the OSD for the game screen: add the speed vs the game's own target,
                // so the panel replaces what was hidden up top rather than showing less.
                if (moveOsd.value) {
                    val nominal = runCatching { NativeApp.getNominalFrameRate() }.getOrDefault(0f)
                    if (nominal > 1f) append("   ").append((fps / nominal * 100f).toInt()).append('%')
                    if (runCatching { MainActivityRuntime.isFastForwardActive() }.getOrDefault(false)) append("   ▶▶")
                }
            }

            // Read charge straight from BatteryManager rather than plumbing state over from the
            // main-display status cluster — this panel ticks on its own and the call is cheap.
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val battery = runCatching { bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 }.getOrDefault(-1)
            val charging = runCatching { bm?.isCharging == true }.getOrDefault(false)
            val clock = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date())
            status.text = buildString {
                append(clock)
                if (battery >= 0) append("   ").append(batteryIcon(battery, charging)).append(' ').append(battery).append('%')
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
