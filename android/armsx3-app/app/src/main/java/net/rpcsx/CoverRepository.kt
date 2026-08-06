package net.rpcsx

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PS3 box-art covers, sourced from aldostools/Resources.
 *
 * Layout there is flat: COV/<TITLE_ID>.JPG, where TITLE_ID is exactly the id we
 * already get out of PARAM.SFO (BLUS30443, BCAS20001, ...). No index file, no
 * per-region subdirectories -- a title either has a cover or 404s.
 *
 * Two things worth knowing before touching the UI side:
 *  - These are 260x300 (aspect 0.866), multiMAN-style covers. They are NOT the
 *    ~0.72 aspect of a real PS3 retail sleeve. Draw them at COVER_ASPECT or
 *    every single one gets letterboxed. See [COVER_ASPECT].
 *  - The repo has no 3D/spine covers, so there is no 3D variant to offer.
 *
 * The repo itself is ~3.1 GB; we fetch per-title on demand and never clone it.
 */
object CoverRepository {
    /** Width / height of every cover in the upstream set (260x300). */
    const val COVER_ASPECT = 260f / 300f

    private const val BASE_URL =
        "https://raw.githubusercontent.com/aldostools/Resources/main/COV"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // aldostools is a public raw-content host; be a good citizen when a user
    // drops in a 200-game library at once.
    private val gate = Semaphore(4)

    private lateinit var cacheDir: File

    /** titleId -> local file path, observable so grid tiles recompose on arrival. */
    private val covers = mutableMapOf<String, MutableState<String?>>()

    fun initialize(context: Context) {
        cacheDir = File(context.filesDir, "covers").apply { mkdirs() }
    }

    private fun coverFile(titleId: String) = File(cacheDir, "$titleId.jpg")

    /**
     * Marker for "upstream has no cover for this id". Without it every library
     * refresh re-requests every coverless title forever.
     */
    private fun missFile(titleId: String) = File(cacheDir, "$titleId.miss")

    /**
     * PS3 title id: 4 letters then 5 digits (BLUS30443, BCES01234, NPUB30662).
     * Games are installed to <root>/games/<TITLE_ID>/, so the id is a path
     * component -- but scan the components from the end rather than assuming a
     * fixed depth, since a title can also be booted from an arbitrary folder.
     */
    private val TITLE_ID_REGEX = Regex("^[A-Z]{4}[0-9]{5}$")

    fun titleIdFromPath(path: String): String? =
        path.trimEnd('/')
            .split('/')
            .asReversed()
            .firstOrNull { TITLE_ID_REGEX.matches(it.uppercase()) }
            ?.uppercase()

    fun coverState(titleId: String): MutableState<String?> =
        covers.getOrPut(titleId) {
            mutableStateOf(coverFile(titleId).takeIf { it.exists() }?.absolutePath)
        }

    /**
     * Ensure a cover exists locally for [titleId]. Safe to call on every library
     * scan: returns immediately if already cached or already known-missing.
     */
    suspend fun fetch(titleId: String): Boolean {
        if (titleId.isBlank()) return false
        if (!::cacheDir.isInitialized) return false

        val target = coverFile(titleId)
        if (target.exists() && target.length() > 0) {
            coverState(titleId).value = target.absolutePath
            return true
        }
        if (missFile(titleId).exists()) return false

        return withContext(Dispatchers.IO) {
            gate.withPermit {
                // Re-check: a concurrent caller may have landed it while we queued.
                if (target.exists() && target.length() > 0) {
                    coverState(titleId).value = target.absolutePath
                    return@withPermit true
                }

                val request = Request.Builder()
                    .url("$BASE_URL/${titleId.uppercase()}.JPG")
                    .build()

                try {
                    client.newCall(request).execute().use { response ->
                        if (response.code == 404) {
                            missFile(titleId).createNewFile()
                            return@withPermit false
                        }
                        if (!response.isSuccessful) return@withPermit false

                        // OkHttp 5: body is non-null.
                        val body = response.body

                        // Download to a temp file and rename, so an interrupted
                        // fetch can never leave a truncated jpg in the cache
                        // that we would then treat as valid forever.
                        val tmp = File(cacheDir, "$titleId.part")
                        tmp.outputStream().use { out -> body.byteStream().copyTo(out) }

                        if (tmp.length() == 0L) {
                            tmp.delete()
                            return@withPermit false
                        }

                        tmp.renameTo(target)
                        coverState(titleId).value = target.absolutePath
                        true
                    }
                } catch (_: Exception) {
                    // Offline or transient: do NOT write a .miss marker, so it
                    // retries next launch rather than being permanently blank.
                    false
                }
            }
        }
    }

    /** Drop a cached cover so the next [fetch] re-downloads it. */
    fun evict(titleId: String) {
        coverFile(titleId).delete()
        missFile(titleId).delete()
        coverState(titleId).value = null
    }

    /** Clear every cached cover and every negative marker. */
    fun clear() {
        if (!::cacheDir.isInitialized) return
        cacheDir.listFiles()?.forEach { it.delete() }
        covers.values.forEach { it.value = null }
    }
}
