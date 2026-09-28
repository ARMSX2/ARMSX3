package com.armsx2.ui.saves

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.armsx2.GameInfo
import com.armsx2.i18n.I18n
import com.armsx2.runtime.MainActivityRuntime
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.armsx3.NativeApp

data class SaveStateItem(
    val slot: Int?,
    val file: File,
    val gameTitle: String,
    val serial: String,
    val preview: Bitmap?,
    val canUseWithActiveGame: Boolean,
)

data class SaveManagerUiState(
    val gameTitle: String? = null,
    val saves: List<SaveStateItem> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
    val error: String? = null,
)

class SaveManagerViewModel(application: Application) : AndroidViewModel(application) {
    var state = androidx.compose.runtime.mutableStateOf(SaveManagerUiState())
        private set

    /** The game this list was opened for from the library, or null for every game's states. */
    private var scopeGame: GameInfo? = null

    fun open(game: GameInfo?) {
        scopeGame = game
        // The view model outlives the screen, so drop the last list rather than flash it under
        // the new title while this one is read.
        state.value = state.value.copy(gameTitle = game?.title, saves = emptyList())
        refresh()
    }

    fun refresh() {
        val previous = state.value
        state.value = previous.copy(loading = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { readSaves() }
            state.value = state.value.copy(
                gameTitle = result.gameTitle,
                saves = result.saves,
                loading = false,
            )
        }
    }

