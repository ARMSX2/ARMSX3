package com.armsx2.ui.trophies

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.armsx2.data.trophies.TrophyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrophiesUiState(
    val games: List<TrophyRepository.Game> = emptyList(),
    val loading: Boolean = true,
    /** Mirrors the native overlay's square-button toggle (HOME_MENU_TROPHY_SHOW_HIDDEN_TROPHIES):
     *  off by default, so an unearned hidden trophy does not spoil itself by existing. */
    val showHidden: Boolean = false,
    /** True when this instance is scoped to the running game (the in-game menu), so the UI can
     *  say "this game has no trophies" instead of "you have no trophies at all". */
    val currentGameOnly: Boolean = false,
)

class TrophiesViewModel(application: Application) : AndroidViewModel(application) {
    var state = androidx.compose.runtime.mutableStateOf(TrophiesUiState())
        private set

    /**
     * Rescan the trophy folders.
     *
     * All of it on Dispatchers.IO: a set is two file reads plus a stat per trophy icon, and a
     * library's worth of sets on the main thread is exactly the ANR the texture screen had.
     *
     * [currentGameOnly] narrows the scan to the running game's set (the in-game menu). The
     * default is the library-wide scan, so existing callers are unchanged.
     */
    fun refresh(currentGameOnly: Boolean = false) {
        viewModelScope.launch {
            state.value = state.value.copy(loading = true, currentGameOnly = currentGameOnly)
            val games = withContext(Dispatchers.IO) {
                runCatching {
                    if (currentGameOnly) {
                        listOfNotNull(TrophyRepository.loadCurrentGame())
                    } else {
                        TrophyRepository.load()
                    }
                }.getOrDefault(emptyList())
            }
            state.value = state.value.copy(games = games, loading = false)
        }
    }

    fun setShowHidden(show: Boolean) {
        state.value = state.value.copy(showHidden = show)
    }

    /** Trophies of [game] as the list should be shown, applying the hidden-trophy rule. */
    fun visibleTrophies(game: TrophyRepository.Game): List<TrophyRepository.Trophy> =
        game.trophies.filter { !(it.hidden && !it.unlocked) || state.value.showHidden }
}
