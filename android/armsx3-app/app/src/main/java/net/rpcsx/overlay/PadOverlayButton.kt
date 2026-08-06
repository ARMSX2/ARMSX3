package net.rpcsx.overlay

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import net.rpcsx.Digital1Flags
import kotlin.math.roundToInt
import net.rpcsx.utils.GeneralSettings
import net.rpcsx.utils.GeneralSettings.boolean
import net.rpcsx.utils.InputBindingPrefs

class PadOverlayButton(resources: Resources, image: Bitmap, private val digital1: Int, private val digital2: Int) : PadOverlayItem, BitmapDrawable(resources, image) {
    private var pressed = false
    private var locked = -1
    private var origAlpha = alpha
    override var dragging = false
    private var offsetX = 0
    private var offsetY = 0
    private var scaleFactor = 0.5f
    private var opacity = alpha
    var defaultSize: Pair<Int, Int> = Pair(-1, -1)
    lateinit var defaultPosition: Pair<Int, Int>
    override fun bounds(): Rect = bounds
    override fun draw(canvas: Canvas) = super.draw(canvas)
    override var enabled: Boolean = GeneralSettings["button_${digital1}_${digital2}_enabled"].boolean(true)
        set(value) {
            field = value
            GeneralSettings.setValue("button_${digital1}_${digital2}_enabled", value)
        }
    
    override fun contains(x: Int, y: Int) = bounds.contains(x, y)

    override fun onTouch(event: MotionEvent, pointerIndex: Int, padState: State): Boolean {
        val action = event.actionMasked
        var hit = false
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (locked == -1) {
                locked = event.getPointerId(pointerIndex)
                pressed = true
                origAlpha = alpha
                alpha = 255
                hit = true
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            if (locked != -1 && (action == MotionEvent.ACTION_CANCEL || event.getPointerId(pointerIndex) == locked)) {
                pressed = false
                locked = -1
                alpha = origAlpha
                hit = true
            }
        }

        if (pressed) {
            padState.digital[0] = padState.digital[0] or digital1
            padState.digital[1] = padState.digital[1] or digital2
        } else {
            padState.digital[0] = padState.digital[0] and digital1.inv()
            padState.digital[1] = padState.digital[1] and digital2.inv()
        }

        return hit
    }

    override fun startDragging(startX: Int, startY: Int) {
        dragging = true
        offsetX = startX - bounds.left
        offsetY = startY - bounds.top
    }

    override fun updatePosition(x: Int, y: Int, force: Boolean) {
        if (dragging) {
            setBounds(x - offsetX, y - offsetY, x - offsetX + bounds.width(), y - offsetY + bounds.height())
            GeneralSettings.setValue("button_${digital1}_${digital2}_x", x - offsetX)
            GeneralSettings.setValue("button_${digital1}_${digital2}_y", y - offsetY)
        } else if (force) {
            // don't use offsets as we aren't dragging
            setBounds(x, y, x + bounds.width(), y + bounds.height())
            GeneralSettings.setValue("button_${digital1}_${digital2}_x", x)
            GeneralSettings.setValue("button_${digital1}_${digital2}_y", y)
        }
    }

    override fun stopDragging() {
        dragging = false
    }

    override fun setScale(percent: Int) {
        scaleFactor = percent / 100f
        val newWidth = (1024 * scaleFactor).roundToInt()
        val newHeight = (1024 * scaleFactor).roundToInt()
        setBounds(bounds.left, bounds.top, bounds.left + newWidth, bounds.top + newHeight)
        GeneralSettings.setValue("button_${digital1}_${digital2}_scale", percent)
    }

    override fun setOpacity(percent: Int) {
        opacity = (255 * (percent / 100f)).roundToInt()
        alpha = opacity
        GeneralSettings.setValue("button_${digital1}_${digital2}_opacity", percent)
    }

    fun measureDefaultScale(): Int {
        if (defaultSize.second <= 0 || defaultSize.first <= 0) return 100
        val widthScale = defaultSize.second.toFloat() / 1024 * 100
        val heightScale = defaultSize.first.toFloat() / 1024 * 100
        return minOf(widthScale, heightScale).roundToInt()
    }

    override fun resetConfigs() {
        setOpacity(50)
        setBounds(defaultPosition.first, defaultPosition.second, defaultPosition.first + defaultSize.second, defaultPosition.second + defaultSize.first)
        GeneralSettings.setValue("button_${digital1}_${digital2}_scale", null)
        GeneralSettings.setValue("button_${digital1}_${digital2}_opacity", null)
        GeneralSettings.setValue("button_${digital1}_${digital2}_x", null)
        GeneralSettings.setValue("button_${digital1}_${digital2}_y", null)
    }

    fun getInfo(): Triple<String, Int, Int> {
        val dn = if (digital1 == Digital1Flags.None.ordinal) 1 else 0
        return Triple(InputBindingPrefs.rpcsxKeyCodeToString(if (dn == 0) digital1 else digital2, dn), GeneralSettings["button_${digital1}_${digital2}_scale"] as Int? ?: measureDefaultScale(), GeneralSettings["button_${digital1}_${digital2}_opacity"] as Int? ?: 50)
    }
}
