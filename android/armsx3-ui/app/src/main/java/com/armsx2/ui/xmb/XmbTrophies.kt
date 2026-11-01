package com.armsx2.ui.xmb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import com.armsx2.data.trophies.TrophyRepository
import com.armsx2.i18n.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Trophy Collection as XMB lists, as the console keeps it: each game with its share earned and its
 * icon, and in it every trophy with its icon, grade and when it was earned. A hidden trophy not yet
 * earned keeps its name to itself. Over a game, the running game's trophies straight away. The same
 * trophy files the Trophies screen reads (TrophyRepository).
 */
object XmbTrophies {
    /** The sets as last read; null while reading. */
    val games = mutableStateOf<List<TrophyRepository.Game>?>(null)
}

@Composable
fun xmbTrophiesNode(inGame: Boolean): XmbNode {
    val context = LocalContext.current
    val title = fw("explore_plugin_full", "msg_trophy_collection", "trophies.title")
    val icon = XmbIcon("explore_category_psn", "item_tex_trophy", "🏆")
    val loading = str("trophies.loading")
    val emptyTitle = str(if (inGame) "trophies.none.title" else "trophies.empty.title")
    val emptyBody = str(if (inGame) "trophies.none.body" else "trophies.empty.body")
    val earnedOn = str("trophies.earnedOn")
    val earned = str("trophies.earned")
    val notEarned = str("trophies.notEarned")
    val hiddenName = str("trophies.hidden.name")
    val hiddenDesc = str("trophies.hidden.desc")
    val grades = mapOf(
        TrophyRepository.Grade.Bronze to str("trophies.grade.bronze"),
        TrophyRepository.Grade.Silver to str("trophies.grade.silver"),
        TrophyRepository.Grade.Gold to str("trophies.grade.gold"),
        TrophyRepository.Grade.Platinum to str("trophies.grade.platinum"),
    )

    fun date(at: Long?): String? = at?.let {
        android.text.format.DateUtils.formatDateTime(
            context,
            it,
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_ALL,
        )
    }

    fun trophies(g: TrophyRepository.Game): List<XmbNode> = g.trophies.map { t ->
        val secret = t.hidden && !t.unlocked
        val grade = grades[t.grade]
        val state = if (t.unlocked) date(t.unlockedAt)?.let { earnedOn.format(it) } ?: earned else notEarned
        XmbNode.Action(
            "trophy.${g.commId}.${t.id}",
            if (secret) hiddenName else t.name,
            if (t.icon != null && !secret) XmbIcon(file = t.icon, glyph = "🏆", shadow = false) else icon,
            listOfNotNull(grade, state, if (secret) hiddenDesc else t.description.takeIf { it.isNotBlank() }).joinToString("   "),
        ) {}
    }

    val reader: @Composable () -> Unit = {
        LaunchedEffect(Unit) {
            XmbTrophies.games.value = null
            XmbTrophies.games.value = withContext(Dispatchers.IO) {
                runCatching {
                    if (inGame) listOfNotNull(TrophyRepository.loadCurrentGame()) else TrophyRepository.load()
                }.getOrDefault(emptyList())
            }
        }
    }
    return XmbNode.Folder(if (inGame) "ingame.trophies" else "game.trophies", title, icon, live = true, host = reader) {
        val games = XmbTrophies.games.value
        when {
            games == null -> listOf(XmbNode.Action("trophies.loading", loading, icon) {})
            games.isEmpty() -> listOf(XmbNode.Action("trophies.empty", emptyTitle, icon, emptyBody) {})
            inGame && games.size == 1 -> trophies(games[0])
            else -> games.map { g ->
                XmbNode.Folder(
                    "trophies.${g.commId}",
                    g.title,
                    g.icon?.let { XmbIcon(file = it, glyph = "🏆") } ?: icon,
                    "${g.percent}%   ${g.unlocked} / ${g.total}",
                ) { trophies(g) }
            }
        }
    }
}
