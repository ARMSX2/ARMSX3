package com.armsx2.ui.packages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import android.util.Log
import androidx.compose.ui.unit.dp
import com.armsx2.Ps3Sfo
import com.armsx2.data.library.GameLibraryRepository
import com.armsx2.i18n.I18n
import com.armsx2.ui.settings.controllerFocusable
import com.armsx2.updates.Ps3UpdateService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private fun str(key: String) = I18n.get(key)

private const val TAG = "GameUpdates"

/** Update packages downloading at once. See [GameUpdatesTab]'s installUpTo. */
private const val PARALLEL_DOWNLOADS = 3

/** Free space kept on top of a package before it starts downloading while others are ahead of it. */
private const val SPACE_MARGIN_BYTES = 512L * 1024 * 1024

/**
 * Compare two PS3 version strings ("01.04", "1.10") numerically, field by field.
 *
 * Not a string compare. Sony zero-pads to two digits, so "01.10" vs "01.04" happens to come out
 * right lexically -- but that holds only while both sides are padded the same. The installed
 * version is read out of a PARAM.SFO written by whoever built the package, and against an
 * unpadded "1.4" a lexical compare puts "1.10" first and reports a newer update as older.
 */
private fun compareVersions(a: String, b: String): Int {
    val left = a.split('.').mapNotNull { it.trim().toIntOrNull() }
    val right = b.split('.').mapNotNull { it.trim().toIntOrNull() }
    for (i in 0 until maxOf(left.size, right.size)) {
        val d = (left.getOrNull(i) ?: 0).compareTo(right.getOrNull(i) ?: 0)
        if (d != 0) return d
    }
    return 0
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    bytes > 0 -> "%.0f KB".format(bytes / 1024.0)
    else -> "—"
}

/** Where a title stands. [Pending] is the state a row is born in, before the service has answered. */
private enum class Stage { Pending, Checking, NonePublished, UpToDate, Available, Failed }

private data class UpdateRow(
    val serial: String,
    val title: String,
    val installed: String?,
    val newest: Ps3UpdateService.Ps3Update?,
    val stage: Stage,
    /** Every package the service published, in publication order. See [pending]. */
    val published: List<Ps3UpdateService.Ps3Update> = emptyList(),
) {
    /**
     * The packages that still have to be applied, in the order a PS3 would apply them.
     *
     * Not just the newest one. Sony's packages are USUALLY cumulative -- Watch Dogs publishes a
     * single 01.04 that installs over a bare disc -- but they are not always: Minecraft publishes
     * seven, and its 01.84 is an incremental patch that refuses to install without 01.83 already
     * present ("A target app version is required (01.83), but no PARAM.SFO was found"). Taking
     * only the newest silently failed on every title that patches in steps.
     *
     * Applying a cumulative title's single package is the same operation either way, so this needs
     * no per-title knowledge of which kind it is.
     */
    val pending: List<Ps3UpdateService.Ps3Update>
        get() = published.filter { installed == null || compareVersions(it.version, installed) > 0 }
}

/**
 * Find and install official title updates.
 *
 * Opens on the library rather than an empty text box, and every row carries its own answer -- what
 * is installed, and whether anything newer exists -- without being clicked. The two halves arrive
 * at different speeds and are treated differently because of it: the INSTALLED version is a local
 * PARAM.SFO read, so the whole list has it before the first frame, while AVAILABLE means one
 * request per title against Sony's service. Those run a few at a time in the background and each
 * row resolves itself as its answer lands, so the list is useful immediately instead of blocking
 * on the slowest lookup.
 *
 * Manual entry stays underneath for a title that is not in the library yet.
 */
