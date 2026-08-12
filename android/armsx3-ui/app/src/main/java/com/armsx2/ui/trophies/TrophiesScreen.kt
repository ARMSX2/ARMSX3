package com.armsx2.ui.trophies

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.armsx2.data.trophies.TrophyRepository
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.ArmsTopBar
import com.armsx2.ui.common.EmptyState
import com.armsx2.ui.common.RoundAction
import com.armsx2.ui.common.SettingSwitchRow
import com.armsx2.ui.common.StatusChip
import com.armsx2.ui.settings.controllerFocusable
import com.armsx2.ui.theme.Success

/**
 * PS3 trophies, browsable from the library — RPCS3's own native trophy data, not
 * RetroAchievements (RA has no PS3 sets at all, which is why that screen is hidden here).
 *
 * WHY THIS EXISTS ALONGSIDE THE NATIVE OVERLAY. RPCS3 already has a trophy list of its own
 * (Emu/RSX/Overlays/Trophies/overlay_trophy_list_dialog.cpp) and it works on Android: the
 * home menu grows a Trophies item once a game calls sceNpTrophyRegisterContext. But that
 * list is reachable only from inside a running game and only ever shows THAT game's set —
 * `current_trophy_name` is what the home menu passes it. Browsing across titles was Qt-only
 * (rpcs3qt/trophy_manager_dialog.cpp), so on Android there was no way to see a set without
 * booting its game. This screen is that missing manager, not a reimplementation of the
 * in-game list; the in-game path is left to the native overlay.
 *
 * It also shows the unlock DATE, which the native overlay reads but never displays (it only
 * sorts by the timestamp).
 *
 * @param currentGameOnly scope the screen to the RUNNING game's set. Used by the in-game
 *   menu; the library's own Trophies screen leaves it false and lists everything.
 */
