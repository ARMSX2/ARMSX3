package com.armsx2

import androidx.compose.runtime.mutableStateOf
import com.armsx2.i18n.I18n
import net.rpcsx.ProgressUpdateEntry

/**
 * The firmware compile that follows an install, for the library to show.
 *
 * installFw returns once the firmware is written. The core then compiles its modules on its own
 * thread for a minute or more, reporting into the install's progress entry ("Compiling PPU
 * Modules...", "Progress: file 14 of 76, module 14 of 24 (28s remaining)"). Setup has closed by
 * then, so nothing showed it, and the core reads no disc until it ends: a new library sat with no
 * titles or covers and no reason given. A game booted meanwhile tore the compile down halfway,
 * which is why the library now blocks on it (FirmwareCompileDialog in HomeScreen).
 */
object FirmwareCompile {
    /** Where the compile is. file and total arrive with the first count, secondsLeft with them. */
    data class Progress(val file: Int? = null, val total: Int? = null, val secondsLeft: Int? = null) {
        val fraction: Float? get() = if (file != null && total != null && total > 0) file.toFloat() / total else null

        /** "14 of 76, about 30 s left", or less while the counts are still unknown. */
        fun countText(): String? = when {
            file != null && secondsLeft != null ->
                I18n.get("games.compilingFirmware.countTime").format(file, total, secondsLeft)
            file != null -> I18n.get("games.compilingFirmware.count").format(file, total)
            else -> null
        }
    }

    /** Null when no compile is running. */
    val progress = mutableStateOf<Progress?>(null)

    private val fileProgress = Regex("""file (\d+) of (\d+)""")
    private val secondsRemaining = Regex("""\((\d+)s remaining\)""")

    /** Fed every update of a firmware install's progress entry, on the main thread. */
    fun onUpdate(update: ProgressUpdateEntry) {
        if (update.isFinished()) {
            progress.value = null
            return
        }
        val message = update.message ?: return
        // Only the compile's own messages. The install before it has its own progress on screen.
        if (!message.contains("PPU Modules") && !message.startsWith("Progress: file")) return

        val file = fileProgress.find(message)?.groupValues
        val seconds = secondsRemaining.find(message)?.groupValues?.get(1)?.toIntOrNull()
        progress.value = if (file != null) {
            Progress(file[1].toIntOrNull(), file[2].toIntOrNull(), seconds)
        } else {
            progress.value ?: Progress()
        }
    }

    /** The library header's line while it runs: "Compiling firmware: 14 of 76, about 30 s left". */
    fun headerLine(p: Progress): String =
        p.countText()?.let { I18n.get("games.compilingFirmware.header").format(it) }
            ?: I18n.get("games.compilingFirmware")
}
