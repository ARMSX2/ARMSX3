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

    /** Restore the mode an earlier run was closed in. Once, at startup, before the first frame. */
    fun load() {
        val on = MainActivityRuntime.prefs.getBoolean(PREF_KEY, false)
        active.value = on
        booting.value = on
        LibraryBackgroundColorPreferences.xmbModeGl = on
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
