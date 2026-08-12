package com.armsx2.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import com.armsx2.data.library.ParamSfo
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
private fun isLicence(file: java.io.File): Boolean = isLicenceName(file.name)

/**
 * The same test against a NAME rather than a File.
 *
 * A pick made through the system picker has no File behind it at all -- the provider may be a
 * USB-OTG app, a card reader or a network share -- so the display name the provider reports is
 * the only thing there is to route on.
 */
private fun isLicenceName(name: String): Boolean =
    name.endsWith(".rap", ignoreCase = true) || name.endsWith(".edat", ignoreCase = true)

/**
 * Order the parts of a split package the way a human numbers them.
 *
 * Part order is CORRECTNESS here, not presentation: installSplitPkg hands the parts to
 * package_reader::extract_data in the order given and the extractor trusts it. The in-app
 * browser sorts its selection by name before handing it over, but the system picker returns
 * documents in the order the user happened to tap them, so a split game picked from a USB
 * drive could arrive part 2 first and extract into a broken install rather than fail.
 *
 * Digit runs compare numerically because plain string order puts _10 between _1 and _2, and a
 * release big enough to be split is exactly the one that reaches ten parts. Compared as digit
 * strings rather than parsed, so a pathologically long run of digits cannot overflow.
 */
private val NaturalOrder = Comparator<String> { first, second ->
    var i = 0
    var j = 0
    while (i < first.length && j < second.length) {
        val a = first[i]
        val b = second[j]
        if (a.isDigit() && b.isDigit()) {
            var ai = i
            while (ai < first.length && first[ai].isDigit()) ai++
            var bj = j
            while (bj < second.length && second[bj].isDigit()) bj++
            val da = first.substring(i, ai).trimStart('0')
            val db = second.substring(j, bj).trimStart('0')
            if (da.length != db.length) return@Comparator da.length - db.length
            val order = da.compareTo(db)
            if (order != 0) return@Comparator order
            i = ai
            j = bj
        } else {
            val order = a.lowercaseChar().compareTo(b.lowercaseChar())
            if (order != 0) return@Comparator order
            i++
            j++
        }
    }
    (first.length - i) - (second.length - j)
}

/**
 * Licence files sitting in exdata.
 *
 * Installing one is a silent copy into a directory nothing else on this screen reads, so a
 * success looked exactly like a failure: a licence belongs to no title, never appears under
 * Installed titles, and left no trace anywhere in the app. Reported as "I installed the .rap
 * but nothing seemed to happen" -- the file was there the whole time.
 *
 * Listed through Licences.exdataDir() rather than a path assembled here, so it follows the
 * logged-in user instead of assuming 00000001, and cannot drift from where installs land.
 */
private fun readLicences(): List<java.io.File> =
    Licences.exdataDir()
        .listFiles()
        ?.filter { it.isFile && isLicence(it) }
        ?.sortedBy { it.name }
        .orEmpty()

/**
 * An installed title, under the name a person would recognise it by.
 *
 * The folder under dev_hdd0/game is named for the title id, and that id was all this screen
 * ever showed: a list of NPUB90434, BLES01807, BLUS30464 with an Uninstall button beside each.
 * Reported in issue #16 -- "it should say Dragon Ball: Raging Blast 2 Demo" -- and it is worse
 * than unhelpful for a delete button, because deciding which of two demos to reclaim space
 * from meant looking the ids up somewhere else.
 *
 * The id is kept and still shown: it is what the compatibility lists, patches and cheat files
 * are keyed by, so dropping it would cost as much as it gained.
 */
private data class InstalledTitle(
    val dir: java.io.File,
    val id: String,
    val name: String,
)

/**
 * TITLE out of an install's own PARAM.SFO, falling back to the folder name.
 *
 * Read straight off disk rather than through the library. The library is a cache that can be
 * stale or empty, and this list exists precisely so uninstall keeps working when it is -- an
 * install that the library has not scanned yet must still be named here.
 */
private fun installedTitleFor(dir: java.io.File): InstalledTitle {
    val sfo = runCatching {
        dir.listFiles()?.firstOrNull { it.isFile && it.name.equals("PARAM.SFO", ignoreCase = true) }
    }.getOrNull()
    val title = sfo?.let { ParamSfo.string(it, "TITLE") }?.trim().orEmpty()
    return InstalledTitle(dir, dir.name, title.ifBlank { dir.name })
}

