package com.armsx2.packages

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import com.armsx2.EmuState
import com.armsx2.Main
import com.armsx2.data.library.GameLibraryRepository
import com.armsx2.data.library.Licences
import com.armsx2.i18n.I18n
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.packages.ExternalPickFailure
import com.armsx2.ui.packages.OpenedDocument
import com.armsx2.ui.packages.isLicenceName
import com.armsx2.ui.packages.openForInstall
import com.armsx2.ui.packages.stagedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.armsx3.core.ProgressRepository
import com.armsx3.core.ARMSX3
import java.io.File
import java.util.UUID

/**
 * Installing packages and licences for another app (issue #188).
 *
 * Game library managers download and verify PKGs themselves, and on desktop finish the job with
 * RPCS3's --installpkg. On Android there was nothing to hand the file to, so the user had to open
 * ARMSX3 and install every package by hand. This is that command for Android, with the one thing
 * Android adds: the user says yes in ARMSX3 before anything is installed.
 *
 * ## For the app asking
 *
 * Start [ExternalInstallActivity] for a result, with action [ACTION_INSTALL] and package com.armsx3
 * (com.armsx3.play for the Play build). Give the files as content:// URIs, the first as the
 * intent's data and all of them in its ClipData, with FLAG_GRANT_READ_URI_PERMISSION. A file://
 * URI works only where ARMSX3 can read the path itself.
 *
 * - Packages install in the order given: a base game, then its updates oldest first, then DLC.
 *   The parts of a split package go one after another in part order. ARMSX3 tells a part from the
 *   next package by its header.
 * - Licences (.rap, .edat) install after every package has, and are told apart by file name. A
 *   .rap has to be named for the content id it unlocks, as RPCS3 requires.
 *
 * The result code is RESULT_OK when everything installed, RESULT_CANCELED when the user said no,
 * and RESULT_FIRST_USER when ARMSX3 was busy or a file failed. RESULT_CANCELED with no extras means
 * ARMSX3 was closed before it answered. The extras are [EXTRA_STATUS], one of the STATUS_ values;
 * [EXTRA_MESSAGE], a sentence for the user in ARMSX3's language; and [EXTRA_CONTENT_IDS] and
 * [EXTRA_TITLE_IDS], what was installed, which on a failure is everything before the file that
 * failed.
 *
 * The installed titles and licences can be read back from [InstalledTitlesProvider], once the user
 * has allowed it in Settings > App.
 */
object ExternalInstall {

    const val ACTION_INSTALL = "com.armsx3.action.INSTALL"

    const val EXTRA_STATUS = "com.armsx3.extra.STATUS"
    const val EXTRA_MESSAGE = "com.armsx3.extra.MESSAGE"
    const val EXTRA_TITLE_IDS = "com.armsx3.extra.TITLE_IDS"
    const val EXTRA_CONTENT_IDS = "com.armsx3.extra.CONTENT_IDS"

    /** Everything given was installed. */
    const val STATUS_INSTALLED = "installed"

    /** The user said no. */
    const val STATUS_DECLINED = "declined"

    /** A game is running, or another app's install is: nothing was done, so try again later. */
    const val STATUS_BUSY = "busy"

    /** A file could not be read or installed. */
    const val STATUS_FAILED = "failed"

    /**
     * How Main is told to show a request. Main is exported, so this can arrive from anywhere: it
     * carries only the request's token, and a token that is not the open request does nothing.
     */
    internal const val ACTION_SHOW = "com.armsx2.packages.action.SHOW_EXTERNAL_INSTALL"
    internal const val EXTRA_TOKEN = "com.armsx2.packages.extra.TOKEN"

    private const val TAG = "ARMSX3-ExternalInstall"

    class Item(val uri: Uri, val name: String) {
        val licence: Boolean get() = isLicenceName(name)
    }

    class Request internal constructor(
        val token: String,
        /** The asking app's name, when Android says which app it is. */
        val caller: String?,
        val items: List<Item>,
        /** Hands the result to the activity the caller is waiting on. Main thread only. */
        internal var onDone: ((Result) -> Unit)?,
    )

    class Result(
        val status: String,
        val message: String?,
        contentIds: List<String> = emptyList(),
    ) {
        /** Once each: a licence package and its .rap carry the same id. */
        val contentIds: List<String> = contentIds.distinct()
        val titleIds: List<String> get() = contentIds.mapNotNull(::titleIdOf).distinct()
    }

    /** The request being asked about or installed. One at a time. */
    @Volatile
    private var active: Request? = null

    /** The request Main is showing, once Main has been told about it. */
    val showing = mutableStateOf<Request?>(null)

    /** From the user's yes until the last file is done. */
    val installing = mutableStateOf(false)

    /** The progress entry of the package being installed, for the bar. */
    val progressId = mutableStateOf<Long?>(null)

