package com.armsx2.storage

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.net.toUri

/**
 * A picked folder, named the way the person who picked it would name it.
 *
 * What a folder picker hands back is a document id, and a document id is built for lookup
 * rather than for reading: "primary:PS3 ROMs" is the volume, a colon, and the path. Printed
 * as it stands it looks like a parse that went wrong, and it was being shown to users that
 * way on the settings screen.
 *
 * The volume half is worth keeping rather than trimming off, because with a folder on the
 * card and a folder on internal storage the path alone can name both.
 */
object StorageLabel {

    /**
     * Something readable for [raw], which is either a tree URI or a plain path.
     *
     * Never empty, and never throws: this only ever feeds a label, and a folder the user can
     * see in the list is better named badly than not shown at all.
     */
    fun forFolder(context: Context, raw: String): String {
        if (!raw.startsWith("content://")) {
            return raw.trimEnd('/').substringAfterLast('/').ifEmpty { raw }
        }

        val uri = runCatching { raw.toUri() }.getOrNull() ?: return raw
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return Uri.decode(raw).substringAfterLast('/').ifEmpty { raw }

        val volume = docId.substringBefore(':', "")
        val path = docId.substringAfter(':', docId).trim('/')
        val where = volumeName(context, volume)

        return when {
            path.isEmpty() -> where ?: docId
            where == null -> path
            else -> "$where / $path"
        }
    }

    /**
     * What the system itself calls this volume, so the label matches what the user saw in
     * the picker they just came back from.
     *
     * Falls back to naming it a card rather than guessing at a description. Anything that is
     * not the built-in storage is removable, and on these devices that is nearly always a
     * card, so being wrong here costs a word rather than a meaning.
     */
    private fun volumeName(context: Context, volume: String): String? {
        if (volume.isEmpty()) return null
        if (volume.equals("primary", ignoreCase = true)) {
            return com.armsx2.i18n.I18n.get("storage.volume.internal")
        }

        val described = runCatching {
            context.getSystemService(StorageManager::class.java)
                ?.storageVolumes
                ?.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }
                ?.getDescription(context)
        }.getOrNull()

        return described?.takeIf { it.isNotBlank() }
            ?: com.armsx2.i18n.I18n.get("storage.volume.card")
    }
}
