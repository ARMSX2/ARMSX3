package com.armsx2.ui.mods

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.i18n.str
import com.armsx2.mods.ModImporter
import com.armsx2.mods.ModManager
import com.armsx2.ui.settings.SettingsDivider
import com.armsx2.ui.settings.controllerFocusable
import com.armsx2.ui.settings.ToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-game mod list.
 *
 * Everything the user does here is a file operation on their install, so the screen leads with
 * Leads with where mods go and when a change takes effect, because a switch whose result only
 * appears on the next launch is otherwise a switch that looks broken.
 */
@Composable
fun ModsTab(serial: String) {
    val scope = rememberCoroutineScope()
    var refreshToken by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var mods by remember { mutableStateOf<List<ModManager.Mod>>(emptyList()) }
    // A loose file that still needs somewhere to go. See the dialog at the end of this file.
    var pendingFile by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingPath by remember { mutableStateOf("") }
    var pendingMatches by remember { mutableStateOf<List<String>>(emptyList()) }

    val context = LocalContext.current
    val modsRoot = remember(serial, refreshToken) { ModManager.modsRoot(serial) }

    val importedFormat = str("mods.imported")

    fun handle(result: ModImporter.Result?) {
        if (result == null) {
            // Waiting on the destination dialog; not a failure and not a success yet.
            busy = false
            return
        }
        message = when (result) {
            is ModImporter.Result.Ok ->
                importedFormat.format(result.modName, result.fileCount) +
                    (result.warning?.let { "\n\n$it" } ?: "")
            is ModImporter.Result.Failed -> result.reason
        }
        busy = false
        refreshToken++
    }

    // Two pickers because people have mods in both shapes: a .zip straight off a mod site, or a
    // folder they already unpacked. Neither can be dropped into the store by hand, because
    // Android closes this app's data directory to file managers, so these are the only way in.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            handle(
                withContext(Dispatchers.IO) {
                    // A mod arrives as an archive or as a package, and which one is not worth
                    // asking the user about: the file says so. A .pkg is unpacked into the mod
                    // store rather than installed, so it stays something that can be turned off.
                    val name = androidx.documentfile.provider.DocumentFile
                        .fromSingleUri(context, uri)?.name.orEmpty()
                    val lower = name.lowercase()
                    when {
                        lower.endsWith(".pkg") -> ModImporter.importPkg(context, serial, uri)
                        lower.endsWith(".zip") -> ModImporter.importZip(context, serial, uri)
                        // A bare file carries no layout: nothing in patch.ff says it belongs in
                        // USRDIR/english. Ask, rather than fail with something unhelpful.
                        else -> {
                            // Ask the game where this belongs before asking the user. One answer
                            // and there is nothing to decide; several and the choice is real
                            // (Call of Duty keeps a patch.ff per language); none and it is the
                            // free-text case this started as.
                            val matches = ModManager.matchingPaths(serial, name)
                            pendingFile = uri
                            pendingMatches = matches
                            pendingPath = matches.singleOrNull() ?: "USRDIR/"
                            null
                        }
                    }
                },
            )
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            handle(withContext(Dispatchers.IO) { ModImporter.importFolder(context, serial, uri) })
        }
    }

    LaunchedEffect(serial, refreshToken) {
        mods = withContext(Dispatchers.IO) { ModManager.list(serial) }
    }

    Column(Modifier.fillMaxWidth()) {
        if (serial.isBlank()) {
            InfoCard(str("mods.noGame"))
            return@Column
        }

        // Stated up front for every game, not only the ones it rules out. Someone whose game
        // IS moddable still needs to know the rule before they go looking for a mod, and a
        // message that only appears on failure teaches nobody anything.
        InfoCard(str("mods.requirement"))
        Spacer(Modifier.height(8.dp))
        InfoCard(str("mods.backup"))
        Spacer(Modifier.height(10.dp))

        modsRoot?.let { root ->
            Text(
                text = str("mods.folder"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = root.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { if (!busy) filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f).controllerFocusable("mods.import.file"),
                enabled = !busy,
            ) { Text(str("mods.import.file")) }
            OutlinedButton(
                onClick = { if (!busy) folderPicker.launch(null) },
                modifier = Modifier.weight(1f).controllerFocusable("mods.import.folder"),
                enabled = !busy,
            ) { Text(str("mods.import.folder")) }
        }
        Spacer(Modifier.height(10.dp))

        message?.let {
            InfoCard(it)
            Spacer(Modifier.height(10.dp))
        }

        // ABOVE the empty-list early return, not below it.
        //
        // A game with no mods yet is exactly the game someone is importing their first mod into,
        // and the early return below meant the dialog could never compose for it: picking a file
        // set the state and nothing appeared. Same shape as the savestate wipe confirmation,
        // which was nested inside a message block and could only show while a message was
        // already up. A dialog belongs at the top level of the composable, gated on its own
        // state and nothing else.
        pendingFile?.let { uri ->
            val fileName = androidx.documentfile.provider.DocumentFile
                .fromSingleUri(context, uri)?.name.orEmpty()
            DestinationDialog(
                fileName = fileName,
                matches = pendingMatches,
                path = pendingPath,
                onPathChange = { pendingPath = it },
                onDismiss = { pendingFile = null },
                onConfirm = {
                    val chosen = pendingPath
                    pendingFile = null
                    busy = true
                    scope.launch {
                        handle(
                            withContext(Dispatchers.IO) {
                                ModImporter.importFile(context, serial, uri, chosen)
                            },
                        )
                    }
                },
            )
        }

        if (mods.isEmpty()) {
            InfoCard(str("mods.empty"))
            return@Column
        }

        mods.forEachIndexed { index, mod ->
            if (index > 0) SettingsDivider()
            ToggleRow(
                label = mod.name,
                value = mod.enabled,
                description = str("mods.fileCount").format(mod.fileCount),
            ) { wanted ->
                if (busy) return@ToggleRow
                busy = true
                message = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        ModManager.setEnabled(serial, mod.name, wanted)
                    }
                    if (result is ModManager.Result.Failed) message = result.reason
                    busy = false
                    refreshToken++
                }
            }
        }
    }
}

@Composable
private fun DestinationDialog(
    fileName: String,
    matches: List<String>,
    path: String,
    onPathChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str("mods.where.title")) },
        text = {
            Column {
                Text(
                    if (matches.isEmpty()) {
                        str("mods.where.body").format(fileName)
                    } else {
                        str("mods.where.found").format(fileName)
                    },
                )
                Spacer(Modifier.height(10.dp))

                // Tappable, because the whole point is to save typing a path that has to be
                // exact. The field stays editable underneath for anything not listed.
                matches.forEach { candidate ->
                    Surface(
                        onClick = { onPathChange(candidate) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .controllerFocusable("mods.where.$candidate", onConfirm = { onPathChange(candidate) }),
                        shape = RoundedCornerShape(12.dp),
                        color = if (candidate == path) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                    ) {
                        Text(
                            text = candidate,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = path,
                    onValueChange = onPathChange,
                    singleLine = true,
                    label = { Text(str("mods.where.label")) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(str("action.ok")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str("action.cancel")) } },
    )
}

@Composable
private fun InfoCard(text: String) {
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
