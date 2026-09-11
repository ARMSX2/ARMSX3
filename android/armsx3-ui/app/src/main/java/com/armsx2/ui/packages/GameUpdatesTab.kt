package com.armsx2.ui.packages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.armsx2.data.library.GameLibraryRepository
import com.armsx2.i18n.I18n
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.updates.Ps3UpdateService
import kotlinx.coroutines.launch
import java.io.File

private fun str(key: String) = I18n.get(key)

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    bytes > 0 -> "%.0f KB".format(bytes / 1024.0)
    else -> "—"
}

/**
 * Find and install a title's official updates.
 *
 * Sits beside the file picker rather than on the info tab: the reason to want a patch is usually
 * that a game misbehaves, and "install something into the emulator" is what this screen is for.
 * Installing goes through [onInstall], the same path a hand-picked .pkg takes, so a downloaded
 * package gets the same extractor, progress and failure reporting as any other.
 */
@Composable
fun GameUpdatesTab(
    busy: Boolean,
    onInstall: (List<File>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var titleId by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<Ps3UpdateService.Ps3Update>>(emptyList()) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var showLibrary by remember { mutableStateOf(false) }

    // The cache, not a scan: this is a picker, and anything worth patching is already listed.
    val library = remember {
        runCatching {
            GameLibraryRepository(context).loadCached().games
                .filter { !it.serial.isNullOrBlank() }
                .distinctBy { it.serial!!.uppercase() }
                .sortedBy { it.title.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun check(id: String) {
        titleId = id.uppercase()
        found = emptyList()
        status = null
        checking = true
        scope.launch {
            when (val result = Ps3UpdateService.find(titleId)) {
                is Ps3UpdateService.Lookup.Found -> {
                    found = result.updates
                    status = null
                }
                Ps3UpdateService.Lookup.None ->
                    status = str("packages.updates.none")
                is Ps3UpdateService.Lookup.Failed ->
                    status = str("packages.updates.failed").format(result.reason)
            }
            checking = false
        }
    }

    fun download(update: Ps3UpdateService.Ps3Update) {
        downloading = update.version
        progress = 0f
        status = null
        scope.launch {
            // Into cacheDir: if the install succeeds the package is dead weight, and if the app is
            // killed mid-download the system reclaims it rather than leaving a part-file the user
            // has to find. Same reason the savestate temps needed sweeping.
            val dest = File(context.cacheDir, "updates/${update.titleId}-${update.version}.pkg")
            val result = Ps3UpdateService.download(update, dest) { progress = it }

            downloading = null
            result.fold(
                onSuccess = { onInstall(listOf(it)) },
                onFailure = { status = str("packages.updates.downloadFailed").format(it.message ?: "download failed") },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                str("packages.updates.description"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        OutlinedTextField(
            value = titleId,
            onValueChange = { titleId = it.trim().uppercase() },
            label = { Text(str("packages.updates.titleId")) },
            placeholder = { Text("BLUS30443") },
            singleLine = true,
            enabled = !checking && downloading == null && !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { check(titleId) },
                enabled = titleId.isNotBlank() && !checking && downloading == null && !busy,
            ) { Text(str("packages.updates.check")) }

            if (library.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showLibrary = !showLibrary },
                    enabled = !checking && downloading == null && !busy,
                ) { Text(str("packages.updates.fromLibrary")) }
            }
        }

        // Saves typing a serial the user would otherwise have to go and look up.
        if (showLibrary) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(library, key = { it.serial!! }) { game ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showLibrary = false
                                check(game.serial!!)
                            },
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(
                                game.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                game.serial!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (checking) {
            Text(str("packages.updates.checking"), style = MaterialTheme.typography.bodyMedium)
        }

        downloading?.let { version ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    str("packages.updates.downloading").format(version, (progress * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Newest last, the order the service publishes them. Sony's packages are cumulative, so
        // the bottom entry is normally the only one worth taking -- but they are all shown rather
        // than one being picked on the user's behalf.
        found.forEach { update ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            str("packages.updates.version").format(update.version),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            buildList {
                                add(formatSize(update.sizeBytes))
                                update.systemVersion.takeIf { it.isNotBlank() }
                                    ?.let { add(str("packages.updates.firmware").format(it)) }
                            }.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { download(update) },
                        enabled = downloading == null && !busy,
                    ) { Text(str("packages.updates.install")) }
                }
            }
        }
    }
}
