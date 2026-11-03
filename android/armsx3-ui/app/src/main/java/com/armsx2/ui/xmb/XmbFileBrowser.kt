package com.armsx2.ui.xmb

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.armsx2.BuildConfig
import com.armsx2.i18n.str
import com.armsx2.ui.common.SystemPickers
import com.armsx2.ui.common.canBrowse
import com.armsx2.ui.common.defaultBrowseRoot
import com.armsx2.ui.settings.SettingsControllerNav
import com.armsx2.ui.settings.controllerFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The XMB's own way to files and folders, in place of the system's pickers: [pick] opens ARMSX3's
 * browser ([XmbFileBrowser]) where the app reads the storage itself, asking for All files access
 * first on the GitHub build, and the system picker it is given otherwise. The system's folder picker
 * cannot use internal storage's own folder or its Download folder, and its file picker cannot open a
 * folder and a .zip side by side.
 */
internal class XmbPicker(private val controller: XmbController, private val ask: (open: () -> Unit, otherwise: () -> Unit) -> Unit) {
    /**
     * The browser, over the XMB as a panel: files of [extensions] (any file when empty), with
     * [folders] the folder shown as well, with [multiple] as many files as are ticked, taken with
     * [confirm]. [onPick] gets what was taken once the panel has closed. [system] where it cannot be used.
     */
    fun pick(
        key: String,
        title: String,
        icon: XmbIcon,
        extensions: Set<String>,
        fileIcon: XmbIcon,
        folders: Boolean = false,
        multiple: Boolean = false,
        confirm: String = title,
        system: () -> Unit,
        onPick: (List<File>) -> Unit,
    ) = ask(
        {
            controller.panel = XmbNode.Panel(key, title, icon) { _ ->
                XmbFileBrowser(controller, extensions, folders, multiple, confirm, fileIcon) { files ->
                    controller.panel = null
                    onPick(files)
                }
            }
        },
        system,
    )
}

@Composable
internal fun rememberXmbPicker(controller: XmbController): XmbPicker {
    val context = LocalContext.current
    // What to open once All files access has been answered: the browser, else the system picker.
    val pending = remember { AtomicReference<Pair<() -> Unit, () -> Unit>?>(null) }
    val allFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val (open, otherwise) = pending.getAndSet(null) ?: return@rememberLauncherForActivityResult
        if (canBrowse()) open() else otherwise()
    }
    return remember(controller) {
        XmbPicker(controller) { open, otherwise ->
            when {
                canBrowse() -> open()
                BuildConfig.STORAGE_ALL_FILES -> {
                    pending.set(open to otherwise)
                    SystemPickers.launchWithFallback(
                        allFiles,
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
                    ) {
                        pending.set(null)
                        otherwise()
                    }
                }
                else -> otherwise()
            }
        }
    }
}

/** A picked file's name: a file's own, or what its document provider calls it. */
internal fun pickedName(context: Context, uri: Uri): String? =
    if (uri.scheme == "file") uri.path?.let { File(it).name }
    else runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()

/**
 * A file or a folder from anywhere on the device, in the XMB's own list. ARMSX3 reads the storage
 * itself here (All files access), so internal storage and its Download folder are open, as they are
 * not to the system's folder picker, and a .zip can be picked beside the folders. The storage comes
 * first: internal storage and each SD card or drive in, by the names the system gives them. Then a
 * folder at a time: [confirm] with how many are ticked when [multiple] files can be taken, Use This
 * Folder when a folder is what is wanted, Up one folder, the folders in it, and its files of
 * [extensions] (all of them when empty). Cross opens a folder, takes a file or ticks it; Circle goes
 * up a folder, and from the storage closes. It opens on the Download folder, where downloads land.
 */