    fun save(slot: Int) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { NativeApp.saveStateToSlot(slot) }.getOrDefault(false)
            }
            state.value = if (ok) {
                state.value.copy(message = "${I18n.get("action.save")} · ${slotName(slot)}")
            } else {
                state.value.copy(error = "${I18n.get("action.save")} · ${slotName(slot)}")
            }
            refresh()
        }
    }

    /**
     * Load [item].
     *
     * Into the running game when the state is that game's, where the core reboots from it in
     * place. Anything else boots the state itself, which starts its game and restores it in a
     * single boot. With nothing running this used to boot whichever game was played LAST and then
     * load the slot number into it: every module compiled twice, and a slot from another game's
     * list restored that game's slot of the same number, or nothing.
     */
    fun load(item: SaveStateItem) {
        val slot = item.slot ?: return
        viewModelScope.launch {
            val hasActiveVm = withContext(Dispatchers.IO) {
                runCatching { NativeApp.hasActiveVM() }.getOrDefault(false)
            }
            if (hasActiveVm && item.canUseWithActiveGame) {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { NativeApp.loadStateFromSlot(slot) }.getOrDefault(false)
                }
                state.value = if (ok) {
                    state.value.copy(message = "${I18n.get("touch.stateAction.load")} · ${slotName(slot)}")
                } else {
                    state.value.copy(error = "${I18n.get("touch.stateAction.load")} · ${slotName(slot)}")
                }
                return@launch
            }

            val game = scopeGame?.takeIf { it.serial.equals(item.serial, ignoreCase = true) }
                ?: withContext(Dispatchers.IO) { MainActivityRuntime.libraryGameForSerial(item.serial) }
            if (MainActivityRuntime.launchGameFromState(game, item.file)) {
                // Leaving the game should land in the library like any other launch, not back
                // on this list.
                com.armsx2.navigation.UiNavigator.home()
            } else if (game?.locked != true) {
                // A locked game has already been answered with the licence prompt.
                state.value = state.value.copy(error = "${I18n.get("touch.stateAction.load")} · ${slotName(slot)}")
            }
        }
    }

    fun delete(item: SaveStateItem) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { item.file.delete() }.getOrDefault(false).also { deleted ->
                    // The picture too, as the in-game delete does, or the slot's next save shows
                    // this one's if its own frame never arrives.
                    if (deleted) item.slot?.let { runCatching { File(item.file.parentFile, "slot$it.thumb").delete() } }
                }
            }
            state.value = if (ok) {
                state.value.copy(message = I18n.get("action.delete"))
            } else {
                state.value.copy(error = I18n.get("action.delete"))
            }
            refresh()
        }
    }

    fun backupAll() {
        val files = state.value.saves.map(SaveStateItem::file)
        if (files.isEmpty()) {
            state.value = state.value.copy(error = I18n.get("savestate.noSavesToBackUp"))
            return
        }
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                val destination = File(files.first().parentFile, "backups/${System.currentTimeMillis()}").apply { mkdirs() }
                files.count { file ->
                    runCatching {
                        file.copyTo(File(destination, file.name), overwrite = true)
                        true
                    }.getOrDefault(false)
                }
            }
            state.value = state.value.copy(message = "${I18n.get("savestate.backup")} · $count")
        }
    }

    /**
     * Import an external save-state file (e.g. an AetherSX2 / NetherSX2 state, or a .p2s from another
     * install) into the ACTIVE game's next free slot. The bytes are copied verbatim to the slot's
     * on-disk path — the native loader detects the format by content on load (see the legacy
     * save-state reader), so the .p2s extension of the destination doesn't have to match the source.
     *
     * A save state belongs to one specific game, so this needs an active game to target; from the
     * global manager with nothing running it reports that. Slots are chosen automatically (first
     * free of 0..9) so it never silently overwrites an existing save.
     */
    /**
     * Delete everything under the savestate directories, recognised or not.
     *
     * The per-entry delete can only remove what the list shows, and the list can only show files it
     * recognises. That is no help to someone whose directory is full of things it does not: a
     * savestate interrupted part-way leaves a file that is neither a finished state nor anything the
     * manager will offer, and #30 reported gigabytes of them after an autosave crashed repeatedly.
     * The startup sweep clears the one shape we know about; this clears the rest.
     *
     * Every removed name is logged. We still do not know what that reporter's files were called --
     * the sweep did not match them and neither does the scan -- so the next person to run this tells
     * us, instead of us guessing at a pattern again.
     */
    fun wipeAll() {
        viewModelScope.launch {
            val (count, bytes, failed) = withContext(Dispatchers.IO) {
                var n = 0
                var size = 0L
                var bad = 0

                savestateRoots(getApplication()).filter { it.isDirectory }.forEach { root ->
                    root.walkBottomUp().forEach { f ->
                        if (f == root) return@forEach
                        val len = if (f.isFile) f.length() else 0L
                        if (runCatching { f.delete() }.getOrDefault(false)) {
                            if (f.isFile || len > 0) {
                                n++
                                size += len
                            }
                            android.util.Log.i("ARMSX3", "wipeAll: removed ${f.name} ($len bytes)")
                        } else if (f.isFile) {
                            bad++
                            android.util.Log.w("ARMSX3", "wipeAll: could NOT remove ${f.absolutePath}")
                        }
                    }
                }
                Triple(n, size, bad)
            }

            state.value = state.value.copy(
                message = if (failed > 0)
                    I18n.get("savestate.wipe.partial").format(count, formatBytes(bytes), failed)
                else
                    I18n.get("savestate.wipe.done").format(count, formatBytes(bytes)),
            )
            refresh()
        }
    }

    fun importState(uri: android.net.Uri) {
        viewModelScope.launch {
            val slot = withContext(Dispatchers.IO) { importSaveStateToNextFreeSlot(getApplication(), uri) }
            state.value = when {
                slot >= 0 -> state.value.copy(message = "${I18n.get("savestate.import")} · ${slot + 1}")
                slot == SS_IMPORT_NO_GAME -> state.value.copy(error = I18n.get("savestate.import.needsGame"))
                slot == SS_IMPORT_SLOTS_FULL -> state.value.copy(error = I18n.get("savestate.import.slotsFull"))
                else -> state.value.copy(error = I18n.get("savestate.import.failed"))
            }
            refresh()
        }
    }

    fun dismissMessage() {
        state.value = state.value.copy(message = null, error = null)
    }

    private data class ReadResult(
        val gameTitle: String?,
        val saves: List<SaveStateItem>,
    )

    private fun readSaves(): ReadResult {
        val scope = scopeGame
        if (scope != null) return readGameSlots(scope)

        val active = MainActivityRuntime.currentGame.value
        val activeSerial = active?.serial.orEmpty()
        val activePaths = (0 until SLOT_COUNT).mapNotNull { slot ->
            runCatching { NativeApp.getGamePathSlot(slot) }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf(File::exists)
        }

        val roots = savestateRoots(getApplication())
        val discovered = roots.flatMap { root ->
            if (!root.isDirectory) emptyList()
            else root.walkTopDown().filter { it.isFile && isSaveState(it) }.toList()
        }
        val allFiles = (activePaths + discovered)
            .distinctBy { it.absolutePath.lowercase() }
            .filter { file -> active == null || activeSerial.isBlank() || serialFrom(file).equals(activeSerial, true) }

        // Every game's states are listed with nothing running, and Load boots any of them, so
        // name the game rather than showing a bare title id.
        val titles = if (active != null) emptyMap() else runCatching {
            com.armsx2.data.library.GameLibraryRepository(getApplication()).loadCached().games
                .mapNotNull { game -> game.serial?.takeIf(String::isNotBlank)?.let { it.uppercase() to game.title } }
                .toMap()
        }.getOrDefault(emptyMap())

        val saves = allFiles.map { file ->
            val serial = serialFrom(file)
            val belongsToActiveGame = active != null && (
                activeSerial.isBlank() || serial.equals(activeSerial, true) || activePaths.any { it.absolutePath == file.absolutePath }
            )
            SaveStateItem(
                slot = slotFrom(file),
                file = file,
                gameTitle = when {
                    belongsToActiveGame -> active?.title.orEmpty().ifBlank { serial }
                    else -> titles[serial.uppercase()]?.takeIf(String::isNotBlank) ?: serial
                },
                serial = serial,
                preview = decodePreview(file),
                canUseWithActiveGame = belongsToActiveGame,
            )
        }.sortedWith(slotOrder)

        return ReadResult(
            gameTitle = active?.title,
            saves = saves,
        )
    }

    /**
     * One game's numbered slots, for the list opened from its long-press in the library.
     *
     * Slots only. The core's own history keeps a copy of every slot save (see
     * _rpcsx_saveStateToSlot), so listing it too would show each save twice, the second time
     * under a bare title id and without a picture.
     */
    private fun readGameSlots(game: GameInfo): ReadResult {
        val active = MainActivityRuntime.currentGame.value
        val running = active != null && !game.serial.isNullOrBlank() && active.serial.equals(game.serial, ignoreCase = true)
        val saves = slotStatesFor(getApplication(), game.serial).map { file ->
            SaveStateItem(
                slot = slotFrom(file),
                file = file,
                gameTitle = game.title,
                serial = serialFrom(file),
                preview = decodePreview(file),
                canUseWithActiveGame = running,
            )
        }.sortedWith(slotOrder)
        return ReadResult(gameTitle = game.title, saves = saves)
    }

    private fun slotName(slot: Int): String =
        if (slot == com.armsx3.Rpcs3Bridge.AUTOSAVE_SLOT) I18n.get("savestate.autosave.title")
        else "${I18n.get("memcard.slot1").substringBefore(' ')} ${slot + 1}"

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
        bytes > 0 -> "%.0f KB".format(bytes / 1024.0)
        else -> "0 KB"
    }

    // A slot's picture sits beside it as slot<N>.thumb (armsx3_slot_capture). This used to ask
    // NativeApp.getSaveStateImage, an ARMSX2 stub that answers null for everything, so no state
    // here ever showed one. The core's own history files have no picture.
    private fun decodePreview(file: File): Bitmap? {
        val slot = slotFrom(file) ?: return null
        return com.armsx3.Rpcs3Bridge.thumbnailBitmap(File(file.parentFile, "slot$slot.thumb"))
    }

    private companion object {
        const val SLOT_COUNT = 10
    }
}

