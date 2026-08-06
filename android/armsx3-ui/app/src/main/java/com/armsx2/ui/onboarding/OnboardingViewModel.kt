package com.armsx2.ui.onboarding

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.armsx2.BiosInfo
import com.armsx2.runtime.MainActivityRuntime
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.armsx3.NativeApp
import net.rpcsx.FirmwareRepository
import net.rpcsx.FirmwareStatus
import net.rpcsx.ProgressRepository
import net.rpcsx.RPCSX

/** One PS3UPDAT.PUP found by the folder scan, shown in the in-app list. */
data class FirmwareCandidate(val name: String, val uri: Uri, val sizeBytes: Long)

/** One importable BIOS the user can pick from the setup list. */
data class BiosCandidate(val name: String, val path: String, val info: BiosInfo)

data class OnboardingUiState(
    val page: Int = 0,
    val systemLocation: StorageLocation = StorageLocation.Internal,
    val biosName: String? = null,
    val biosInfo: BiosInfo? = null,
    val biosCount: Int = 0,
    // Every BIOS a folder import turned up, so the user can pick right here (not
    // just accept the auto-picked first one). Empty for a single-file import.
    val biosOptions: List<BiosCandidate> = emptyList(),
    val selectedBiosPath: String? = null,
    // PS3 firmware (PS3UPDAT.PUP). This is what the setup gate is actually on --
    // there is no PS2 BIOS to import. The bios* fields above are vestigial and
    // stay only because other ARMSX2 screens still read them.
    val firmwareVersion: String? = null,
    val firmwareInstalled: Boolean = false,
    val firmwareProgress: Float = 0f,
    // Every .PUP the scanned folder turned up, listed IN-APP so choosing one never
    // leaves ARMSX3 -- same shape as ARMSX2's multi-BIOS picker.
    val firmwareOptions: List<FirmwareCandidate> = emptyList(),
    val firmwareScanned: Boolean = false,
    val gameFolders: List<String> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

enum class StorageLocation { Internal, SdCard, Custom }

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    var state = androidx.compose.runtime.mutableStateOf(OnboardingUiState())
        private set

    /**
     * The wizard page, persisted.
     *
     * Picking a firmware file hands control to the system document picker, which
     * is a different process -- Android is free to destroy this Activity while it
     * is away, and on an app holding a ~100 MB core it routinely does. That takes
     * the ViewModel with it (a ViewModel survives configuration changes, NOT
     * destruction), so an in-memory guard is not enough: you come back from the
     * picker and setup has restarted from Welcome.
     *
     * Storing it in prefs is what actually survives, and it costs one small write
     * per page turn.
     */
    private val pageKey = "setup.page"

    private fun savedPage(): Int =
        runCatching { MainActivityRuntime.prefs.getInt(pageKey, -1) }.getOrDefault(-1)

    private fun savePage(page: Int) {
        runCatching { MainActivityRuntime.prefs.edit().putInt(pageKey, page).apply() }
    }

    fun load() {
        val existingBios = MainActivityRuntime.bios.value?.let(::File)?.takeIf(File::isFile)
        val info = existingBios?.let(::probeFile)
        val dir = MainActivityRuntime.systemDir.value
        state.value = state.value.copy(
            systemLocation = when {
                dir == null -> StorageLocation.Internal
                dir == MainActivityRuntime.sdCardDataDir(getApplication()) -> StorageLocation.SdCard
                else -> StorageLocation.Custom
            },
            biosName = existingBios?.name,
            biosInfo = info,
            selectedBiosPath = existingBios?.absolutePath,
            gameFolders = MainActivityRuntime.romsDirs.value,
            // Resume where the user was if setup was interrupted (e.g. Android
            // reclaimed us while the firmware picker was open).
            page = savedPage().takeIf { it >= 0 }
                ?: if (MainActivityRuntime.setupComplete.value) 1 else 0,
            firmwareVersion = FirmwareRepository.version.value,
            firmwareInstalled = FirmwareRepository.status.value != FirmwareStatus.None,
        )
    }

    /**
     * Scan a folder for PS3 firmware and list what it finds, IN-APP.
     *
     * The system document picker is used ONCE, to grant access to a folder --
     * exactly like ARMSX2's BIOS import. Choosing which firmware to install then
     * happens on this screen, so the user is not bounced out to a file explorer
     * to pick a file and dumped back at the start of setup.
     *
     * Matches by extension rather than by name: retail firmware is PS3UPDAT.PUP,
     * but people rename downloads (`PS3UPDAT-4.90.PUP`, `4.91.PUP`), and a name
     * check would show an empty list for a folder that plainly has firmware in it.
     */
    fun scanFirmwareFolder(treeUri: Uri) {
        state.value = state.value.copy(busy = true, error = null)
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                DocumentFile.fromTreeUri(context, treeUri)
                    ?.listFiles()
                    ?.filter { it.isFile && it.name?.endsWith(".PUP", ignoreCase = true) == true }
                    ?.map { FirmwareCandidate(it.name ?: "firmware.PUP", it.uri, it.length()) }
                    ?.sortedBy { it.name.lowercase() }
                    .orEmpty()
            }

            state.value = if (found.isEmpty()) {
                state.value.copy(
                    busy = false,
                    firmwareScanned = true,
                    error = "No .PUP firmware was found in that folder.",
                )
            } else {
                state.value.copy(busy = false, firmwareScanned = true, firmwareOptions = found)
            }
        }
    }

    /**
     * Install one of the scanned firmware files.
     *
     * The core takes a raw fd, not a path -- the file lives behind SAF where there
     * is no POSIX path to hand it. The descriptor must outlive the call, so it is
     * closed only after installFw returns.
     *
     * Slow (PUP unpack, then the system modules), so it runs off the main thread.
     */
    /** Install from a plain file, as picked by the in-app browser. */
    fun installFirmware(file: java.io.File) =
        installFirmware(FirmwareCandidate(file.name, Uri.fromFile(file), file.length()))

    fun installFirmware(candidate: FirmwareCandidate) {
        if (state.value.busy) return

        // The emulator core is dlopen()ed separately and normally comes up from
        // MainActivityRuntime AFTER onboarding is on screen -- so during setup it
        // may not be loaded at all. Bring it up here rather than calling into a
        // core that is not there.
        if (!RPCSX.initialized) {
            runCatching { NativeApp.initializeOnce(getApplication()) }
        }
        if (!RPCSX.initialized) {
            state.value = state.value.copy(
                error = "The emulator core did not load, so firmware cannot be " +
                    "installed. Reinstall ARMSX3 and try again.",
            )
            return
        }

        state.value = state.value.copy(busy = true, error = null, firmwareProgress = 0f)

        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val progressId =
                        ProgressRepository.create(getApplication(), "Installing firmware")
                    resolver.openAssetFileDescriptor(candidate.uri, "r").use { afd ->
                        val fd = afd?.parcelFileDescriptor?.fd ?: return@runCatching false
                        RPCSX.instance.installFw(fd, progressId)
                    }
                }.getOrDefault(false)
            }

            state.value = if (ok && FirmwareRepository.status.value != FirmwareStatus.None) {
                state.value.copy(
                    busy = false,
                    firmwareVersion = FirmwareRepository.version.value,
                    firmwareInstalled = true,
                )
            } else {
                state.value.copy(
                    busy = false,
                    error = "\"${candidate.name}\" could not be installed. " +
                        "It must be the official PS3UPDAT.PUP, not a repack.",
                )
            }
        }
    }

    fun next() {
        if (canContinue()) setPage((state.value.page + 1).coerceAtMost(4))
    }

    fun previous() = setPage((state.value.page - 1).coerceAtLeast(0))

    fun goTo(page: Int) = setPage(page.coerceIn(0, 4))

    private fun setPage(page: Int) {
        state.value = state.value.copy(page = page, error = null)
        savePage(page)
    }

    fun selectStorage(location: StorageLocation) {
        when (location) {
            StorageLocation.Internal -> {
                MainActivityRuntime.systemDir.value = null
                MainActivityRuntime.prefs.edit().remove("systemDir").apply()
                state.value = state.value.copy(systemLocation = location, error = null)
            }
            StorageLocation.SdCard -> {
                val path = MainActivityRuntime.sdCardDataDir(getApplication())
                if (path == null) {
                    state.value = state.value.copy(error = "No writable SD card was found.")
                    return
                }
                MainActivityRuntime.systemDir.value = path
                MainActivityRuntime.prefs.edit().putString("systemDir", path).apply()
                state.value = state.value.copy(systemLocation = location, error = null)
            }
            // Custom is driven by the UI (all-files-access grant + folder pick, github
            // flavor only) — the resolved POSIX path arrives via selectCustomStorage.
            StorageLocation.Custom -> {}
        }
    }

    /** Point the data root at a user-picked folder (github flavor: all-files access).
     *  [path] is a resolved POSIX path under shared storage; the native core writes
     *  memcards / saves / configs there. Requires MANAGE_EXTERNAL_STORAGE, which the
     *  onboarding UI secures before calling this. */
    fun selectCustomStorage(path: String) {
        if (path.isBlank()) {
            state.value = state.value.copy(error = "Couldn't resolve that folder.")
            return
        }
        MainActivityRuntime.systemDir.value = path
        MainActivityRuntime.prefs.edit().putString("systemDir", path).apply()
        state.value = state.value.copy(systemLocation = StorageLocation.Custom, error = null)
    }

    fun importBios(uri: Uri) {
        state.value = state.value.copy(busy = true, error = null)
        val context = getApplication<Application>()
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val imported = importBiosFile(context, uri)
        state.value = if (imported != null) {
            val (target, info) = imported
            selectBios(context, target)
            state.value.copy(
                biosName = target.name,
                biosInfo = info,
                biosCount = 1,
                biosOptions = listOf(BiosCandidate(target.name, target.absolutePath, info)),
                selectedBiosPath = target.absolutePath,
                busy = false,
            )
        } else {
            state.value.copy(busy = false, error = "The selected file is not a valid PlayStation 2 BIOS.")
        }
    }

    // Item 7: point at a folder and import every valid BIOS inside it (refresh
    // parity), instead of one file at a time. Runs off the main thread — copying
    // dozens of BIOS files would otherwise freeze setup for ~10s / risk an ANR.
    // Every valid BIOS is kept as a selectable option; we auto-pick the first so
    // setup can proceed, but the user can choose a different one right on this page
    // (or later in BIOS settings).
    fun importBiosFolder(treeUri: Uri) {
        state.value = state.value.copy(busy = true, error = null)
        val context = getApplication<Application>()
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModelScope.launch {
            val options = withContext(Dispatchers.IO) {
                val children = DocumentFile.fromTreeUri(context, treeUri)?.listFiles()?.filter(DocumentFile::isFile).orEmpty()
                // Source stem -> imported stem, so the .mec/.nvm companions below can follow
                // whatever name the BIOS actually got after sanitising/de-duplication.
                val importedStems = HashMap<String, String>()
                val candidates = children.mapNotNull { child ->
                    val (target, info) = importBiosFile(context, child.uri) ?: return@mapNotNull null
                    child.name?.substringBeforeLast('.')?.lowercase()?.let { srcStem ->
                        importedStems[srcStem] = target.name.substringBeforeLast('.')
                    }
                    BiosCandidate(target.name, target.absolutePath, info)
                }.sortedBy { it.name.lowercase() }
                // The BIOS's own NVRAM/mechacon files are not BIOS images, so importBiosFile
                // rejects them and they were dropped — the .bin arrived without the console
                // settings that belong to it. Requested by Rei Ayanami.
                copyBiosSiblings(context, children, importedStems)
                candidates
            }
            val chosen = options.firstOrNull()
            state.value = if (chosen != null) {
                selectBios(context, File(chosen.path))
                state.value.copy(
                    biosName = chosen.name,
                    biosInfo = chosen.info,
                    biosCount = options.size,
                    biosOptions = options,
                    selectedBiosPath = chosen.path,
                    busy = false,
                )
            } else {
                state.value.copy(busy = false, error = "No PlayStation 2 BIOS files were found in that folder.")
            }
        }
    }

    /** Copy .mec / .nvm companions in beside the BIOS they belong to, renamed to its final stem
     *  (the core looks for them by stem). Folder import only — a single-document URI cannot see
     *  its siblings. Mirrors BiosManagerViewModel.copyBiosSiblings. */
    private fun copyBiosSiblings(
        context: android.content.Context,
        children: List<DocumentFile>,
        importedStems: Map<String, String>,
    ) {
        if (importedStems.isEmpty()) return
        val directory = MainActivityRuntime.internalBiosDir(context)
        children.forEach { child ->
            val name = child.name ?: return@forEach
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext != "mec" && ext != "nvm") return@forEach
            val stem = importedStems[name.substringBeforeLast('.').lowercase()] ?: return@forEach
            runCatching {
                val target = File(directory, "$stem.$ext")
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    target.outputStream().use(input::copyTo)
                }
            }
        }
    }

    /** Switch the active BIOS to one the user tapped in the setup list. */
    fun selectBiosCandidate(candidate: BiosCandidate) {
        selectBios(getApplication(), File(candidate.path))
        state.value = state.value.copy(
            biosName = candidate.name,
            biosInfo = candidate.info,
            selectedBiosPath = candidate.path,
        )
    }

    // Copy+validate a single BIOS into the internal BIOS dir. Returns the stored
    // file and its parsed info, or null for anything that isn't a valid BIOS (so
    // folder import can silently skip non-BIOS files).
    private fun importBiosFile(context: Context, uri: Uri): Pair<File, BiosInfo>? = runCatching {
        val name = DocumentFile.fromSingleUri(context, uri)?.name?.takeIf(String::isNotBlank) ?: "bios.bin"
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
        val info = NativeApp.getBiosInfoFromFd(descriptor.detachFd()) ?: return@runCatching null
        val directory = MainActivityRuntime.internalBiosDir(context).apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(directory, safeName)
        val temporary = File(directory, ".$safeName.import")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temporary.outputStream().use(input::copyTo)
        } ?: return@runCatching null
        if (temporary.length() == 0L) {
            temporary.delete()
            return@runCatching null
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        target to info
    }.getOrNull()

    private fun selectBios(context: Context, target: File) {
        MainActivityRuntime.bios.value = target.absolutePath
        MainActivityRuntime.biosDir.value = null
        MainActivityRuntime.prefs.edit().putString("bios", target.absolutePath).remove("biosDir").apply()
    }

    fun addGameFolder(uri: Uri) {
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val updated = (state.value.gameFolders + uri.toString()).distinct()
        MainActivityRuntime.setRomsDirs(updated)
        state.value = state.value.copy(gameFolders = updated, error = null)
    }

    fun removeGameFolder(uri: String) {
        val updated = state.value.gameFolders - uri
        MainActivityRuntime.setRomsDirs(updated)
        state.value = state.value.copy(gameFolders = updated)
    }

    // Page 2 gates on PS3 FIRMWARE, not a PS2 BIOS. Nothing boots without
    // dev_flash, and the ARMSX2 gate here asked for a .bin that does not exist
    // on this platform -- setup could not be completed at all.
    fun canContinue(): Boolean = when (state.value.page) {
        0, 1 -> true
        2 -> state.value.firmwareInstalled
        3 -> state.value.gameFolders.isNotEmpty()
        else -> state.value.firmwareInstalled && state.value.gameFolders.isNotEmpty()
    }

    fun finish() {
        if (!canContinue()) return
        // Setup is over; drop the resume marker so re-entering via the cog later
        // starts from the storage page rather than the last page of a past run.
        runCatching { MainActivityRuntime.prefs.edit().remove(pageKey).apply() }
        val previousRoot = MainActivityRuntime.currentInitDataRoot()
        MainActivityRuntime.finishSetup()
        val selectedRoot = MainActivityRuntime.systemDirPosix()
        if (MainActivityRuntime.nativeReady.value && previousRoot != null && previousRoot != selectedRoot) {
            MainActivityRuntime.restartApp(getApplication())
        }
    }

    fun dismissError() {
        state.value = state.value.copy(error = null)
    }

    private fun probeFile(file: File): BiosInfo? = runCatching {
        val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        NativeApp.getBiosInfoFromFd(descriptor.detachFd())
    }.getOrNull()
}