@Composable
internal fun XmbFileBrowser(
    controller: XmbController,
    extensions: Set<String>,
    /** The folder shown can be chosen (Use This Folder). */
    folders: Boolean,
    multiple: Boolean,
    confirm: String,
    fileIcon: XmbIcon,
    onPick: (List<File>) -> Unit,
) {
    val context = LocalContext.current
    val font = rememberXmbFont()
    // The XMB's own scale, held upright too.
    val cfg = LocalConfiguration.current
    val w = cfg.screenWidthDp.toFloat()
    val h = cfg.screenHeightDp.toFloat()
    val u = if (w >= h) minOf(w / 1280f, h / 720f) else w / 900f
    val roots = remember { storage(context) }
    // Null is the storage list.
    var dir by remember { mutableStateOf<File?>(defaultBrowseRoot().takeIf { it.canRead() }) }
    // The folder just come up from, to land on.
    var from by remember { mutableStateOf<File?>(null) }
    // Ticked files, kept while moving between folders.
    val ticked = remember { mutableStateListOf<File>() }
    val listing by produceState<List<File>?>(null, dir) {
        value = null
        val d = dir ?: return@produceState
        value = withContext(Dispatchers.IO) { list(d, extensions) }
    }
    fun up() {
        val d = dir ?: return
        from = d
        dir = if (roots.any { it.second == d }) null else d.parentFile?.takeIf { it.canRead() }
    }
    fun open(f: File?) {
        from = null
        dir = f
    }
    DisposableEffect(controller) {
        controller.panelBack = {
            if (dir != null) {
                XmbSounds.play(XmbSounds.Sfx.CANCEL)
                up()
                true
            } else {
                false
            }
        }
        onDispose { controller.panelBack = null }
    }
    val folderIcon = XmbIcon("explore_category_video", "item_tex_plain_folder", "📁")
    val storageIcon = XmbIcon("explore_category_video", "item_tex_sd_icon", "💾")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = (12f * u).dp),
        verticalArrangement = Arrangement.spacedBy((2f * u).dp),
    ) {
        // Where the list is: the storage's name, then the folders down to here.
        Text(
            where(dir, roots),
            style = xmbText(font, 16f * u, 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = (18f * u).dp, vertical = (6f * u).dp),
        )

        @Composable
        fun row(id: String, icon: XmbIcon?, label: String, detail: String?, onConfirm: () -> Unit) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .controllerFocusable(id, onConfirm = onConfirm)
                    .pointerInput(id) { detectTapGestures { onConfirm() } }
                    .padding(horizontal = (18f * u).dp, vertical = (8f * u).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) XmbIconView(icon, (32f * u).dp) else Spacer(Modifier.width((32f * u).dp))
                Spacer(Modifier.width((14f * u).dp))
                Text(label, style = xmbText(font, 19f * u, 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (detail != null) Text(detail, style = xmbText(font, 15f * u, 0.6f))
            }
        }

        if (multiple && ticked.isNotEmpty()) row(TAKE, fileIcon, "$confirm (${ticked.size})", null) { onPick(ticked.toList()) }
        val d = dir
        if (d == null) {
            roots.forEach { (name, root) -> row(rowId(root), storageIcon, name, null) { open(root) } }
        } else {
            if (folders) row(USE, folderIcon, str("browse.useFolder"), null) { onPick(listOf(d)) }
            row(UP, null, str("renderer.shaderChain.folder.up"), null) { up() }
            val l = listing
            when {
                l == null -> Text("…", style = xmbText(font, 19f * u, 0.6f), modifier = Modifier.padding(horizontal = (64f * u).dp))
                l.isEmpty() -> Text(str("browse.empty"), style = xmbText(font, 17f * u, 0.6f), modifier = Modifier.padding(horizontal = (64f * u).dp))
                else -> l.forEach { f ->
                    when {
                        f.isDirectory -> row(rowId(f), folderIcon, f.name, null) { open(f) }
                        multiple -> row(rowId(f), fileIcon, (if (f in ticked) "✓  " else "") + f.name, size(f.length())) {
                            if (!ticked.remove(f)) ticked.add(f)
                        }
                        else -> row(rowId(f), fileIcon, f.name, size(f.length())) { onPick(listOf(f)) }
                    }
                }
            }
        }
    }

    // Each list opens on the folder come up from, else Use This Folder, else its first file to take.
    val first = when {
        dir == null -> (from?.let { f -> roots.firstOrNull { it.second == f } } ?: roots.firstOrNull())?.let { rowId(it.second) }
        listing == null -> null
        from != null -> rowId(from!!)
        folders -> USE
        else -> (listing?.firstOrNull { it.isFile } ?: listing?.firstOrNull())?.let(::rowId) ?: UP
    }
    LaunchedEffect(dir, first) {
        if (first == null) return@LaunchedEffect
        var tries = 0
        while (!SettingsControllerNav.selectById(first) && tries++ < 10) withFrameNanos {}
    }
}

private const val TAKE = "xmb.browse.take"
private const val USE = "xmb.browse.use"
private const val UP = "xmb.browse.up"

private fun rowId(f: File) = "xmb.browse.${f.path}"

/** The storage to browse: internal storage and each SD card or drive in, by the system's names for them. */
private fun storage(context: Context): List<Pair<String, File>> {
    val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
    return sm.storageVolumes.filter { it.state == Environment.MEDIA_MOUNTED }.mapNotNull { v ->
        v.directory?.takeIf { it.canRead() }?.let { v.getDescription(context) to it }
    }
}

/** A folder's folders, then its files of [extensions] (all when empty), each by name; hidden ones left out. */
private fun list(dir: File, extensions: Set<String>): List<File> {
    val children = runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty().filter { !it.name.startsWith(".") }
    return children.filter { it.isDirectory }.sortedBy { it.name.lowercase() } +
        children.filter { it.isFile && (extensions.isEmpty() || it.extension.lowercase() in extensions) }.sortedBy { it.name.lowercase() }
}

/** The storage [dir] is on, by name, and the folders under it down to [dir]. */
private fun where(dir: File?, roots: List<Pair<String, File>>): String {
    if (dir == null) return ""
    val root = roots.firstOrNull { (_, r) -> dir.path == r.path || dir.path.startsWith(r.path + File.separator) } ?: return dir.path
    val rest = dir.path.removePrefix(root.second.path).trim(File.separatorChar)
    return if (rest.isEmpty()) root.first else root.first + "  ›  " + rest.replace(File.separator, "  ›  ")
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toFloat())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toFloat())
    else -> "${(bytes + 1023) / 1024} KB"
}
