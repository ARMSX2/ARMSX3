package com.armsx2.ui.mods

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.GameInfo
import com.armsx2.data.library.GameLibraryRepository
import com.armsx2.i18n.str
import com.armsx2.mods.ModManager
import com.armsx2.navigation.AppRoute
import com.armsx2.navigation.SettingsCategory
import com.armsx2.navigation.UiNavigator
import com.armsx2.ui.common.ArmsTopBar
import com.armsx2.ui.common.RoundAction
import com.armsx2.ui.settings.controllerFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The library, filtered to games that can actually take mods.
 *
 * The per-game Mods tab is the place the work happens, but it is the last tab in a scrolling
 * strip behind a long press, which is a poor way to discover a feature exists. This is the
 * front door: open it and see which of your games are moddable and how many mods each one has,
 * without opening any of them.
 *
 * Filtered rather than greyed out. A disc image cannot be modded at all, and listing every
 * title with most of them permanently disabled would say "this feature is broken" far more
 * loudly than it would say "install this one from a package first".
 */
@Composable
fun ModLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<Row>>(emptyList()) }

    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.IO) {
            GameLibraryRepository(context).loadCached().games
                .mapNotNull { game ->
                    val serial = game.serial?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    // Tested on the ENTRY, not the serial: a disc game with an update
                    // installed has a dev_hdd0 folder too, and would otherwise be listed.
                    if (!ModManager.isModdable(game.extension, serial)) return@mapNotNull null
                    val mods = ModManager.list(serial)
                    Row(game, serial, mods.size, mods.count { it.enabled })
                }
                .distinctBy { it.serial }
                .sortedBy { it.game.title.lowercase() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ArmsTopBar(
            title = str("mods.library.title"),
            leading = { RoundAction("←", str("action.back"), onBack) },
        )

        // A plain scrolling Column, NOT a LazyColumn, and that is not laziness about laziness.
        // Every row is controllerFocusable, which registers itself for pad navigation on compose
        // and unregisters on dispose, and asks to be scrolled into view whenever it is selected.
        // A lazy list disposes rows as they leave the viewport, so off-screen games drop out of
        // pad navigation entirely, and a row recomposing while selected drags the list back under
        // the user's finger. The settings tab strip hit the same thing and solved it the same way.
        // The list is as long as a game library, which is tens of rows, so composing them all is
        // not a cost worth the bug.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Card(str("mods.requirement"))
            Card(str("mods.backup"))

            if (rows.isEmpty()) {
                Card(str("mods.library.empty"))
            } else {
                rows.forEach { row -> GameRow(row) }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

private data class Row(
    val game: GameInfo,
    val serial: String,
    val modCount: Int,
    val enabledCount: Int,
)

@Composable
private fun GameRow(row: Row) {
    val open = {
        UiNavigator.navigate(
            AppRoute.Settings(SettingsCategory.Mods, row.game, returnTo = AppRoute.ModLibrary),
        )
    }
    Surface(
        onClick = open,
        modifier = Modifier
            .fillMaxWidth()
            .controllerFocusable("mods.library.${row.serial}", onConfirm = open),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = row.game.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            Text(
                text = if (row.modCount == 0) {
                    str("mods.library.none")
                } else {
                    str("mods.library.count").format(row.modCount, row.enabledCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun Card(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}
