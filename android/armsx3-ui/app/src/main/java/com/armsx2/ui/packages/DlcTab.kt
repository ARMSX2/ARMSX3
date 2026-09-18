package com.armsx2.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.Ps3Sfo
import com.armsx2.i18n.str
import com.armsx2.ui.settings.controllerFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcsx.RPCSX
import java.io.File

/** One game's add-ons, as the DLC tab lists them. */
private data class DlcGroup(val titleId: String, val name: String, val items: List<Ps3Sfo.Dlc>)

/**
 * Every game's installed add-ons in one place, the way the Updates tab lists title updates.
 *
 * They were only reachable mixed in with everything else: the licence list and the installed
 * titles on the Install tab hold game licences, add-on licences and game data side by side, with
 * nothing to say which is which. Found where [Ps3Sfo.installedDlc] finds them, so a game's own
 * licence is never listed as one of its add-ons.
 *
 * Nothing here downloads anything. An add-on comes from a package the user already owns,
 * installed from the Install tab like any other package.
 */
@Composable
fun DlcTab() {
    var revision by remember { mutableIntStateOf(0) }
    val groups by produceState<List<DlcGroup>?>(null, revision) {
        value = withContext(Dispatchers.IO) { readDlcGroups() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            str("packages.dlc.description"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val found = groups
        when {
            found == null -> Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            found.isEmpty() -> Text(
                str("packages.dlc.none"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            else -> found.forEach { group ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            group.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (group.name != group.titleId) {
                            Text(
                                group.titleId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DlcRows(group.items) { revision++ }
                    }
                }
            }
        }
    }
}

/**
 * Every title that has add-ons, each with its own. The candidates come from the two places an
 * add-on can be told apart from its game (see [Ps3Sfo.installedDlc]): the title id inside each
 * licence file's content id, and the TITLE_ID of any folder that is not named for itself.
 */
private fun readDlcGroups(): List<DlcGroup> {
    val hdd0 = File(RPCSX.getHdd0Dir())
    val ids = linkedSetOf<String>()

    File(hdd0, "home").listFiles()?.filter { it.isDirectory }?.forEach { user ->
        File(user, "exdata").listFiles()
            ?.filter { it.isFile && (it.extension.equals("rap", true) || it.extension.equals("edat", true)) }
            ?.forEach { licence ->
                // UP0700-NPUB30910_00-XXXXXXXXXXXXXXXX: the title id sits between the dash and the underscore.
                licence.nameWithoutExtension.substringAfter('-', "").substringBefore('_')
                    .takeIf { it.length == 9 && it.all(Char::isLetterOrDigit) }
                    ?.let { ids += it.uppercase() }
            }
    }

    File(hdd0, "game").listFiles()?.filter { it.isDirectory }?.forEach { dir ->
        Ps3Sfo.read(File(dir, "PARAM.SFO"))["TITLE_ID"]
            ?.takeIf { !it.equals(dir.name, ignoreCase = true) }
            ?.let { ids += it.uppercase() }
    }

    return ids.mapNotNull { id ->
        val installed = Ps3Sfo.installDir(id)
        // A PSN game's own licence carries its title id too; leave it out (see Ps3Sfo.installedDlc).
        val own = if (id.startsWith("N") && installed != null) {
            runCatching { RPCSX.instance.gameContentId(installed.absolutePath) }.getOrNull().orEmpty()
        } else {
            ""
        }
        val items = Ps3Sfo.installedDlc(id, own).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val name = installed?.let { Ps3Sfo.read(File(it, "PARAM.SFO"))["TITLE"]?.trim() }?.takeIf { it.isNotEmpty() } ?: id
        DlcGroup(id, name, items)
    }.sortedBy { it.name.lowercase() }
}

/**
 * Add-on rows with a Remove button each, shared by the DLC tab and a game's Info tab. Two presses
 * rather than a dialog: a Compose dialog owns its own window and swallows pad keys, and both
 * screens are used with a controller.
 */
@Composable
internal fun DlcRows(items: List<Ps3Sfo.Dlc>, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var armed by remember(items) { mutableStateOf<String?>(null) }
    var busy by remember(items) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (item in items) {
            val key = item.file.absolutePath
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        str(if (item.isLicence) "info.dlc.licence" else "info.dlc.content"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val remove: () -> Unit = {
                    if (!busy) {
                        if (armed != key) {
                            armed = key
                        } else {
                            busy = true
                            // A content folder can be hundreds of MB, so not on the main thread.
                            scope.launch {
                                withContext(Dispatchers.IO) { Ps3Sfo.removeDlc(item) }
                                armed = null
                                busy = false
                                onChanged()
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = remove,
                    enabled = !busy,
                    modifier = Modifier.controllerFocusable("dlc.remove.$key", onConfirm = remove),
                ) { Text(str(if (armed == key) "info.dlc.confirmRemove" else "info.dlc.remove")) }
            }
        }
    }
}
