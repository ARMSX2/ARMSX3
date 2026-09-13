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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

    val context = LocalContext.current
    val modsRoot = remember(serial, refreshToken) { ModManager.modsRoot(serial) }

    val importedFormat = str("mods.imported")

    fun handle(result: ModImporter.Result) {
        message = when (result) {
            is ModImporter.Result.Ok -> importedFormat.format(result.modName, result.fileCount)
            is ModImporter.Result.Failed -> result.reason
        }
        busy = false
        refreshToken++
    }

    // Two pickers because people have mods in both shapes: a .zip straight off a mod site, or a
    // folder they already unpacked. Neither can be dropped into the store by hand, because
    // Android closes this app's data directory to file managers, so these are the only way in.
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            handle(withContext(Dispatchers.IO) { ModImporter.importZip(context, serial, uri) })
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
                onClick = { if (!busy) zipPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f).controllerFocusable("mods.import.zip"),
                enabled = !busy,
            ) { Text(str("mods.import.zip")) }
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