private fun readInstalled(): List<InstalledTitle> =
    java.io.File(RPCSX.rootDirectory, "config/dev_hdd0/game")
        .listFiles()
        ?.filter { it.isDirectory && isInstalledContent(it) }
        ?.map { installedTitleFor(it) }
        // By name now that there is one: sorting by title id groups a user's library by
        // publisher prefix, which is not an order anyone is looking for.
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()

/**
 * The title id a licence file belongs to, or null when its name does not carry one.
 *
 * A RAP is named for the content id it unlocks -- UP0700-NPUB30910_00-XXXXXXXXXXXXXXXX -- so
 * the id sits between the first dash and the underscore. Used only to put a recognisable name
 * beside a licence; a file that has been renamed simply gets no name, which is what it had.
 */
/**
 * What to call a group of licences: the installed game's real name where we have it, otherwise the
 * bare title id, otherwise "unattributed".
 *
 * The name is only used when it differs from the id -- readInstalled falls back to the folder name
 * (which IS the id) for a title whose PARAM.SFO could not be read, and showing "NPEB00856" as
 * though it were a game name would just be the id twice.
 */
private fun groupLabelFor(titleId: String?, installed: List<InstalledTitle>): String {
    if (titleId == null) return I18n.get("packages.licences.unattributed")
    val named = installed.firstOrNull { it.id == titleId }?.name?.takeIf { it != titleId }
    return named ?: titleId
}

private fun licenceTitleId(file: java.io.File): String? =
    file.nameWithoutExtension
        .substringAfter('-', "")
        .substringBefore('_')
        .takeIf { it.length == 9 && it.all { c -> c.isLetterOrDigit() } }

/**
 * True for a directory under dev_hdd0/game that is actually installed content.
 *
 * Everything in there used to be listed with an Uninstall button beside it, including
 * things that are not titles at all. RPCS3 keeps its own "$locks" directory in here
 * (rpcs3::utils::get_hdd0_locks_dir is get_hdd0_game_dir() + "$locks/", which reaches
 * Android as the escaped ＄locks), so the screen offered to delete the emulator's lock
 * state, and any stray folder a failed install left behind was offered as a title too.
 *
 * A PARAM.SFO is the test. Game data installs keep theirs and are deliberately still
 * listed: a 1.1GB BLUS30464_INSTALL is exactly the kind of thing someone comes here to
 * reclaim, even though it is not bootable.
 */
private fun isInstalledContent(dir: java.io.File): Boolean {
    if (dir.name.startsWith("$") || dir.name.startsWith("＄")) return false
    return runCatching {
        dir.listFiles()?.any { it.isFile && it.name.equals("PARAM.SFO", ignoreCase = true) }
    }.getOrNull() == true
}

/**
 * A title's compiled-code and shader cache.
 *
 * Keyed by title id, which is also the name of the title's folder under dev_hdd0/game --
 * rpcs3::utils::get_cache_dir() appends Emu.GetTitleID() to <root>/cache/cache/, and a PKG
 * installs into a directory named for the same id. Measured at 7-58 MB per title, which is
 * why uninstalling without it leaves the bulk of the disk usage behind.
 */
private fun cacheDirFor(titleId: String): java.io.File =
    java.io.File(RPCSX.rootDirectory, "cache/cache/$titleId")

private fun dirSize(dir: java.io.File): Long =
    if (!dir.isDirectory) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.0f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}

/**
 * Delete a title's cache directory.
 *
 * The name is checked rather than trusted: it comes from a directory listing, but a path
 * separator or a dot-dot in it would resolve outside the per-title folder and take the whole
 * cache — or more — with it. Anything but a single plain segment is refused.
 */
private fun removeCacheFor(titleId: String): Boolean = runCatching {
    if (titleId.isBlank() || titleId == "." || titleId == ".." ||
        titleId.contains('/') || titleId.contains('\\')
    ) return false
    val dir = cacheDirFor(titleId)
    if (!dir.isDirectory) return false
    dir.deleteRecursively()
}.getOrDefault(false)

/** A named reason an external pick could not be installed, kept so the user sees it. */
private class ExternalPickFailure(message: String) : Exception(message)

