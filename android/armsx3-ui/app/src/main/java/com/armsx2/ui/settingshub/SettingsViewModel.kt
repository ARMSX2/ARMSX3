package com.armsx2.ui.settingshub

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.armsx2.EmuState
import com.armsx2.GameInfo
import com.armsx2.config.ConfigStore
import com.armsx2.config.Settings
import com.armsx2.config.SettingsScope
import com.armsx2.navigation.SettingsCategory
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.InGameOverlay

data class SettingsUiState(
    val category: SettingsCategory = SettingsCategory.General,
    val game: GameInfo? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    var uiState = androidx.compose.runtime.mutableStateOf(SettingsUiState())
        private set

    val settings get() = InGameOverlay.settingsState

    fun load(category: SettingsCategory, game: GameInfo?) {
        // Before the first read, so this screen shows the settings the game actually has
        // rather than global, and then saves over the top of them.
        game?.let { ConfigStore.adoptLegacyOverrides(it.settingsKey, it.fileStemKey) }
        val serial = game?.settingsKey
        InGameOverlay.currentSerial.value = serial
        InGameOverlay.settingsScope.value = if (serial == null) SettingsScope.Global else SettingsScope.Game
        settings.value = if (serial == null) ConfigStore.loadGlobal() else ConfigStore.resolveForGame(serial)
        // General is the generic "open settings" entry every menu uses, so reopen on the category
        // last shown instead (ARMSX2 #729): changing a setting, trying it in the game and coming
        // back used to land on General every time. A caller that asks for a specific category
        // (the in-game Skins shortcut) still gets it. A remembered tab this scope doesn't show
        // (Info and Mods need a game, About has none) gives way to General.
        val remembered = lastCategory?.takeIf { category == SettingsCategory.General && shownIn(it, game != null) }
        val start = remembered ?: category
        val shown = if (game != null && start == SettingsCategory.General) SettingsCategory.Performance else start
        lastCategory = shown
        uiState.value = SettingsUiState(category = shown, game = game)
    }

    fun selectCategory(category: SettingsCategory) {
        // Nav tick when flipping to a different settings tab (controller bumpers, tap, or search jump).
        if (category != uiState.value.category) com.armsx2.MenuSfx.play(com.armsx2.MenuSfx.Event.NAV)
        uiState.value = uiState.value.copy(category = category)
        lastCategory = category
    }

    private companion object {
        /** The category last shown, for the life of the process. Not in the view-model's own state:
         *  the library's settings route gets a fresh view-model on every visit. */
        var lastCategory: SettingsCategory? = null

        /** Whether the tab row shows [category] with or without a game, as SettingsCategoryBar
         *  filters it. */
        fun shownIn(category: SettingsCategory, forGame: Boolean): Boolean = when (category) {
            SettingsCategory.Info, SettingsCategory.Mods -> forGame
            SettingsCategory.About -> !forGame
            else -> true
        }
    }

    /**
     * Reset ONLY the tab currently being shown.
     *
     * This used to reset the entire scope — `Settings()` globally, or clearing the whole
     * per-game override blob — so pressing Reset on the Renderer page also wiped Audio,
     * Network, Performance and Fixes. (Controller settings survived only because they live
     * in ControllerMappings, not because Reset was scoped.) Categories that own no Settings
     * fields are a no-op; Controls keeps its own reset row for binds/tunables.
     */
    fun resetCurrentScope(category: SettingsCategory) {
        // Takes the category the SCREEN is showing, not uiState.category: the screen remaps
        // General -> Performance under a game scope, so reading it here would reset the wrong
        // (or no) tab.
        if (!categoryHasResettableSettings(category)) return
        // The running game's resolved settings before the reset, so the live push below writes
        // only what the reset changed for it (Settings.applyChangesSince).
        val runningKey = com.armsx2.ui.InGameOverlay.currentSerial.value
        val runningBefore = if (MainActivityRuntime.nativeReady.value &&
            MainActivityRuntime.eState.value != EmuState.STOPPED
        ) runCatching { ConfigStore.resolveForGame(runningKey) }.getOrNull() else null
        val serial = uiState.value.game?.settingsKey
        if (serial != null) {
            // Per-game: drop just this tab's override keys, so those settings fall back to
            // global while every other per-game tweak the user made is preserved.
            ConfigStore.loadOverrides(serial)?.let { overrides ->
                val pruned = pruneOverrides(overrides, categoryOverrideKeys(category))
                if (pruned == null) ConfigStore.clearOverrides(serial)
                else ConfigStore.saveOverrides(serial, pruned)
            }
            settings.value = ConfigStore.resolveForGame(serial)
        } else {
            settings.value = settings.value.resetCategory(category)
            ConfigStore.saveGlobal(settings.value)
        }
        // Push the reset into the emulator's native config + per-game INI. Pruning the override
        // JSON above only updates the on-screen values and the store; the native per-game INI
        // (gamesettings/<serial>_<CRC>.ini) still holds the pruned keys, and VMManager reloads the
        // game layer from it on every commit/boot so the stale keys keep winning — which is why
        // Reset "did nothing". Rewriting that INI clears the tab's keys while preserving the
        // [Patches]/[Cheats] enable lists.
        val running = MainActivityRuntime.nativeReady.value &&
            MainActivityRuntime.eState.value != EmuState.STOPPED
        val gameSerial = serial?.takeIf { it.isNotBlank() }
        runCatching {
            when {
                // In-game: re-apply live so the change shows immediately, then regenerate the
                // running game's INI for the next boot (mirrors InGameOverlay.saveSettings).
                gameSerial != null && running -> {
                    pushToRunningGame(runningKey, runningBefore)
                    ConfigStore.resolveForGame(gameSerial).writeGameSettingsIni(ConfigStore.loadGlobal())
                }
                // From the library (no VM): the INI can't be reached through a running game, so
                // rewrite it by serial. A no-op when the game never wrote one — then the pruned
                // JSON alone already resolves to global at the next boot.
                gameSerial != null -> ConfigStore.resolveForGame(gameSerial)
                    .writeGameSettingsIni(ConfigStore.loadGlobal(), gameSerial)
                // Global scope with a game live: re-apply the reset globals to the base layer.
                running -> pushToRunningGame(runningKey, runningBefore)
            }
        }
    }

    /** The running game's resolved settings now, written live as only what differs from [before].
     *  Without a [before] (none could be resolved) every key goes, as it always did. */
    private fun pushToRunningGame(runningKey: String?, before: com.armsx2.config.Settings?) {
        val after = ConfigStore.resolveForGame(runningKey)
        if (before != null) after.applyChangesSince(before) else after.applyTo()
    }
}
