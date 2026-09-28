package com.armsx2

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import com.armsx2.input.PadRouter
import com.armsx2.runtime.MainActivityRuntime

/**
 * What kind of controller each port claims to be, per game.
 *
 * The PS3 asks through cellPadPeriphGetInfo, and instrument titles will not start until they
 * get an answer they recognise. Every port used to answer "standard pad" unconditionally, which
 * put Guitar Hero, Rock Band, DJ Hero and dance mat games out of reach with no way to say
 * otherwise. Desktop RPCS3 has always had this, as the Device Class dropdown in its gamepad
 * dialog; it lives in the INPUT config rather than the core one, which is why it never appeared
 * under All Core Settings either.
 *
 * ## Why per game
 *
 * It was one setting for the whole emulator, so the Guitar a player set for Guitar Hero stayed
 * on for everything after it. Other games then saw a guitar in port 1: Ratchet & Clank ToD was
 * told it had a Guitar Hero controller, and the savestates made in that session carried the
 * guitar with them. Nothing an instrument needs applies to any other game, so the value belongs
 * to the game, and every game without one gets a standard pad.
 *
 * ## Why the app owns the value
 *
 * The core writes it to Default.yml, but initApp saves g_cfg_input at startup before
 * pad_thread::Init has loaded it, so the file is overwritten with defaults on every launch.
 * Whatever is stored here is therefore the truth: the game's set is pushed into the core just
 * before it boots, so its pads are built right from the first frame, and again on every change.
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

    private fun key(game: String, port: Int) = "pad.deviceClass.$game.$port"

    /** The old emulator-wide values, "pad.deviceClass.<port>". Dropped: see the class comment. */
    private fun legacyKey(port: Int) = "pad.deviceClass.$port"

    /** The game [classes] belong to: the one booting or running, null in the library. */
    private var activeGame: String? = null

    /** One entry per port for the active game, so a Compose row can read it directly. */
    val classes = mutableStateListOf<Int>().apply {
        repeat(PadRouter.MAX_PADS) { add(0) }
    }

    /** Bumped on every change, so rows reading another game's value from prefs recompose. */
    private val revision = mutableIntStateOf(0)

    fun load() {
        runCatching {
            val prefs = MainActivityRuntime.prefs
            val stale = (0 until PadRouter.MAX_PADS).map(::legacyKey).filter(prefs::contains)
            if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { remove(it) } }.apply()
        }
        forGame(null)
    }

    /**
     * Make [game]'s classes the live ones and hand them to the core. Called just before a game
     * boots, with its settings key, and with null on the way back to the library.
     */
    fun forGame(game: String?) {
        activeGame = game?.takeIf { it.isNotBlank() }
        for (port in 0 until PadRouter.MAX_PADS) classes[port] = read(activeGame, port)
        push()
    }

    /** The active game's class for [port]. Standard in the library. */
    fun get(port: Int): Int = classes.getOrElse(port) { 0 }

    /**
     * Set the running game's class for [port]. Does nothing in the library, where there is no game.
     * A launch from outside the app can boot before it has a game identity (it is adopted a moment
     * later from the core's serial), so the key is picked up here if it arrived after the boot.
     */
    fun set(port: Int, value: Int) {
        if (activeGame == null) activeGame = MainActivityRuntime.currentGame.value?.settingsKey?.takeIf { it.isNotBlank() }
        set(activeGame ?: return, port, value)
    }

    /** [game]'s class for [port], whether or not it is running (the settings screen's Game scope). */
    fun get(game: String, port: Int): Int {
        revision.intValue
        return if (game == activeGame) get(port) else read(game, port)
    }

    fun set(game: String, port: Int, value: Int) {
        if (port !in 0 until PadRouter.MAX_PADS || game.isBlank()) return
        val v = value.coerceIn(0, LABEL_KEYS.size - 1)
        runCatching {
            MainActivityRuntime.prefs.edit().apply {
                // Standard is the default, so it is stored as nothing at all.
                if (v == 0) remove(key(game, port)) else putInt(key(game, port), v)
            }.apply()
        }
        revision.intValue++
        if (game == activeGame) {
            classes[port] = v
            push()
        }
    }

    private fun read(game: String?, port: Int): Int {
        if (game == null) return 0
        return runCatching { MainActivityRuntime.prefs.getInt(key(game, port), 0) }
            .getOrDefault(0).coerceIn(0, LABEL_KEYS.size - 1)
    }

    /**
     * Hand the whole set to the core.
     *
     * All ports at once because the core has to rebuild its pads for a change to take effect,
     * and doing that per port would tear the pad thread down several times over. The core
     * ignores the call when nothing actually differs, so this is cheap to call at every boot.
     */
    fun push() {
        runCatching {
            net.rpcsx.RPCSX.instance.setPadDeviceClasses(classes.toIntArray())
        }
    }
}