@Composable
fun TrophiesScreen(
    onBack: () -> Unit,
    currentGameOnly: Boolean = false,
    viewModel: TrophiesViewModel = viewModel(),
) {
    val state = viewModel.state.value
    LaunchedEffect(currentGameOnly) { viewModel.refresh(currentGameOnly) }

    val totalTrophies = state.games.sumOf { it.total }
    val totalUnlocked = state.games.sumOf { it.unlocked }

    ArmsBackdrop {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ArmsTopBar(
                title = str("trophies.title"),
                subtitle = when {
                    state.loading -> str("trophies.loading")
                    state.games.isEmpty() -> null
                    // Scoped to one game: "across 1 game" is noise, so name the game instead.
                    currentGameOnly -> state.games.first().title
                    else -> I18n.get("trophies.overall")
                        .replace("%1", totalUnlocked.toString())
                        .replace("%2", totalTrophies.toString())
                        .replace("%3", state.games.size.toString())
                },
                leading = { RoundAction("←", str("action.back"), onBack) },
                // Must re-refresh in the SAME scope. A bare `viewModel::refresh` would take
                // the default and silently widen the in-game screen to every game.
                actions = {
                    RoundAction(
                        "↻",
                        str("games.card.refresh"),
                        onClick = { viewModel.refresh(currentGameOnly) },
                    )
                },
            )

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.games.isNotEmpty()) {
                    SettingSwitchRow(
                        title = str("trophies.showHidden"),
                        description = str("trophies.showHidden.desc"),
                        checked = state.showHidden,
                        onCheckedChange = viewModel::setShowHidden,
                    )
                }

                when {
                    state.loading && state.games.isEmpty() -> Box(
                        Modifier.fillMaxWidth().height(220.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    // Two different facts, so two different messages: "this game has none"
                    // is not "you have none".
                    state.games.isEmpty() -> EmptyState(
                        title = str(
                            if (currentGameOnly) "trophies.none.title" else "trophies.empty.title",
                        ),
                        message = str(
                            if (currentGameOnly) "trophies.none.body" else "trophies.empty.body",
                        ),
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                    )

                    else -> state.games.forEach { game ->
                        GameGroup(game, viewModel, singleGame = state.games.size == 1)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * One collapsible trophy set.
 *
 * Collapsed by default so the screen opens as a short list of games rather than a wall of
 * every trophy the user owns — the same grouping the package installer's licence list and
 * the cheats browser use. A lone set is expanded, since hiding one group behind a
 * disclosure costs a tap and saves nothing.
 */
@Composable
private fun GameGroup(
    game: TrophyRepository.Game,
    viewModel: TrophiesViewModel,
    singleGame: Boolean,
) {
    var expanded by remember(game.commId) { mutableStateOf(singleGame) }
    val toggle = { expanded = !expanded }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
            .controllerFocusable(
                controllerId = "trophies.game:${game.commId}",
                shape = RoundedCornerShape(18.dp),
                onConfirm = toggle,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = toggle).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrophyImage(game.icon, fallback = "🎮", size = 54)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    game.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    I18n.get("trophies.progress")
                        .replace("%1", game.unlocked.toString())
                        .replace("%2", game.total.toString())
                        .replace("%3", game.percent.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            // A platinum that is actually earned is the headline fact about a set, so it gets a
            // chip of its own rather than being one row among thirty.
            if (game.trophies.any { it.grade == TrophyRepository.Grade.Platinum && it.unlocked }) {
                StatusChip(str("trophies.grade.platinum"), GradePlatinum)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (expanded) "▴" else "▾",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (!expanded) return

    val trophies = viewModel.visibleTrophies(game)
    if (trophies.isEmpty()) {
        Text(
            str("trophies.allHidden"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(start = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        trophies.forEach { TrophyRow(it) }
    }
}

/**
 * One trophy row. Internal rather than private so the in-game menu's Trophies pane renders the
 * exact same row instead of a near-copy that would drift from this one.
 */
@Composable
internal fun TrophyRow(trophy: TrophyRepository.Trophy) {
    // A hidden trophy that has not been earned is masked, exactly as the native overlay masks
    // it: showing the name would defeat the point of the game hiding it. Once earned, the real
    // name and description are shown.
    val masked = trophy.hidden && !trophy.unlocked
    val name = if (masked) str("trophies.hidden.name") else trophy.name
    val description = if (masked) str("trophies.hidden.desc") else trophy.description

    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = if (trophy.unlocked) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(
            1.dp,
            if (trophy.unlocked) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Locked icons are desaturated, matching the native list (its image_info takes the
            // locked flag and greys the bitmap). A hidden one shows no icon at all, since the
            // artwork itself is usually a spoiler.
            TrophyImage(
                file = trophy.icon.takeUnless { masked },
                fallback = "🏆",
                size = 46,
                greyscale = !trophy.unlocked,
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name.ifBlank { "#${trophy.id}" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (trophy.unlocked) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description.isNotBlank()) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The unlock date the native overlay reads but never shows.
                trophy.unlockedAt?.let { at ->
                    Text(
                        I18n.get("trophies.earnedOn").replace("%s", formatDateTime(at)),
                        style = MaterialTheme.typography.labelSmall,
                        color = Success,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusChip(gradeLabel(trophy.grade), gradeColor(trophy.grade))
                StatusChip(
                    if (trophy.unlocked) str("trophies.earned") else str("trophies.notEarned"),
                    if (trophy.unlocked) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A trophy or game icon straight off the emulator's HDD, with a glyph fallback. */
@Composable
private fun TrophyImage(file: java.io.File?, fallback: String, size: Int, greyscale: Boolean = false) {
    val shape = RoundedCornerShape(11.dp)
    if (file == null) {
        Surface(Modifier.size(size.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(contentAlignment = Alignment.Center) { Text(fallback) }
        }
        return
    }
    AsyncImage(
        model = file,
        contentDescription = null,
        modifier = Modifier.size(size.dp).clip(shape).alpha(if (greyscale) 0.55f else 1f),
        contentScale = ContentScale.Crop,
        colorFilter = if (greyscale) {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        } else {
            null
        },
    )
}

// The four PS3 trophy metals. Fixed colours rather than theme roles: a bronze trophy is
// bronze-coloured on the console and in every other trophy list a user has seen.
private val GradeBronze = Color(0xFFCD7F32)
private val GradeSilver = Color(0xFFC0C4CC)
private val GradeGold = Color(0xFFE8B923)
private val GradePlatinum = Color(0xFF9AD5E8)

private fun gradeColor(grade: TrophyRepository.Grade): Color = when (grade) {
    TrophyRepository.Grade.Bronze -> GradeBronze
    TrophyRepository.Grade.Silver -> GradeSilver
    TrophyRepository.Grade.Gold -> GradeGold
    TrophyRepository.Grade.Platinum -> GradePlatinum
    TrophyRepository.Grade.Unknown -> GradeSilver
}

@Composable
private fun gradeLabel(grade: TrophyRepository.Grade): String = when (grade) {
    TrophyRepository.Grade.Bronze -> str("trophies.grade.bronze")
    TrophyRepository.Grade.Silver -> str("trophies.grade.silver")
    TrophyRepository.Grade.Gold -> str("trophies.grade.gold")
    TrophyRepository.Grade.Platinum -> str("trophies.grade.platinum")
    TrophyRepository.Grade.Unknown -> "?"
}

/** Device-locale short date and time, so the row reads the way the rest of the system does. */
private fun formatDateTime(millis: Long): String = java.text.DateFormat
    .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
    .format(java.util.Date(millis))