private const val SLOT_DIR = "armsx3_slots"

// Ordered longest-first so ".SAVESTAT" cannot shadow the compressed forms.
private val SAVESTATE_SUFFIXES = listOf(".SAVESTAT.zst", ".SAVESTAT.gz", ".SAVESTAT")

// armsx3_slot_find writes slot<N>.SAVESTAT with whichever extension the core produced.
private val SLOT_PATTERN = Regex("^slot([0-9]+)\\.SAVESTAT", RegexOption.IGNORE_CASE)

/** The autosave first, since it is where the last session ended, then slots in order, then the rest. */
private val slotOrder = compareBy<SaveStateItem> { it.slot == null }
    .thenBy { if (it.slot == com.armsx3.Rpcs3Bridge.AUTOSAVE_SLOT) -1 else it.slot ?: Int.MAX_VALUE }
    .thenByDescending { it.file.lastModified() }

/**
 * Where savestates can live. "sstates" is the ARMSX2 name and is kept so an install carried over
 * from it is not left with an unreachable directory; the core writes "savestates".
 */
private fun savestateRoots(context: android.content.Context): List<File> {
    val root = MainActivityRuntime.assetCopyRoot(context)
    return listOf("config/savestates", "savestates", "sstates")
        .map { File(root, it) }
        .distinctBy { it.absolutePath }
}