/**
 * A picked document, opened as something the native installer can actually read.
 *
 * Owns the copy it may have had to make: [close] drops the staged file as well as the
 * descriptor, so a failed or a finished install never leaves gigabytes behind.
 */
private class OpenedDocument(
    val descriptor: ParcelFileDescriptor,
    private val staged: java.io.File?,
) {
    fun close() {
        runCatching { descriptor.close() }
        staged?.let { runCatching { it.delete() } }
    }
}

/**
 * True when the installer can seek in this descriptor.
 *
 * Asked with the same call the native side will make. Every install entry point takes a raw fd
 * and seeks in it -- getFileType sniffs the magic and rewinds, package_reader jumps around the
 * archive -- so a descriptor that only streams forward is not usable, however valid the file
 * behind it is.
 */
private fun isSeekable(descriptor: ParcelFileDescriptor): Boolean = runCatching {
    android.system.Os.lseek(descriptor.fileDescriptor, 0, android.system.OsConstants.SEEK_CUR)
    true
}.getOrDefault(false)

/** Bytes the provider says a document holds, or -1 when it will not say. */
private fun documentSize(resolver: android.content.ContentResolver, uri: android.net.Uri): Long =
    runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                cursor.getLong(column)
            } else {
                null
            }
        }
    }.getOrNull() ?: -1L

/** Free bytes on the volume [dir] is on, asked of the nearest directory that exists. */
private fun usableSpaceAt(dir: java.io.File): Long {
    var probe: java.io.File? = dir
    while (probe != null && !probe.exists()) probe = probe.parentFile
    return runCatching { probe?.usableSpace ?: 0L }.getOrDefault(0L)
}

/**
 * Where a package that has to be copied before it can be installed goes.
 *
 * Whichever of the emulator's own storage and the app cache has more room, because these are
 * often different volumes: someone who put app data on an SD card did it because internal
 * storage is full, and a staged package is the size of the package. The emulator's copy is
 * under cache/ so a kill mid-copy leaves it somewhere users and cleaners already expect
 * disposable data, not next to their games.
 */
private fun stagingDir(context: android.content.Context): java.io.File? {
    val candidates = buildList {
        if (RPCSX.rootDirectory.isNotBlank()) {
            add(java.io.File(RPCSX.rootDirectory, "cache/install"))
        }
        add(java.io.File(context.cacheDir, "install"))
    }
    val best = candidates.maxByOrNull { usableSpaceAt(it) } ?: return null
    return best.takeIf { it.isDirectory || it.mkdirs() }
}

/**
 * A provider's display name reduced to something safe to create in a directory we own.
 *
 * The name is not trusted: it is a string the provider chose, and a path separator in it would
 * put a multi-gigabyte copy somewhere other than the staging directory. Nothing reads the name
 * afterwards -- the installer sniffs the file's magic, never its extension -- so reducing it to
 * plain characters costs nothing.
 */
private fun stagedName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\')
        .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        .trimStart('.')
        .ifBlank { "staged.pkg" }

/**
 * Open a picked document for the native installer, copying it first only when it has to be.
 *
 * Providers backed by real storage -- internal storage, and the SD cards and USB volumes the
 * platform itself mounts -- hand back a descriptor onto a real file, so the common case costs
 * no copy and no extra space at all, which is what makes installing a 40 GB package off a USB
 * drive possible in the first place. That is the whole point of issue #16: a package on OTG
 * storage used to have to be copied to internal storage BY HAND before it could be installed.
 *
 * The providers that cannot do that are the third-party USB-OTG and cloud apps people reach
 * for when the platform will not mount their drive: those hand back a PIPE. lseek on a pipe
 * fails with ESPIPE, and the installer's first act is to seek, so the file was reported as
 * unsupported or broken with nothing wrong with it. Those get copied, which is slow and costs
 * the space, but is the only thing that can be done with a stream.
 */