@Composable
fun GameUpdatesTab(
    busy: Boolean,
    /** files, isLastOfChain, completion. The flag lets a chain pay for one library rescan. */
    onInstall: (List<File>, Boolean, (Boolean) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<UpdateRow>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    // What is coming down the wire, and how far along. Not the same package as the one
    // being installed: the chain fetches the next one while the current one installs.
    var fetching by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var installing by remember { mutableStateOf<String?>(null) }
    var manualId by remember { mutableStateOf("") }
    var refreshToken by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<String?>(null) }
    // Serial whose Remove button is armed. A second tap does it; tapping anything else disarms.
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    fun readInstalled(serial: String): String? =
        Ps3Sfo.installedUpdateVersion(serial)?.takeIf { it.isNotBlank() }

    fun stageFor(installed: String?, newest: Ps3UpdateService.Ps3Update?): Stage = when {
        newest == null -> Stage.NonePublished
        installed != null && compareVersions(installed, newest.version) >= 0 -> Stage.UpToDate
        else -> Stage.Available
    }

    /**
     * Re-read what is installed, locally, without asking the service anything.
     *
     * What a title has published does not change because we installed something, so a full rescan
     * after an install is all cost and no information. It used to be keyed on `busy` as well, which
     * meant a seven-package chain re-queried every game in the library seven times WHILE it was
     * still running -- the reason batch updating crawled.
     */
    fun refreshInstalledOnly() {
        scope.launch {
            val reread = withContext(Dispatchers.IO) {
                rows.map { row ->
                    val installed = readInstalled(row.serial)
                    row.copy(installed = installed, stage = stageFor(installed, row.newest))
                }
            }
            rows = reread
        }
    }

    // Local state first, then the network fills in behind it. Keyed only on refreshToken: the
    // button re-runs it, and an install refreshes locally instead of paying for this again.
    LaunchedEffect(refreshToken) {

        scanning = true
        status = null

        val library = withContext(Dispatchers.IO) {
            runCatching {
                GameLibraryRepository(context).loadCached().games
                    .filter { !it.serial.isNullOrBlank() }
                    .distinctBy { it.serial!!.uppercase() }
                    .sortedBy { it.title.lowercase() }
                    .map { UpdateRow(it.serial!!.uppercase(), it.title, readInstalled(it.serial!!), null, Stage.Pending) }
            }.getOrDefault(emptyList())
        }

        rows = library

        if (library.isEmpty()) {
            scanning = false
            return@LaunchedEffect
        }

        // Four at a time. One request per title serialised would take a minute on a large library;
        // all of them at once is a burst at someone else's server and a thundering herd on a phone
        // radio. Chunked rather than a semaphore because the batch boundaries also give the list
        // visible progress.
        library.chunked(4).forEach { chunk ->
            val resolved = chunk.map { row ->
                async(Dispatchers.IO) {
                    when (val result = Ps3UpdateService.find(row.serial)) {
                        is Ps3UpdateService.Lookup.Found -> {
                            val newest = result.updates.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
                            row.copy(newest = newest, stage = stageFor(row.installed, newest), published = result.updates)
                        }
                        Ps3UpdateService.Lookup.None -> row.copy(stage = Stage.NonePublished)
                        is Ps3UpdateService.Lookup.Failed -> row.copy(stage = Stage.Failed)
                    }
                }
            }.awaitAll()

            val byId = resolved.associateBy { it.serial }
            rows = rows.map { byId[it.serial] ?: it }
        }

        scanning = false
    }

    /**
     * Download and install every package this title still needs, in order, stopping at the first
     * failure. One at a time, waiting for each install to land, because a later package can require
     * the version an earlier one produces.
     */
    fun installUpTo(row: UpdateRow, target: Ps3UpdateService.Ps3Update?) {
        val ceiling = target ?: row.newest

        // Going backwards is not a patch, it is a different state: an older package will not apply
        // over a newer one, so the update comes off first and the chain is rebuilt from the disc.
        val startFrom = if (ceiling != null && row.installed != null &&
            compareVersions(ceiling.version, row.installed) <= 0
        ) {
            if (!Ps3Sfo.removeInstalledUpdate(row.serial)) {
                status = str("packages.updates.removeFailed")
                return
            }
            null
        } else {
            row.installed
        }

        val queue = row.published.filter { p ->
            (startFrom == null || compareVersions(p.version, startFrom) > 0) &&
                (ceiling == null || compareVersions(p.version, ceiling.version) <= 0)
        }
        if (queue.isEmpty()) return

        status = null
        scope.launch {
            fun dest(u: Ps3UpdateService.Ps3Update) =
                File(context.cacheDir, "updates/${u.titleId}-${u.version}.pkg")

            // Packages download several at a time, ahead of the installs, which stay one at a time
            // and in order. One connection to Sony's server carries far less than the Wi-Fi can
            // (about 7 MB/s on an Odin 3, where a Mac pulls 16 MB/s of the same package), and
            // Gran Turismo 6 alone is 21 packages and 10 GB, so a chain that fetched one package at
            // a time left most of the connection idle for its whole length.
            //
            // Every package reports its progress. The bar follows the earliest one still on the
            // wire, which is the one the installs are waiting for. (Prefetched packages used to pass
            // an empty callback, and the bar sat at 100% for the rest of the chain: nothing was
            // wrong, but a seven package title looked frozen, and was reported as frozen.)
            val wire = ConcurrentHashMap<Int, Float>()
            fun showWire() {
                fetching = wire.keys.minOrNull()?.let { i -> queue[i].version to (wire[i] ?: 0f) }
            }

            val results = List(queue.size) { CompletableDeferred<Result<File>>() }
            // How many packages are on the wire at once, and how many may sit on disk ahead of the
            // installs, the one installing included. The second keeps a long chain from filling
            // the cache with gigabytes it has not got to yet.
            val onWire = Semaphore(PARALLEL_DOWNLOADS)
            val ahead = Semaphore(PARALLEL_DOWNLOADS + 1)
            val started = System.nanoTime()

            val downloads = launch(Dispatchers.IO) {
                for ((i, u) in queue.withIndex()) {
                    // Taken in queue order, so a later package can never hold the room the next
                    // install is waiting for.
                    ahead.acquire()
                    // Wait for installs to free the space it needs, unless nothing else is ahead of
                    // it: then it goes regardless, as a single download always did.
                    while (ahead.availablePermits < PARALLEL_DOWNLOADS &&
                        context.cacheDir.usableSpace < u.sizeBytes + SPACE_MARGIN_BYTES
                    ) {
                        delay(1_000)
                    }
                    onWire.acquire()
                    wire[i] = 0f
                    showWire()
                    launch {
                        try {
                            results[i].complete(
                                Ps3UpdateService.download(u, dest(u)) { value ->
                                    wire[i] = value
                                    showWire()
                                }
                            )
                        } finally {
                            results[i].complete(Result.failure(IllegalStateException("download cancelled")))
                            wire.remove(i)
                            showWire()
                            onWire.release()
                        }
                    }
                }
            }

            try {
                for ((index, update) in queue.withIndex()) {
                    downloading = if (queue.size == 1) update.version
                    else "${update.version} (${index + 1}/${queue.size})"

                    val file = results[index].await().getOrElse {
                        status = str("packages.updates.downloadFailed").format(it.message ?: "download failed")
                        return@launch
                    }

                    val done = CompletableDeferred<Boolean>()
                    installing = update.version
                    onInstall(listOf(file), index == queue.lastIndex) { ok -> done.complete(ok) }

                    val ok = done.await()
                    installing = null

                    // Done with it either way; a chain of seven is otherwise gigabytes of dead cache.
                    runCatching { file.delete() }
                    ahead.release()

                    if (!ok) {
                        // The parent already shows the native reason, which names the real problem.
                        status = str("packages.updates.chainStopped").format(update.version)
                        return@launch
                    }
                }

                val seconds = (System.nanoTime() - started) / 1e9
                val megabytes = queue.sumOf { it.sizeBytes } / 1048576.0
                Log.i(TAG, "chain of %d packages, %.0f MB, done in %.0f s (%.1f MB/s overall)".format(
                    queue.size, megabytes, seconds, megabytes / seconds.coerceAtLeast(0.001),
                ))
            } finally {
                // A chain that stops leaves nothing behind: the packages fetched ahead were for
                // installs that are not going to happen now.
                downloads.cancel()
                queue.forEach { runCatching { dest(it).delete() } }
                downloading = null
                fetching = null
                installing = null
            }

            // Re-read rather than assume: the installed version is now whatever is on disk. Local
            // only -- nothing the service told us has changed.
            refreshInstalledOnly()
        }
    }

    fun checkManual(id: String) {
        val serial = id.trim().uppercase()
        if (serial.isBlank()) return
        scope.launch {
            status = str("packages.updates.checking")
            val installed = withContext(Dispatchers.IO) { readInstalled(serial) }
            val row = when (val result = Ps3UpdateService.find(serial)) {
                is Ps3UpdateService.Lookup.Found -> {
                    val newest = result.updates.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
                    UpdateRow(serial, serial, installed, newest, stageFor(installed, newest), result.updates)
                }
                Ps3UpdateService.Lookup.None -> UpdateRow(serial, serial, installed, null, Stage.NonePublished)
                is Ps3UpdateService.Lookup.Failed -> UpdateRow(serial, serial, installed, null, Stage.Failed)
            }
            status = null
            // Replace an existing row for the same title rather than showing it twice.
            rows = listOf(row) + rows.filterNot { it.serial == serial }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { refreshToken++ },
                enabled = !scanning && downloading == null && !busy,
                modifier = Modifier.controllerFocusable(
                    "packages.updates.refresh",
                    RoundedCornerShape(20.dp),
                    onConfirm = { if (!scanning && downloading == null && !busy) refreshToken++ },
                ),
            ) { Text(str("packages.updates.refresh")) }

            if (scanning) {
                Text(
                    str("packages.updates.scanning").format(rows.count { it.stage != Stage.Pending }, rows.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        downloading?.let { version ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val wire = fetching
                val busyWith = installing

                // A determinate bar only where there is something to be determinate about.
                // Installing reports nothing back, so it gets a bar that moves on its own
                // rather than one parked at whatever the last download left behind.
                if (wire != null) {
                    Text(
                        str("packages.updates.downloading").format(wire.first, (wire.second * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(progress = { wire.second }, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(
                        str("packages.updates.installingOne").format(busyWith ?: version),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // The queue position, which is the part that says a chain is making headway.
                Text(
                    version,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (rows.isEmpty() && !scanning) {
            Text(
                str("packages.updates.noLibrary"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Titles with something to install first, then the rest. A list sorted purely by name
        // buries the one row the user came here to act on.
        val ordered = rows.sortedWith(
            compareBy<UpdateRow> { if (it.stage == Stage.Available) 0 else 1 }.thenBy { it.title.lowercase() },
        )

        // Not a LazyColumn: this sits inside the screen's own verticalScroll, and nesting a lazy
        // list in a scrollable parent gives it an unbounded height constraint and crashes.
        ordered.forEach { row ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = if (row.stage == Stage.Available) 0.65f else 0.35f,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        confirmRemove = null
                        expanded = if (expanded == row.serial) null else row.serial
                    }
                    .controllerFocusable(
                        "packages.updates.row.${row.serial}",
                        RoundedCornerShape(12.dp),
                        onConfirm = {
                            confirmRemove = null
                            expanded = if (expanded == row.serial) null else row.serial
                        },
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(row.serial)
                                append("  ·  ")
                                append(
                                    when (row.stage) {
                                        Stage.Pending, Stage.Checking -> str("packages.updates.checking")
                                        Stage.Failed -> str("packages.updates.rowFailed")
                                        Stage.NonePublished -> str("packages.updates.none")
                                        Stage.UpToDate ->
                                            str("packages.updates.rowUpToDate").format(row.installed.orEmpty())
                                        Stage.Available -> {
                                            val v = row.newest!!
                                            // What Install will download, not the newest package's
                                            // size: Gran Turismo 6 read "35 MB" for a chain of 21
                                            // packages and 10 GB.
                                            val chain = row.pending
                                            val size = formatSize(chain.sumOf { it.sizeBytes }.takeIf { it > 0 } ?: v.sizeBytes)
                                            when {
                                                chain.size > 1 && row.installed == null ->
                                                    str("packages.updates.rowChainFromDisc").format(v.version, chain.size, size)
                                                chain.size > 1 ->
                                                    str("packages.updates.rowChain").format(row.installed, v.version, chain.size, size)
                                                row.installed == null ->
                                                    str("packages.updates.rowAvailable").format(v.version, size)
                                                else ->
                                                    str("packages.updates.rowUpgrade").format(row.installed, v.version, size)
                                            }
                                        }
                                    },
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (row.stage == Stage.Available) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (row.stage == Stage.Available) {
                        Button(
                            onClick = { installUpTo(row, null) },
                            enabled = downloading == null && !busy,
                            modifier = Modifier.controllerFocusable(
                                "packages.updates.install.${row.serial}",
                                RoundedCornerShape(20.dp),
                                onConfirm = {
                                    if (downloading == null && !busy) installUpTo(row, null)
                                },
                            ),
                        ) { Text(str("packages.updates.install")) }
                    } else if (row.stage == Stage.UpToDate && row.newest != null) {
                        OutlinedButton(
                            onClick = { installUpTo(row.copy(installed = null), null) },
                            enabled = downloading == null && !busy,
                            modifier = Modifier.controllerFocusable(
                                "packages.updates.reinstall.${row.serial}",
                                RoundedCornerShape(20.dp),
                                onConfirm = {
                                    if (downloading == null && !busy) installUpTo(row.copy(installed = null), null)
                                },
                            ),
                        ) { Text(str("packages.updates.reinstall")) }
                    }
                }
            }

            // Every published version, so a title that patches in steps can be taken to a specific
            // one -- "01.04 broke it, put me on 01.03" is the whole reason this is here.
            if (expanded == row.serial && row.published.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(start = 18.dp, end = 4.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        str("packages.updates.versions"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    row.published.forEach { pkg ->
                        val isCurrent = row.installed != null &&
                            compareVersions(row.installed, pkg.version) == 0

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${pkg.version}  ·  ${formatSize(pkg.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (isCurrent) {
                                Text(
                                    str("packages.updates.current"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 10.dp),
                                )
                            } else {
                                OutlinedButton(
                                    onClick = { installUpTo(row, pkg) },
                                    enabled = downloading == null && !busy,
                                    modifier = Modifier.controllerFocusable(
                                        "packages.updates.ver.${row.serial}.${pkg.version}",
                                        RoundedCornerShape(20.dp),
                                        onConfirm = {
                                            if (downloading == null && !busy) installUpTo(row, pkg)
                                        },
                                    ),
                                ) { Text(str("packages.updates.installThis")) }
                            }
                        }
                    }

                    if (row.installed != null) {
                        Text(
                            str("packages.updates.downgradeNote"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Only for a directory CATEGORY says is game data. A title that lives only on
                    // the HDD is somebody's purchase, not a patch, and is never offered here.
                    if (row.installed != null && Ps3Sfo.installedIsUpdate(row.serial)) {
                        OutlinedButton(
                            onClick = {
                                if (confirmRemove == row.serial) {
                                    confirmRemove = null
                                    status = if (Ps3Sfo.removeInstalledUpdate(row.serial))
                                        str("packages.updates.removed")
                                    else str("packages.updates.removeFailed")
                                    refreshInstalledOnly()
                                } else {
                                    confirmRemove = row.serial
                                }
                            },
                            enabled = downloading == null && !busy,
                            modifier = Modifier.controllerFocusable(
                                "packages.updates.remove.${row.serial}",
                                RoundedCornerShape(20.dp),
                            ),
                        ) {
                            Text(
                                if (confirmRemove == row.serial) str("packages.updates.removeConfirm")
                                else str("packages.updates.removeUpdate"),
                            )
                        }
                    }
                }
            }
        }

        // For a title that is not in the library -- checking before installing the base game, or a
        // disc the scanner has not picked up.
        Text(
            str("packages.updates.manual"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = manualId,
                onValueChange = { manualId = it.trim().uppercase() },
                label = { Text(str("packages.updates.titleId")) },
                placeholder = { Text("BLUS30443") },
                singleLine = true,
                enabled = downloading == null && !busy,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { checkManual(manualId) },
                enabled = manualId.isNotBlank() && downloading == null && !busy,
                modifier = Modifier.controllerFocusable(
                    "packages.updates.checkManual",
                    RoundedCornerShape(20.dp),
                    onConfirm = { if (manualId.isNotBlank() && downloading == null && !busy) checkManual(manualId) },
                ),
            ) { Text(str("packages.updates.check")) }
        }
    }
}