/**
 * The numbered slot states for title id [serial]. Blocking, a few directory listings.
 *
 * The folder is matched without regard to case: the core names it with the title id exactly, but
 * the library's serial is only conventionally upper case, and internal storage is case sensitive.
 */
internal fun slotStatesFor(context: android.content.Context, serial: String?): List<File> {
    if (serial.isNullOrBlank()) return emptyList()
    return savestateRoots(context).flatMap { root ->
        root.listFiles()
            ?.filter { it.isDirectory && it.name.equals(serial, ignoreCase = true) }
            ?.flatMap { dir -> File(dir, SLOT_DIR).listFiles()?.toList().orEmpty() }
            ?.filter { it.isFile && isSaveState(it) && slotFrom(it) != null }
            .orEmpty()
    }.distinctBy { it.absolutePath.lowercase() }
}

// The core writes <TITLE>_<digits>.SAVESTAT and compresses it, so the last extension is "zst"
// or "gz" rather than a fixed savestate extension -- File.extension cannot identify one. This
// scanned for "p2s", the PCSX2 extension, which is an ARMSX2 leftover: the walk below matched
// nothing on any device, so the manager only ever listed the running game's numbered slots and
// reported "no savestates" from the library. Issue #123.
private fun isSaveState(file: File): Boolean {
    val name = file.name
    return SAVESTATE_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }
}

private fun slotFrom(file: File): Int? = SLOT_PATTERN.find(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull()

// Both layouts put the title id in the directory, never in the file name: the core's rolling
// states live in savestates/<TITLE>/ and the numbered slots in savestates/<TITLE>/armsx3_slots/.
// Reading it from there beats parsing the name, which is what the old " (" split was doing for
// PCSX2's "SLUS-12345 (Title).00.p2s" convention and which no ARMSX3 file has ever matched.
private fun serialFrom(file: File): String {
    val parent = file.parentFile
    val dir = if (parent?.name.equals(SLOT_DIR, true)) parent?.parentFile else parent
    return dir?.name.orEmpty().ifBlank {
        file.name.substringBefore(".SAVESTAT").substringBeforeLast('_')
    }
}

// Negative sentinels returned by importSaveStateToNextFreeSlot (a slot index >= 0 means success).
internal const val SS_IMPORT_SLOTS_FULL = -1
internal const val SS_IMPORT_FAILED = -2
internal const val SS_IMPORT_NO_GAME = -3

/**
 * Copy an external save-state file [uri] into the ACTIVE game's next free slot (0..9). BLOCKING —
 * call inside withContext(Dispatchers.IO). Returns the slot index on success, or a negative sentinel
 * above. Shared by [SaveManagerViewModel] (library manager) and the in-game SaveStatePicker so both
 * import identically: bytes are copied verbatim to the slot's on-disk path and the native loader
 * detects the format (p2s / AetherSX2 / NetherSX2) by content on load.
 */
internal fun importSaveStateToNextFreeSlot(context: android.content.Context, uri: android.net.Uri): Int {
    val active = MainActivityRuntime.currentGame.value
    if (active == null || active.serial.isNullOrBlank()) return SS_IMPORT_NO_GAME
    return runCatching {
        // Occupancy comes from the core. getGamePathSlot answers with the TITLE ID, so
        // File(it).exists() was false for every slot: the first OCCUPIED slot read as free,
        // and the destination built from the same value was a relative name that landed in
        // the process working directory. The import then reported the slot it had not
        // written, which is worse than failing.
        val free = (0 until 10).firstOrNull { !NativeApp.hasStateInSlot(it) }
            ?: return@runCatching SS_IMPORT_SLOTS_FULL
        val destPath = NativeApp.getSlotFilePath(free)?.takeIf(String::isNotBlank)
            ?: return@runCatching SS_IMPORT_FAILED
        val dest = File(destPath)
        dest.parentFile?.mkdirs()
        val ok = context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
            true
        } ?: false
        if (ok) free else SS_IMPORT_FAILED
    }.getOrDefault(SS_IMPORT_FAILED)
}