private fun openForInstall(
    context: android.content.Context,
    uri: android.net.Uri,
    name: String,
): OpenedDocument {
    val resolver = context.contentResolver
    val direct = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
        ?: throw ExternalPickFailure(I18n.get("packages.install.unreadable").format(name))
    if (isSeekable(direct)) return OpenedDocument(direct, null)

    runCatching { direct.close() }

    val size = documentSize(resolver, uri)
    val dir = stagingDir(context)
        ?: throw ExternalPickFailure(I18n.get("packages.install.noRoom").format(name))
    // Headroom, not an exact fit: the install writes the extracted content to this same
    // volume, and filling it completely with the staged copy would only move the failure.
    if (size > 0 && usableSpaceAt(dir) < size + StagingHeadroom) {
        throw ExternalPickFailure(I18n.get("packages.install.noRoom").format(name))
    }

    val staged = java.io.File(dir, stagedName(name))
    val copied = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        } != null
    }.getOrDefault(false)
    // Checked against the size the provider reported, because a copy that stops short does not
    // throw: it produces a shorter file, and a truncated package fails much later as "broken",
    // which is a bug report about the package rather than about the copy.
    if (!copied || (size > 0 && staged.length() != size)) {
        runCatching { staged.delete() }
        throw ExternalPickFailure(I18n.get("packages.install.copyFailed").format(name))
    }

    val descriptor = runCatching {
        ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY)
    }.getOrNull() ?: run {
        runCatching { staged.delete() }
        throw ExternalPickFailure(I18n.get("packages.install.copyFailed").format(name))
    }
    return OpenedDocument(descriptor, staged)
}

/** Room left over after a staged copy, so the install itself still has somewhere to write. */
private const val StagingHeadroom = 256L * 1024L * 1024L