    /** The file being installed. */
    val current = mutableStateOf<String?>(null)

    /** The files [intent] names, in the order given: its data, then its ClipData. */
    internal fun urisIn(intent: Intent): List<Uri> {
        val uris = LinkedHashSet<Uri>()
        intent.data?.let(uris::add)
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add)
        }
        return uris.filter { it.scheme == "content" || it.scheme == "file" }
    }

    /** A file's name as the provider gives it. A content:// URI's last segment can be an opaque id. */
    internal fun displayName(context: Context, uri: Uri): String =
        runCatching {
            if (uri.scheme != "content") return@runCatching null
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')

    /** A game on screen or paused. Installing then would write under a running title. */
    internal fun gameRunning(): Boolean =
        MainActivityRuntime.eState.value.let { it == EmuState.RUNNING || it == EmuState.PAUSED }

    /** Open a request, or null when one is already open. */
    internal fun open(caller: String?, items: List<Item>, onDone: (Result) -> Unit): Request? =
        synchronized(this) {
            if (active != null) return null
            Request(UUID.randomUUID().toString(), caller, items, onDone).also { active = it }
        }

    /**
     * Main's half: true when [intent] was a request to show, so it never reaches the game-launch
     * path. Shown only when its token names the open request.
     */
    fun accept(intent: Intent?): Boolean {
        if (intent?.action != ACTION_SHOW) return false
        val request = active
        if (request != null && request.token == intent.getStringExtra(EXTRA_TOKEN)) {
            showing.value = request
        }
        return true
    }

    /** The user said no, in ARMSX3 or by going back to the app that asked. */
    fun decline(token: String?) {
        val request = active?.takeIf { it.token == token } ?: return
        if (installing.value) return
        finish(request, Result(STATUS_DECLINED, I18n.get("packages.external.declined")))
    }

    /**
     * No, said in ARMSX3: Cancel, a tap outside the question, Back or B. Goes straight back to the
     * app that asked, which has the answer. Does nothing once the install has started.
     */
    fun cancel() {
        val request = showing.value ?: return
        if (installing.value) return
        decline(request.token)
        MainActivityRuntime.instance?.moveTaskToBack(true)
    }

    /**
     * The activity the caller waits on is gone, so there is nobody left to answer. A request not
     * yet confirmed goes with it, so ARMSX3 does not keep asking; one already installing finishes.
     */
    internal fun detach(token: String?) {
        val request = active?.takeIf { it.token == token } ?: return
        request.onDone = null
        if (!installing.value) {
            finish(request, Result(STATUS_DECLINED, I18n.get("packages.external.declined")))
        }
    }

    /**
     * Close [request] with [result]. Main thread only. Returns whether the caller was still there
     * to be told.
     */
    private fun finish(request: Request, result: Result): Boolean {
        synchronized(this) {
            if (active !== request) return false
            active = null
        }
        showing.value = null
        installing.value = false
        progressId.value = null
        current.value = null
        Log.i(TAG, "${result.status}: ${result.message.orEmpty()} ${result.contentIds}")
        val deliver = request.onDone ?: return false
        request.onDone = null
        deliver(result)
        return true
    }

    /** The user said yes: install everything, then go back to the app that asked. */
    fun start(context: Context, request: Request) {
        if (active !== request || installing.value) return
        installing.value = true
        MainActivityRuntime.invoke {
            val result = withContext(Dispatchers.IO) {
                runCatching { install(context, request) }.getOrElse {
                    Log.e(TAG, "install threw", it)
                    Result(STATUS_FAILED, I18n.get("packages.install.failed"))
                }
            }
            withContext(Dispatchers.Main) {
                if (finish(request, result)) {
                    // The app that asked has the answer and shows it; ARMSX3 has nothing to add.
                    MainActivityRuntime.instance?.moveTaskToBack(true)
                } else {
                    // Nobody is waiting any more, so this is the only place it gets said.
                    Toast.makeText(context, result.message.orEmpty(), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * The install itself, off the main thread. The same steps as a pick in the installer screen,
     * with two differences: packages keep the caller's order, because updates have to go on in
     * order, and each one installs on its own, because a request can carry several.
     */
    private fun install(context: Context, request: Request): Result {
        val installed = mutableListOf<String>()
        var changed = false
        try {
            // A file that does not start with a package header is the next part of the package
            // before it. The header is the only reliable test: parts are often named alike.
            val packages = mutableListOf<MutableList<Item>>()
            val packageIds = mutableListOf<String?>()
            for (item in request.items.filterNot { it.licence }) {
                val head = readHead(context, item.uri, PKG_HEAD)
                    ?: return Result(STATUS_FAILED, I18n.get("packages.external.unreadable").format(item.name), installed)
                if (isPackageHeader(head)) {
                    packages += mutableListOf(item)
                    packageIds += contentIdAt(head, PKG_CONTENT_ID)
                } else {
                    packages.lastOrNull()?.add(item)
                        ?: return Result(STATUS_FAILED, I18n.get("packages.external.notPackage").format(item.name), installed)
                }
            }

            for ((index, parts) in packages.withIndex()) {
                val label = parts.first().name
                current.value = label
                val id = ProgressRepository.create(context, "Installing $label")
                progressId.value = id
                val entry = ProgressRepository.getItem(id)
                var reason: String? = null
                // Every descriptor stays open for the whole install: the native side takes raw fds,
                // and closing one early would pull the file out from under the extractor.
                val opened = mutableListOf<OpenedDocument>()
                val ok = try {
                    for (part in parts) {
                        ProgressRepository.onProgressEvent(id, 0, 0, I18n.get("packages.reading").format(part.name))
                        opened += openForInstall(context, part.uri, part.name)
                    }
                    ProgressRepository.onProgressEvent(id, 0, 0, I18n.get("packages.installingFile").format(label))
                    if (opened.size == 1) {
                        ARMSX3.instance.install(opened[0].descriptor.fd, id)
                    } else {
                        ARMSX3.instance.installSplitPkg(opened.map { it.descriptor.fd }.toIntArray(), id)
                    }
                } catch (failure: ExternalPickFailure) {
                    reason = failure.message
                    // Finished here, as the native installer finishes its own failures, or its
                    // ongoing notification would sit in the shade forever.
                    ProgressRepository.onProgressEvent(id, -1, 0, failure.message)
                    false
                } finally {
                    opened.forEach { it.close() }
                }
                entry?.value?.takeIf { it.isFailed() }?.message?.value?.let { reason = it }
                if (!ok) {
                    return Result(
                        STATUS_FAILED,
                        reason?.takeIf { it.isNotBlank() } ?: I18n.get("packages.install.failed"),
                        installed,
                    )
                }
                changed = true
                packageIds[index]?.let { installed += it }
            }

            progressId.value = null
            for (item in request.items.filter { it.licence }) {
                current.value = item.name
                // Licences are a few bytes and their installers want a real file, so always staged.
                val staged = File(context.cacheDir, stagedName(item.name))
                val contentId = runCatching {
                    val copied = context.contentResolver.openInputStream(item.uri)?.use { input ->
                        staged.outputStream().use { output -> input.copyTo(output) }
                    } != null
                    if (copied) installLicence(context, staged) else null
                }.getOrNull()
                runCatching { staged.delete() }
                contentId ?: return Result(
                    STATUS_FAILED,
                    I18n.get("packages.external.licenceFailed").format(item.name),
                    installed,
                )
                changed = true
                installed += contentId
            }
            return Result(STATUS_INSTALLED, I18n.get("packages.install.done"), installed)
        } finally {
            // Also after a failure part way through: what did install has to show up.
            if (changed) {
                GameLibraryRepository(context).invalidateCache()
                FrontendExport.requestSync(context)
            }
        }
    }

    /** Install the licence in [file]. Returns the content id it unlocks, or null when it failed. */
    private fun installLicence(context: Context, file: File): String? {
        if (file.extension.equals("rap", ignoreCase = true)) {
            // A RAP's name is its content id, and that is the name Licences installs it under.
            return if (Licences.installRap(file)) file.nameWithoutExtension else null
        }
        // An EDAT carries its content id, so installKey names it from the file and needs no game.
        val id = ProgressRepository.create(context, "Installing ${file.name}", silent = true)
        val ok = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                ARMSX3.instance.installKey(descriptor.fd, id, "")
            }
        }.getOrDefault(false)
        ProgressRepository.cancel(id)
        if (!ok) return null
        return readHead(context, Uri.fromFile(file), EDAT_HEAD)
            ?.takeIf { isEdatHeader(it) }
            ?.let { contentIdAt(it, EDAT_CONTENT_ID) }
            ?: file.nameWithoutExtension
    }

    /** The first [size] bytes of [uri], or fewer when it is shorter. Null when it cannot be read. */
    private fun readHead(context: Context, uri: Uri, size: Int): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(size)
            var filled = 0
            while (filled < size) {
                val read = input.read(buffer, filled, size - filled)
                if (read <= 0) break
                filled += read
            }
            buffer.copyOf(filled)
        }
    }.getOrNull()

    // PKG header: magic 7F 'P' 'K' 'G', the content id at 0x30. EDAT (NPD) header: magic
    // 'N' 'P' 'D' 00, the content id at 0x10. Both ids are up to 36 ASCII characters, NUL padded.
    private const val PKG_HEAD = 0x60
    private const val PKG_CONTENT_ID = 0x30
    private const val EDAT_HEAD = 0x40
    private const val EDAT_CONTENT_ID = 0x10
    private const val CONTENT_ID_LENGTH = 36

    private fun isPackageHeader(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 0x7F.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'K'.code.toByte() && head[3] == 'G'.code.toByte()

    private fun isEdatHeader(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'N'.code.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'D'.code.toByte() && head[3] == 0.toByte()

    /**
     * The content id at [offset]. Not held to the usual 36-character shape: packages made by fans
     * use shorter ones (MCEE-NPUB31419_00-0000000000000000) and install fine.
     */
    private fun contentIdAt(head: ByteArray, offset: Int): String? {
        if (head.size < offset + CONTENT_ID_LENGTH) return null
        val id = String(head, offset, CONTENT_ID_LENGTH, Charsets.US_ASCII).substringBefore('\u0000')
        return id.takeIf { it.isNotEmpty() && it.all { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' } }
    }

    private val TitleIdPattern = Regex("[A-Z]{4}[0-9]{5}")

    /**
     * NPUB30910 out of UP0700-NPUB30910_00-XXXXXXXXXXXXXXXX. Found by its shape rather than at a
     * fixed offset, for the same fan packages; the title id comes before the label, so the first
     * match is it.
     */
    internal fun titleIdOf(contentId: String): String? = TitleIdPattern.find(contentId)?.value
}

/**
 * What another app starts to install something (see [ExternalInstall]).
 *
 * Runs in the caller's task, which is what lets a result go back, and hands the user to ARMSX3 for
 * the question. Main cannot be started for a result itself: finishing Main shuts the emulator down
 * and ends the process, and a second Main in the caller's task would run beside the first.
 */
class ExternalInstallActivity : Activity() {

    private var token: String? = null

    /** Set once this has been out of sight, so a return to it can be told from the first start. */
    private var stopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated after the process was killed: the request died with it.
        if (savedInstanceState != null) {
            finish()
            return
        }

        val items = ExternalInstall.urisIn(intent).map { ExternalInstall.Item(it, ExternalInstall.displayName(this, it)) }
        if (items.isEmpty()) {
            answer(ExternalInstall.Result(ExternalInstall.STATUS_FAILED, I18n.get("packages.external.noFiles")))
            return
        }
        // Checked here rather than in ARMSX3, so a request during a game does not pull the game to
        // the front just to say no.
        if (ExternalInstall.gameRunning()) {
            refuse(I18n.get("packages.external.busyGame"))
            return
        }
        val request = ExternalInstall.open(callerName(), items) { answer(it) }
        if (request == null) {
            refuse(I18n.get("packages.external.busyInstall"))
            return
        }
        token = request.token
        startActivity(
            Intent(this, Main::class.java)
                .setAction(ExternalInstall.ACTION_SHOW)
                .putExtra(ExternalInstall.EXTRA_TOKEN, request.token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onStop() {
        super.onStop()
        stopped = true
    }

    override fun onResume() {
        super.onResume()
        if (!stopped || isFinishing) return
        stopped = false
        // Back here with the request still open: the user left ARMSX3 without answering.
        if (ExternalInstall.installing.value) {
            // Too late to say no. ARMSX3 is where the progress is.
            startActivity(Intent(this, Main::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            ExternalInstall.decline(token)
        }
    }

    override fun onDestroy() {
        // Finished by answer() the request is already closed and this does nothing. Otherwise the
        // caller's task went away, and nobody is left to answer.
        if (!isChangingConfigurations) ExternalInstall.detach(token)
        super.onDestroy()
    }

    /** The asking app's name. Android says which app it is only for a request made for a result. */
    @Suppress("DEPRECATION")
    private fun callerName(): String? {
        val caller = callingActivity?.packageName ?: return null
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(caller, 0)).toString()
        }.getOrNull() ?: caller
    }

    private fun refuse(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        answer(ExternalInstall.Result(ExternalInstall.STATUS_BUSY, message))
    }

    private fun answer(result: ExternalInstall.Result) {
        if (isFinishing || isDestroyed) return
        val code = when (result.status) {
            ExternalInstall.STATUS_INSTALLED -> RESULT_OK
            ExternalInstall.STATUS_DECLINED -> RESULT_CANCELED
            else -> RESULT_FIRST_USER
        }
        setResult(
            code,
            Intent()
                .putExtra(ExternalInstall.EXTRA_STATUS, result.status)
                .putExtra(ExternalInstall.EXTRA_MESSAGE, result.message)
                .putExtra(ExternalInstall.EXTRA_CONTENT_IDS, result.contentIds.toTypedArray())
                .putExtra(ExternalInstall.EXTRA_TITLE_IDS, result.titleIds.toTypedArray()),
        )
        finish()
    }
}
