package com.armsx2.packages

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.armsx2.data.library.GameLibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.ProgressRepository
import net.rpcsx.RPCSX
import java.io.File

/**
 * Install one package straight from the library, without going through the installer screen.
 *
 * A .pkg sitting in a games folder is a game the user has and cannot play, and the library
 * now says so by listing it. This is what the tile does when it is tapped. The installer
 * screen keeps its own path, which does more: several files at once, split packages, and
 * licences alongside them. None of that applies to one package picked out of a folder.
 */
object QuickInstall {

    private const val TAG = "ARMSX3-QuickInstall"

    sealed interface Result {
        data object Ok : Result
        data class Failed(val reason: String?) : Result
    }

    /**
     * Install the package at [uri].
     *
     * The descriptor stays open across the call: the core takes a raw fd and releases the
     * handle itself, so closing early would pull the file out from under the extractor.
     */
    suspend fun install(context: Context, uri: Uri, label: String): Result =
        withContext(Dispatchers.IO) {
            val id = ProgressRepository.create(context, "Installing $label")
            val entry = ProgressRepository.getItem(id)

            val ok = runCatching {
                val descriptor = open(context, uri) ?: return@runCatching false
                try {
                    RPCSX.instance.install(descriptor.fd, id)
                } finally {
                    runCatching { descriptor.close() }
                }
            }.getOrDefault(false)

            if (!ok) {
                // The native reason names the real problem, where the generic string guesses.
                // Read after the call returns: the failure message is written before it reports.
                return@withContext Result.Failed(
                    entry?.value?.takeIf { it.isFailed() }?.message?.value,
                )
            }

            // A package that installed is a game now, not a package, so the tile has to stop
            // being one. Nothing else would prompt a rescan: the folder set has not changed.
            GameLibraryRepository(context).invalidateCache()
            Result.Ok
        }

    /**
     * Delete the package file now that its contents are installed.
     *
     * Offered rather than done, and only after a successful install. The file is the user's
     * and it is the only copy they have if the install has to be repeated.
     */
    suspend fun deleteSource(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            when (uri.scheme) {
                "file" -> uri.path?.let { File(it).delete() } ?: false
                "content" -> DocumentsContract.deleteDocument(context.contentResolver, uri)
                else -> false
            }
        }.getOrElse { failure ->
            android.util.Log.w(TAG, "could not delete $uri: ${failure.message}")
            false
        }
    }

    private fun open(context: Context, uri: Uri): ParcelFileDescriptor? = when (uri.scheme) {
        "file" -> uri.path?.let {
            ParcelFileDescriptor.open(File(it), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        else -> context.contentResolver.openFileDescriptor(uri, "r")
    }
}