@Composable
fun PackageInstallerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    var progressId by remember { mutableStateOf<Long?>(null) }
    var installed by remember { mutableStateOf(readInstalled()) }
    var licences by remember { mutableStateOf(readLicences()) }
    var confirmRemove by remember { mutableStateOf<InstalledTitle?>(null) }
    var confirmRemoveLicence by remember { mutableStateOf<java.io.File?>(null) }

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
    /**
     * Install from a Storage Access Framework pick.
     *
     * The in-app browser walks java.io.File, which only reaches storage this process can open
     * by path -- internal, and its own external dirs. A .pkg on a USB-OTG drive or on some SD
     * cards is not reachable that way at all, so those users had to copy multi-gigabyte files
     * to internal storage first. Reported as issue #16.
     *
     * Packages are normally handed over as the descriptor SAF already gave us: the native side
     * takes a raw fd, so nothing is copied and a 40 GB package costs no extra space. Only a
     * provider that cannot produce a seekable descriptor forces a copy, which [openForInstall]
     * decides per file. Licences are 16 bytes and their installer wants a real file, so those
     * are always staged.
     */
    fun installFromUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        busy = true
        message = null
        MainActivityRuntime.invoke {
            var nativeFailure: String? = null
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver

                    fun displayName(uri: android.net.Uri): String =
                        runCatching {
                            resolver.query(uri, null, null, null, null)?.use { c ->
                                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                            }
                        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

                    val named = uris.map { it to displayName(it) }
                    val licenceUris = named.filter { (_, n) -> isLicenceName(n) }
                    // Sorted, because the picker returns the user's tap order and the split
                    // installer takes the order given as the part order.
                    val packageUris = named.filterNot { (_, n) -> isLicenceName(n) }
                        .sortedWith(compareBy(NaturalOrder) { (_, n) -> n })

                    val label = if (uris.size == 1) named[0].second else "${uris.size} files"
                    val id = ProgressRepository.create(context, "Installing $label")
                    progressId = id
                    val progressEntry = ProgressRepository.getItem(id)

                    var result = true
                    if (packageUris.isNotEmpty()) {
                        val opened = mutableListOf<OpenedDocument>()
                        try {
                            for ((uri, name) in packageUris) {
                                // Reported on the notification because a copy of a package on
                                // slow external storage takes minutes with nothing else to
                                // show for it, and silence there reads as a hang.
                                ProgressRepository.onProgressEvent(
                                    id, 0, 0, I18n.get("packages.reading").format(name),
                                )
                                opened += openForInstall(context, uri, name)
                            }
                            ProgressRepository.onProgressEvent(
                                id, 0, 0, I18n.get("packages.installingFile").format(label),
                            )
                            result = if (opened.size == 1) {
                                RPCSX.instance.install(opened[0].descriptor.fd, id)
                            } else {
                                RPCSX.instance.installSplitPkg(
                                    opened.map { it.descriptor.fd }.toIntArray(), id,
                                )
                            }
                        } catch (failure: ExternalPickFailure) {
                            // The only failure with a reason worth showing: it names the file
                            // and says what went wrong with it, which the generic string cannot.
                            nativeFailure = failure.message
                            result = false
                            // Reported into the progress entry as well, the same way the native
                            // installer reports its own failures. Bailing before the native call
                            // means nothing else ever finishes that entry, and its notification
                            // is an ongoing one that would sit in the shade forever.
                            ProgressRepository.onProgressEvent(id, -1, 0, failure.message)
                        } finally {
                            opened.forEach { it.close() }
                        }
                    }

                    for ((uri, name) in licenceUris) {
                        if (!result) break
                        val staged = java.io.File(context.cacheDir, stagedName(name))
                        val copied = runCatching {
                            resolver.openInputStream(uri)?.use { input ->
                                staged.outputStream().use { out -> input.copyTo(out) }
                            } != null
                        }.getOrDefault(false)
                        if (!copied) { result = false; break }
                        result = if (name.endsWith(".rap", true)) {
                            Licences.installRap(staged)
                        } else {
                            val d = ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY)
                            try { RPCSX.instance.installKey(d.fd, id, "") } finally { runCatching { d.close() } }
                        }
                        runCatching { staged.delete() }
                    }

                    progressEntry?.value?.takeIf { it.isFailed() }?.let {
                        nativeFailure = it.message.value
                    }
                    result
                }.getOrDefault(false)
            }
            busy = false
            progressId = null
            message = if (ok) {
                GameLibraryRepository(context).invalidateCache()
                installed = readInstalled()
                licences = readLicences()
                I18n.get("packages.install.done")
            } else {
                nativeFailure?.takeIf { it.isNotBlank() } ?: I18n.get("packages.install.failed")
            }
        }
    }

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
                    // Sorted here as well as in the browser: the browser's sort is plain
                    // string order, which puts part 10 between part 1 and part 2.
                    val packages = files.filterNot { isLicence(it) }
                        .sortedWith(compareBy(NaturalOrder) { it.name })
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
                licences = readLicences()
                I18n.get("packages.install.done")
            } else {
                // The native reason names the actual problem ("Game is broken: PARAM.SFO not
                // found", "Every selected file must be a .pkg part"); the generic string
                // guesses, and used to be all the user ever saw.
                nativeFailure?.takeIf { it.isNotBlank() } ?: I18n.get("packages.install.failed")
            }
        }
    }

    // Same treatment as a title: deleting the licence is not undoable, and the content it
    // unlocks stops working without it, so it is confirmed rather than done on tap.
    confirmRemoveLicence?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmRemoveLicence = null },
            title = { Text(str("packages.licence.remove.title")) },
            text = { Text(I18n.get("packages.licence.remove.body").replace("%s", target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val file = target
                    confirmRemoveLicence = null
                    MainActivityRuntime.invoke {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { file.delete() }.getOrDefault(false)
                        }
                        licences = readLicences()
                        message = if (ok) I18n.get("packages.licence.removed")
                        else I18n.get("packages.licence.remove.failed")
                    }
                }) { Text(str("action.remove")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveLicence = null }) { Text(str("action.cancel")) }
            },
        )
    }

    // Deleting a game folder is not undoable, so it is confirmed rather than done on tap.
    confirmRemove?.let { target ->
        // Uninstall only ever removed dev_hdd0/game/<TITLEID>, so the title's compiled-code and
        // shader cache -- by far the larger of the two on disk -- stayed forever. Offered as a
        // checkbox rather than done silently, and defaulted on, which is how RPCS3 desktop's own
        // remove dialog treats caches. Save data, trophies and licences are deliberately NOT
        // touched: those are the user's, not the install's.
        var alsoRemoveCache by remember(target) { mutableStateOf(true) }
        // Off the main thread: a cache directory holds hundreds of files and this runs while the
        // dialog is opening.
        val cacheBytes by androidx.compose.runtime.produceState(0L, target) {
            value = withContext(Dispatchers.IO) { dirSize(cacheDirFor(target.id)) }
        }
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(str("packages.uninstall.confirmTitle")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        str("packages.uninstall.confirmBody").format(
                            if (target.name == target.id) target.id else "${target.name} (${target.id})",
                        ),
                    )
                    // Hidden when there is no cache, so the row never offers to free nothing.
                    if (cacheBytes > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { alsoRemoveCache = !alsoRemoveCache },
                        ) {
                            Checkbox(checked = alsoRemoveCache, onCheckedChange = { alsoRemoveCache = it })
                            Text(
                                str("packages.uninstall.alsoCache").format(formatSize(cacheBytes)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    val removeCache = alsoRemoveCache
                    val titleId = target.id
                    MainActivityRuntime.invoke {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                RPCSX.instance.uninstallGame(target.dir.absolutePath)
                            }.getOrDefault(false)
                        }
                        // Only after the game itself is gone: dropping the cache for a title that
                        // is still installed would just cost the user a recompile.
                        if (ok && removeCache) {
                            withContext(Dispatchers.IO) { removeCacheFor(titleId) }
                        }
                        installed = readInstalled()
                        licences = readLicences()
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

    val safPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (!uris.isNullOrEmpty()) installFromUris(uris) }

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
                        // What is happening right now, when anything says so. Copying a
                        // package off storage that cannot be read directly takes minutes on
                        // its own, before the install has started at all, and a bar with only
                        // "Installing" under it reads as a hang for the whole of it.
                        progress?.message?.value?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
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
                        // Reaches storage the in-app browser cannot open by path: USB-OTG,
                        // and SD cards on devices that only expose them through SAF.
                        Button(
                            onClick = { safPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(str("packages.select.external"))
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

            // Installed licences. Read from exdata, which is where Licences.installRap puts
            // them: without this the screen showed nothing at all after a .rap install and
            // a success was indistinguishable from a failure.
            if (licences.isNotEmpty()) {
                Text(
                    str("packages.licences.header"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Grouped by game, one collapsed row per title, because a library's worth of .rap
                // files is otherwise a flat wall of indistinguishable content ids -- the same
                // reason the cheats browser groups its PNACH files per game rather than listing
                // every patch at once. Requested for that parity.
                //
                // Keyed on the title id already carried inside the content id, which is the id the
                // install folder is named for, so a group can be labelled with the real game name
                // whenever the game it unlocks is installed. Licences whose name carries no id
                // cannot be attributed and get their own group at the end rather than being hidden.
                val licenceGroups = licences.groupBy { licenceTitleId(it) }
                val orderedGroups = licenceGroups.entries
                    .sortedWith(compareBy({ it.key == null }, { groupLabelFor(it.key, installed) }))

                orderedGroups.forEach { (titleId, groupFiles) ->
                    val label = groupLabelFor(titleId, installed)
                    // Collapsed by default: the point of the grouping is that the screen opens
                    // short. A single-licence group is expanded, since hiding one row behind a
                    // disclosure costs a tap and saves nothing.
                    var expanded by remember(titleId) { mutableStateOf(groupFiles.size == 1) }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { expanded = !expanded }
                                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    I18n.get("packages.licences.count")
                                        .replace("%d", groupFiles.size.toString()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (expanded) "▴" else "▾",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (!expanded) return@forEach

                    groupFiles.forEach { file ->
                    val owner = licenceTitleId(file)
                        ?.let { id -> installed.firstOrNull { it.id == id } }
                        ?.takeIf { it.name != it.id }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                owner?.let {
                                    Text(
                                        it.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Text(
                                    file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Installing a licence was one-way: the row existed only to prove the
                            // install had happened, with no way to undo it. A wrong or duplicate
                            // .rap could only be cleared by finding exdata in a file manager,
                            // which on a scoped-storage device is not something most people can
                            // do at all.
                            TextButton(onClick = { confirmRemoveLicence = file }) {
                                Text(str("packages.licence.remove"))
                            }
                        }
                    }
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
                    items(installed, key = { it.dir.absolutePath }) { title ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        title.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    // Only when it adds something: an install whose PARAM.SFO
                                    // could not be read is already named for its id, and the
                                    // row would print it twice.
                                    if (title.name != title.id) {
                                        Text(
                                            title.id,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                TextButton(onClick = { confirmRemove = title }) {
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
