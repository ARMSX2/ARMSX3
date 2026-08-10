package com.armsx2.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.data.library.GameLibraryRepository
import com.armsx2.data.library.Licences
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.FileBrowserDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.ProgressRepository
import net.rpcsx.RPCSX

/**
 * Install a .pkg (game, update or DLC) into the emulator's own storage.
 *
 * The native side already did all of this -- _rpcsx_install dispatches on the file's
 * magic and hands a PKG to installPkg -- but RPCSX.instance.install() had ZERO callers,
 * so there was no way to reach it from the UI. Reported by two testers as a serious
 * oversight, and it read as a missing feature rather than a missing button.
 *
 * Installs land in config/dev_hdd0/game, which GameLibraryRepository now scans, so the
 * title shows up in the library on the next scan. The cache is keyed by folder set and
 * would not notice a new game inside a folder it already knows, hence invalidateCache().
 */
/**
 * Titles installed into the emulator's own storage, i.e. everything a PKG install
 * produced. Read from disk rather than from the library, so uninstall still works when
 * the library cache is stale and can never point at a user's ROM folder.
 */
/** Licence files, which install through a different native entry point than packages. */
private fun isLicence(file: java.io.File): Boolean =
    file.extension.equals("rap", ignoreCase = true) ||
        file.extension.equals("edat", ignoreCase = true)


private fun readInstalled(): List<java.io.File> =
    java.io.File(RPCSX.rootDirectory, "config/dev_hdd0/game")
        .listFiles()
        ?.filter { it.isDirectory }
        ?.sortedBy { it.name }
        .orEmpty()

