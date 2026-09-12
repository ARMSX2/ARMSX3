package com.armsx2.ui.packages

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun str(key: String) = I18n.get(key)

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
    var progress by remember { mutableStateOf(0f) }
    var manualId by remember { mutableStateOf("") }
    var refreshToken by remember { mutableIntStateOf(0) }

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
    fun installChain(row: UpdateRow) {
        val queue = row.pending
        if (queue.isEmpty()) return

        status = null
        scope.launch {
            fun dest(u: Ps3UpdateService.Ps3Update) =
                File(context.cacheDir, "updates/${u.titleId}-${u.version}.pkg")

            // The next package downloads while this one installs. They are independent -- only the
            // INSTALLS have to stay ordered -- and a chain otherwise alternates network-idle and
            // disk-idle for its whole length.
            var ahead = scope.async(Dispatchers.IO) {
                Ps3UpdateService.download(queue[0], dest(queue[0])) { progress = it }
            }

            for ((index, update) in queue.withIndex()) {
                downloading = if (queue.size == 1) update.version
                else "${update.version} (${index + 1}/${queue.size})"

                val downloaded = ahead.await()

                if (index + 1 < queue.size) {
                    val next = queue[index + 1]
                    ahead = scope.async(Dispatchers.IO) {
                        Ps3UpdateService.download(next, dest(next)) { }
                    }
                }

                val file = downloaded.getOrElse {
                    status = str("packages.updates.downloadFailed").format(it.message ?: "download failed")
                    downloading = null
                    return@launch
                }

                val done = CompletableDeferred<Boolean>()
                onInstall(listOf(file), index == queue.lastIndex) { ok -> done.complete(ok) }

                if (!done.await()) {
                    // The parent already shows the native reason, which names the real problem.
                    status = str("packages.updates.chainStopped").format(update.version)
                    downloading = null
                    return@launch
                }

                // Done with it either way; a chain of seven is otherwise gigabytes of dead cache.
                runCatching { file.delete() }
            }

            downloading = null
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
                Text(
                    str("packages.updates.downloading").format(version, (progress * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
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
                modifier = Modifier.fillMaxWidth(),
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
                                            if (row.installed == null)
                                                str("packages.updates.rowAvailable")
                                                    .format(v.version, formatSize(v.sizeBytes))
                                            else
                                                str("packages.updates.rowUpgrade")
                                                    .format(row.installed, v.version, formatSize(v.sizeBytes))
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
                            onClick = { installChain(row) },
                            enabled = downloading == null && !busy,
                            modifier = Modifier.controllerFocusable(
                                "packages.updates.install.${row.serial}",
                                RoundedCornerShape(20.dp),
                                onConfirm = {
                                    if (downloading == null && !busy) installChain(row)
                                },
                            ),
                        ) { Text(str("packages.updates.install")) }
                    } else if (row.stage == Stage.UpToDate && row.newest != null) {
                        OutlinedButton(
                            onClick = { installChain(row.copy(installed = null)) },
                            enabled = downloading == null && !busy,
                            modifier = Modifier.controllerFocusable(
                                "packages.updates.reinstall.${row.serial}",
                                RoundedCornerShape(20.dp),
                                onConfirm = {
                                    if (downloading == null && !busy) installChain(row.copy(installed = null))
                                },
                            ),
                        ) { Text(str("packages.updates.reinstall")) }
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
