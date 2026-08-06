package com.armsx2.ui.common

import android.os.Build
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.armsx2.i18n.str
import java.io.File

/**
 * In-app file browser.
 *
 * WHY THIS EXISTS: both `GetContent` and `OpenDocumentTree` hand the user to the
 * system document picker — a different app, on top of ours. Coming back from it
 * is also where Android is most likely to have destroyed our Activity. Browsing
 * here keeps the user inside ARMSX3 for the whole flow, which is what ARMSX1 and
 * ARMSX2 do.
 *
 * Full-screen rather than an AlertDialog: an alert sizes itself to its content
 * and ends up a cramped box with four rows visible, which does not read as a
 * file browser at all.
 *
 * Needs all-files access (MANAGE_EXTERNAL_STORAGE) to see anything outside our
 * own directories — [canBrowse] reports that, and callers fall back to the
 * system picker when it is false rather than showing an empty list.
 *
 * @param extensions lower-case extensions to show, without the dot. Empty = all.
 */
@Composable
fun FileBrowserDialog(
    title: String,
    extensions: Set<String> = emptySet(),
    onPick: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    var dir by remember { mutableStateOf(defaultBrowseRoot()) }

    // Directories first, then matching files, each alphabetical — the order
    // people expect from a file manager.
    val entries = remember(dir) {
        val children = runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty()
        val dirs = children.filter { it.isDirectory && !it.isHidden }
            .sortedBy { it.name.lowercase() }
        val files = children
            .filter { file ->
                file.isFile && !file.isHidden &&
                    (extensions.isEmpty() || file.extension.lowercase() in extensions)
            }
            .sortedBy { it.name.lowercase() }
        dirs + files
    }

    val roots = remember { storageRoots() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Back walks UP a directory before it closes the browser — closing the
        // whole thing from three levels deep is the wrong default.
        BackHandler(enabled = true) {
            val parent = dir.parentFile
            if (parent != null && parent.canRead() && dir.absolutePath != "/") dir = parent
            else onDismiss()
        }

        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            dir.absolutePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text(str("action.cancel")) }
                }

                // Jump straight to a storage volume. Without this, reaching an SD
                // card means walking up to / and back down through /storage.
                if (roots.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        roots.forEach { (label, root) ->
                            TextButton(onClick = { dir = root }) { Text(label) }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    dir.parentFile?.takeIf { it.canRead() }?.let { parent ->
                        item { BrowserRow("..", isDir = true) { dir = parent } }
                    }
                    items(entries) { entry ->
                        BrowserRow(
                            label = entry.name,
                            isDir = entry.isDirectory,
                            detail = if (entry.isFile) {
                                "%.1f MB".format(entry.length() / 1_048_576f)
                            } else {
                                null
                            },
                        ) {
                            if (entry.isDirectory) dir = entry else onPick(entry)
                        }
                    }
                    if (entries.isEmpty()) {
                        item {
                            Text(
                                if (extensions.isEmpty()) str("browse.empty")
                                else str("browse.emptyFiltered"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserRow(
    label: String,
    isDir: Boolean,
    detail: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isDir) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (isDir) "📁" else "📄",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * True when a plain File walk can actually see the user's storage.
 *
 * Below Android 11 the legacy read permission is enough; from 11 onward only
 * all-files access is. Without it the browser would list an empty
 * /storage/emulated/0 and look broken, so callers use the system picker instead.
 */
fun canBrowse(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
    else Environment.getExternalStorageDirectory().canRead()

/** Shared storage — where downloads and firmware actually live. */
fun defaultBrowseRoot(): File {
    val shared = Environment.getExternalStorageDirectory()
    val downloads = File(shared, Environment.DIRECTORY_DOWNLOADS)
    return if (downloads.isDirectory && downloads.canRead()) downloads else shared
}

/** Internal storage plus any readable removable volume, for the shortcut row. */
private fun storageRoots(): List<Pair<String, File>> {
    val out = mutableListOf<Pair<String, File>>()
    val internal = Environment.getExternalStorageDirectory()
    if (internal.canRead()) out += "Internal" to internal
    runCatching {
        File("/storage").listFiles()
            ?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" }
            ?.forEach { out += it.name to it }
    }
    return out
}
