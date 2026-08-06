package net.rpcsx.ui.theme

import androidx.compose.runtime.mutableStateOf
import net.rpcsx.utils.GeneralSettings

/**
 * Colour state for the animated library backdrop.
 *
 * Ported from ARMSX2, trimmed to what the 2D Canvas wave needs. ARMSX2 also
 * drives a GLES3 XMB mesh from here (glTop/glBottom/rgbCycleGl read off the GL
 * thread); ARMSX3 ships only the Canvas wave for now, so those are omitted
 * rather than carried as dead fields.
 *
 * Default is purple rather than ARMSX2's XMB royal blue -- PS3 house colour,
 * matching the app theme.
 */
object LibraryBackgroundColorPreferences {
    private const val Key = "ui.library.bgColor"
    private const val RgbKey = "ui.library.bgRgb"

    /** Built-in wave colour, shown in the picker when unset. */
    const val DefaultDisplayColor: Int = 0xFF6A4BA8.toInt()

    /** Quick-pick swatches; first entry is the built-in default. */
    val PRESETS: List<Int> = listOf(
        0xFF6A4BA8.toInt(), // violet (default)
        0xFF9B59B6.toInt(), // purple
        0xFFD3BBFF.toInt(), // lavender
        0xFFE84393.toInt(), // magenta
        0xFF2E75F5.toInt(), // royal blue
        0xFF00B4D8.toInt(), // aqua
        0xFF16A085.toInt(), // teal
        0xFF19C37D.toInt(), // green
        0xFFE74C3C.toInt(), // red
        0xFFF39C12.toInt(), // orange
        0xFF95A5A6.toInt(), // silver
        0xFF34495E.toInt(), // slate
    )

    /** Packed ARGB. 0 = not customised, use [DefaultDisplayColor]. */
    val color = mutableStateOf(0)

    /** Cycle the hue wheel continuously, overriding the fixed colour. */
    val rgbCycle = mutableStateOf(false)

    fun load() {
        if (!GeneralSettings.isInitialized()) return
        color.value = GeneralSettings.raw.getInt(Key, 0)
        rgbCycle.value = GeneralSettings.raw.getBoolean(RgbKey, false)
    }

    /** Alpha is forced opaque -- a translucent backdrop colour just reads as mud. */
    fun set(argb: Int) {
        val v = if (argb == 0) 0 else (argb or (0xFF shl 24))
        color.value = v
        GeneralSettings.raw.edit().putInt(Key, v).apply()
    }

    fun setRgbCycle(enabled: Boolean) {
        rgbCycle.value = enabled
        GeneralSettings.raw.edit().putBoolean(RgbKey, enabled).apply()
    }

    fun reset() {
        color.value = 0
        GeneralSettings.raw.edit().remove(Key).apply()
    }
}
