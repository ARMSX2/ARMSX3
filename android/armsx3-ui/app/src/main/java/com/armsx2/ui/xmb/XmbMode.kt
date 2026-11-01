package com.armsx2.ui.xmb

import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.theme.LibraryBackgroundColorPreferences

/**
 * XMB mode: the library replaced by a recreation of the PS3's XrossMediaBar, entered from the
 * drawer ("XMB Mode", under Boot XMB) with the console's boot animation, ARMSX3 where it said PS3.
 *
 * It is remembered: an app closed in XMB mode starts in it again, and then the XMB boot plays in
 * place of the app's own intro video (BootSplashActivity reads [PREF_KEY] to skip it).
 */
object XmbMode {
    /** In the "ARMSX2" preferences, which BootSplashActivity opens directly before the runtime exists. */
    const val PREF_KEY = "xmb.mode.active"

    /** The XMB is shown in place of the library. */
    val active = mutableStateOf(false)

    /** The boot animation is playing. */
    val booting = mutableStateOf(false)

    private const val ColorKey = "xmb.theme.color"
    private const val BackgroundsKey = "xmb.theme.backgrounds"
    private const val IconsKey = "xmb.theme.icons"
    private const val GameMusicKey = "xmb.music.game"

    /** Theme Settings > Color: -1 follows the month, as the console's Default does; 0..11 keeps that month's. */
    val themeColor = mutableStateOf(-1)

    /** The GL thread's copy of [themeColor]. */
    @Volatile var pinnedMonth = -1
        private set

    /** A game's PIC1 behind the XMB while it is focused. */
    val gameBackgrounds = mutableStateOf(true)

    /** A game's ICON1 playing in place of its icon while it is focused. */
    val animatedIcons = mutableStateOf(true)

    /** A game's SND0 playing while it is focused. */
    val gameMusic = mutableStateOf(true)

    /** Restore the mode an earlier run was closed in. Once, at startup, before the first frame. */
    fun load() {
        val prefs = MainActivityRuntime.prefs
        val on = prefs.getBoolean(PREF_KEY, false)
        active.value = on
        booting.value = on
        LibraryBackgroundColorPreferences.xmbModeGl = on
        themeColor.value = prefs.getInt(ColorKey, -1).coerceIn(-1, 11)
        pinnedMonth = themeColor.value
        gameBackgrounds.value = prefs.getBoolean(BackgroundsKey, true)
        animatedIcons.value = prefs.getBoolean(IconsKey, true)
        gameMusic.value = prefs.getBoolean(GameMusicKey, true)
    }

    fun setThemeColor(month: Int) {
        val m = month.coerceIn(-1, 11)
        themeColor.value = m
        pinnedMonth = m
        XmbOriginalColors.invalidate()
        MainActivityRuntime.prefs.edit { putInt(ColorKey, m) }
    }

    fun setGameBackgrounds(on: Boolean) {
        gameBackgrounds.value = on
        MainActivityRuntime.prefs.edit { putBoolean(BackgroundsKey, on) }
    }

    fun setAnimatedIcons(on: Boolean) {
        animatedIcons.value = on
        MainActivityRuntime.prefs.edit { putBoolean(IconsKey, on) }
    }

    fun setGameMusic(on: Boolean) {
        gameMusic.value = on
        MainActivityRuntime.prefs.edit { putBoolean(GameMusicKey, on) }
    }

    /** Into XMB mode, boot animation first. */
    fun enter() {
        active.value = true
        booting.value = true
        LibraryBackgroundColorPreferences.xmbModeGl = true
        MainActivityRuntime.prefs.edit { putBoolean(PREF_KEY, true) }
        com.armsx2.navigation.UiNavigator.home()
    }

    /** Back to the regular library. */
    fun exit() {
        active.value = false
        booting.value = false
        LibraryBackgroundColorPreferences.xmbModeGl = false
        MainActivityRuntime.prefs.edit { putBoolean(PREF_KEY, false) }
    }

    fun bootFinished() {
        booting.value = false
    }
}
