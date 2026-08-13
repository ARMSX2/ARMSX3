package com.armsx2.ui.patches

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.Ps3PatchRepo
import com.armsx2.i18n.str
import com.armsx2.ui.settings.SettingsDivider
import com.armsx2.ui.settings.ToggleRow
import com.armsx2.ui.settings.controllerFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RPCS3 patches and graphics mods.
 *
 * Replaces ARMSX2's PNACH browser. The two are not the same shape at all: PNACH
 * is per-serial cheat text applied by PCSX2, while RPCS3 patches are keyed by
 * the PPU executable HASH and can carry graphics mods (60fps unlocks, resolution
 * and LOD changes, widescreen). The hash key is why a patch can apply to several
 * serials at once and why the database is one shared file.
 *
 * @param serial the game to filter to; empty lists everything.
 */
@Composable
fun Ps3PatchesTab(serial: String = "") {
    val scope = rememberCoroutineScope()
    var patches by remember { mutableStateOf<List<Ps3PatchRepo.Patch>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    // The full database is ~2000 patches. The host (SettingsScreen) wraps tab
    // content in a verticalScroll, so a LazyColumn cannot be nested here -- every
    // row would compose at once and the UI locks up. Filter first, then cap.
    val filtered = remember(patches, query) {
        if (query.isBlank()) patches
        else patches.filter {
            it.name.contains(query, true) || it.game.contains(query, true) ||
                it.author.contains(query, true)
        }
    }
    val shown = remember(filtered) { filtered.take(MAX_ROWS) }

    // Grouped by game for the global list; per-game scopes stay flat.
    val grouped = remember(filtered) {
        filtered.groupBy { it.game.ifBlank { "Unknown game" } }.toSortedMap()
    }
    var expanded by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            patches = withContext(Dispatchers.IO) { Ps3PatchRepo.list(serial) }
        }
    }

    val context = LocalContext.current
    // No MIME filter: patch.yml is served as text/plain, application/octet-stream or
    // nothing at all depending on where it came from, and filtering hides the file
    // the user is looking straight at.
    val patchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { Ps3PatchRepo.importLocal(context, uri) }
            busy = false
            message = when (r) {
                is Ps3PatchRepo.Result.Ok -> {
                    reload()
                    "${r.count} " + str2("patches.ps3.imported")
                }
                Ps3PatchRepo.Result.Parse -> str2("patches.ps3.parseFailed")
                else -> str2("patches.ps3.importFailed")
            }
        }
    }

    LaunchedEffect(serial) { reload() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            str("patches.ps3.header"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        OutlinedButton(
            onClick = {
                if (busy) return@OutlinedButton
                busy = true
                message = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Ps3PatchRepo.download() }
                    busy = false
                    message = when (r) {
                        is Ps3PatchRepo.Result.Ok -> {
                            reload()
                            "${r.count} " + str2("patches.ps3.imported")
                        }
                        // Named separately so a server or format problem does not
                        // send people to check their wifi.
                        Ps3PatchRepo.Result.Network -> str2("patches.ps3.downloadFailed")
                        is Ps3PatchRepo.Result.Server ->
                            str2("patches.ps3.serverError") + " (${r.code})"
                        Ps3PatchRepo.Result.Parse -> str2("patches.ps3.parseFailed")
                        Ps3PatchRepo.Result.Checksum -> str2("patches.ps3.checksumFailed")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .controllerFocusable("patches.download", RoundedCornerShape(13.dp)),
        ) {
            Text(str("patches.ps3.download"))
        }

        // Local import sits next to the download because the two produce the same
        // result -- patchesImport merges either source into patches/patch.yml.
        OutlinedButton(
            onClick = { if (!busy) patchPicker.launch(arrayOf("*/*")) },
            modifier = Modifier
                .fillMaxWidth()
                .controllerFocusable("patches.import", RoundedCornerShape(13.dp)),
        ) {
            Text(str("patches.ps3.importFile"))
        }

        if (busy) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text(
                    str("patches.ps3.downloading"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(12.dp))

        // Search is the only practical way through a 2000-entry list on a
        // handheld; per-game scopes are already short and are unaffected.
        if (serial.isBlank() && patches.size > MAX_ROWS) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(str("patches.ps3.search")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                str("patches.ps3.showing")
                    .replace("%1", shown.size.toString())
                    .replace("%2", filtered.size.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (patches.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (serial.isBlank()) str("patches.ps3.emptyAll")
                    else str("patches.ps3.emptyGame"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
            return@Column
        }

        // The core builds its patch map once, while the game loads, and writes
        // the patches into the executable as each module is loaded. Toggling
        // here only rewrites patch_config.yml, so a running game is unaffected
        // and the toggle looks broken without saying this.
        Text(
            str("patches.ps3.restartNeeded"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        @Composable
        fun patchRow(patch: Ps3PatchRepo.Patch) {
            ToggleRow(
                label = patch.name,
                value = patch.enabled,
                description = listOfNotNull(
                    patch.author.takeIf { it.isNotBlank() },
                    patch.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    patch.notes.takeIf { it.isNotBlank() },
                ).joinToString(" \u2022 ").ifBlank { null },
            ) { on ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        Ps3PatchRepo.setEnabled(patch, serial, on)
                    }
                    if (ok) reload() else message = str2("patches.ps3.toggleFailed")
                }
            }
            SettingsDivider()
        }

        if (serial.isNotBlank()) {
            // Already one game -- nothing to group by.
            shown.forEach { patchRow(it) }
        } else {
            // Grouped by game. A flat 2000-row list gives no way to tell which
            // patch belongs to what; desktop RPCS3 shows a game -> patches tree
            // for the same reason. Only the open game's rows compose, so the row
            // count stays small however large the database is.
            grouped.forEach { (game, list) ->
                val isOpen = expanded == game
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isOpen) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .controllerFocusable("patches.game.$game", RoundedCornerShape(12.dp)),
                    onClick = { expanded = if (isOpen) null else game },
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (isOpen) "\u25BE" else "\u25B8",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            game,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${list.count { it.enabled }}/${list.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isOpen) list.forEach { patchRow(it) }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** str() is @Composable; these are read from non-composable callbacks. */
private fun str2(key: String) = com.armsx2.i18n.I18n.get(key)

/** Rows rendered at once. The host scrolls, so this is a composition cap. */
private const val MAX_ROWS = 60
