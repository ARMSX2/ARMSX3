package com.armsx2

import androidx.compose.runtime.mutableStateListOf
import com.armsx2.input.PadRouter
import com.armsx2.runtime.MainActivityRuntime

/**
 * What kind of controller each port claims to be.
 *
 * The PS3 asks through cellPadPeriphGetInfo, and instrument titles will not start until they
 * get an answer they recognise. Every port used to answer "standard pad" unconditionally, which
 * put Guitar Hero, Rock Band, DJ Hero and dance mat games out of reach with no way to say
 * otherwise. Desktop RPCS3 has always had this, as the Device Class dropdown in its gamepad
 * dialog; it lives in the INPUT config rather than the core one, which is why it never appeared
 * under All Core Settings either.
 *
 * ## Why the app owns the value
 *
 * The core writes it to Default.yml, but initApp saves g_cfg_input at startup before
 * pad_thread::Init has loaded it, so the file is overwritten with defaults on every launch.
 * Whatever is stored here is therefore the truth, and it is pushed into the core once the
 * emulator is up and again on every change.
 */
object PadDeviceClass {

    /** Matches CELL_PAD_PCLASS_TYPE_* in pad_types.h. Order is the order shown in the UI. */
    val LABEL_KEYS = listOf(
        "pad.deviceClass.standard",
        "pad.deviceClass.guitar",
        "pad.deviceClass.drum",
        "pad.deviceClass.dj",
        "pad.deviceClass.danceMat",
    )

    private fun key(port: Int) = "pad.deviceClass.$port"

    /** One entry per port, so a Compose row can read it directly. */
    val classes = mutableStateListOf<Int>().apply {
        repeat(PadRouter.MAX_PADS) { add(0) }
    }

    fun load() {
        for (port in 0 until PadRouter.MAX_PADS) {
            classes[port] = runCatching {
                MainActivityRuntime.prefs.getInt(key(port), 0)
            }.getOrDefault(0).coerceIn(0, LABEL_KEYS.size - 1)
        }
    }

    fun get(port: Int): Int = classes.getOrElse(port) { 0 }

    fun set(port: Int, value: Int) {
        if (port !in 0 until PadRouter.MAX_PADS) return
        val v = value.coerceIn(0, LABEL_KEYS.size - 1)
        classes[port] = v
        runCatching { MainActivityRuntime.prefs.edit().putInt(key(port), v).apply() }
        push()
    }

    /**
     * Hand the whole set to the core.
     *
     * All ports at once because the core has to rebuild its pads for a change to take effect,
     * and doing that per port would tear the pad thread down several times over. The core
     * ignores the call when nothing actually differs, so this is cheap to call on startup.
     */
    fun push() {
        runCatching {
            net.rpcsx.RPCSX.instance.setPadDeviceClasses(classes.toIntArray())
        }
    }
}