@Composable
fun PackageInstallerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    var progressId by remember { mutableStateOf<Long?>(null) }
    var installed by remember { mutableStateOf(readInstalled()) }
    var confirmRemove by remember { mutableStateOf<java.io.File?>(null) }

    // getItem returns MutableState<ProgressEntry>; reading .value here and .longValue
    // below is what subscribes this composable to the native progress callbacks.
    val progress = ProgressRepository.getItem(progressId)?.value
    val fraction = progress?.let {
        if (it.isIndeterminate()) null
        else (it.value.longValue.toFloat() / it.max.longValue.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    // One path for every pick: a lone package, several parts of a split one, a licence, or
    // a package together with the licence that unlocks it.
    //
    // The selection is SPLIT BY KIND rather than by count. It used to be routed on count
    // alone, so picking a game's .pkg and its .rap together -- which the file browser
    // invites, since it allows multiple selection -- handed both to installSplitPkg, whose
    // first act is to reject anything that is not a .pkg part. Choosing a game and its
    // licence, the obvious thing to do, could therefore only ever fail.
    //
    // Packages install FIRST: a licence unlocks content the package has to have written.
    fun install(files: List<java.io.File>) {
        if (files.isEmpty()) return
        showBrowser = false
        busy = true
        message = null
        MainActivityRuntime.invoke {
            // The reason the native side reported, if it failed. Held here because
            // ProgressRepository drops its handler entry as soon as a request finishes, so
            // the entry has to be captured while the install is still running.
            var nativeFailure: String? = null
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val licences = files.filter { isLicence(it) }
                    val packages = files.filterNot { isLicence(it) }
                    val label = if (files.size == 1) files[0].name
                    else "${files.size} files"
                    val id = ProgressRepository.create(context, "Installing $label")
                    progressId = id
                    val progressEntry = ProgressRepository.getItem(id)

                    // Every descriptor stays open for the whole install: the native side
                    // takes raw fds and releases the handles itself, so closing them early
                    // would pull the file out from under the extractor.
                    var result = true
                    if (packages.isNotEmpty()) {
                        val descriptors = packages.map {
                            ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
                        }
                        try {
                            result = if (descriptors.size == 1) {
                                RPCSX.instance.install(descriptors[0].fd, id)
                            } else {
                                RPCSX.instance.installSplitPkg(
                                    descriptors.map { it.fd }.toIntArray(), id,
                                )
                            }
                        } finally {
                            descriptors.forEach { runCatching { it.close() } }
                        }
                    }

                    for (licence in licences) {
                        if (!result) break
                        result = if (licence.extension.equals("rap", ignoreCase = true)) {
                            Licences.installRap(licence)
                        } else {
                            // EDAT carries its own content id, so installKey works out the
                            // exdata name from the file itself and needs no game path.
                            val descriptor = ParcelFileDescriptor.open(
                                licence, ParcelFileDescriptor.MODE_READ_ONLY,
                            )
                            try {
                                RPCSX.instance.installKey(descriptor.fd, id, "")
                            } finally {
                                runCatching { descriptor.close() }
                            }
                        }
                    }

                    // Read after the calls return: onProgressEvent writes the message on the
                    // calling thread before it reports, so a failure reason is already there.
                    progressEntry?.value?.takeIf { it.isFailed() }?.let {
                        nativeFailure = it.message.value
                    }
                    result
                }.getOrDefault(false)
            }
            busy = false
            progressId = null
            // I18n.get, not str(): this runs inside a coroutine, and str() is a
            // @Composable that can only be called during composition.
            message = if (ok) {
                // Force the library to re-read storage; the folder set is unchanged
                // so nothing else would prompt a rescan.
                GameLibraryRepository(context).invalidateCache()
                installed = readInstalled()
                I18n.get("packages.install.done")
            } else {
                // The native reason names the actual problem ("Game is broken: PARAM.SFO not
                // found", "Every selected file must be a .pkg part"); the generic string
                // guesses, and used to be all the user ever saw.
                nativeFailure?.takeIf { it.isNotBlank() } ?: I18n.get("packages.install.failed")
            }
        }
    }

    // Deleting a game folder is not undoable, so it is confirmed rather than done on tap.
    confirmRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(str("packages.uninstall.confirmTitle")) },
            text = { Text(str("packages.uninstall.confirmBody").format(target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    MainActivityRuntime.invoke {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                RPCSX.instance.uninstallGame(target.absolutePath)
                            }.getOrDefault(false)
                        }
                        installed = readInstalled()
                        if (ok) GameLibraryRepository(context).invalidateCache()
                        message = I18n.get(
                            if (ok) "packages.uninstall.done" else "packages.uninstall.failed",
                        )
                    }
                }) { Text(str("packages.uninstall")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text(str("action.cancel")) }
            },
        )
    }

    if (showBrowser) {
        FileBrowserDialog(
            title = str("packages.select.title"),
            // RAP and EDAT are licence files, not packages, and install() routes them
            // accordingly. Some titles need both: the .pkg carries the content and the .rap
            // is what unlocks it, so both can be selected in one go. PUP stays out on
            // purpose, firmware has its own screen and should not be installable from a
            // menu that says nothing about it.
            extensions = setOf("pkg", "rap", "edat"),
            // Split releases ship as several .pkg parts that only install correctly when
            // handed to the installer together, the way RPCS3 desktop does it.
            allowMultiple = true,
            onPickMultiple = { files -> install(files) },
            onPick = { file -> install(listOf(file)) },
            onDismiss = { showBrowser = false },
        )
    }

    ArmsBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                str("packages.title"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        str("packages.description"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        str("packages.multiHint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (busy) {
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            str("packages.installing"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(onClick = { showBrowser = true }) {
                            Text(str("packages.select.action"))
                        }
                    }

                    message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Installed titles, with uninstall. Only what is under the emulator's own
            // dev_hdd0/game is listed, so this can never remove a disc or ROM folder.
            if (installed.isNotEmpty()) {
                Text(
                    str("packages.installed.header"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(installed, key = { it.absolutePath }) { dir ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    dir.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { confirmRemove = dir }) {
                                    Text(str("packages.uninstall"))
                                }
                            }
                        }
                    }
                }
            }

            TextButton(onClick = onBack) { Text(str("action.back")) }
        }
    }
}
