package com.armsx2.data.trophies

import android.util.Log
import java.io.File
import net.rpcsx.RPCSX
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Reader for RPCS3's NATIVE PS3 trophy data — the real thing the games write, not
 * RetroAchievements (which has no PS3 support at all, which is why the RA screen is
 * hidden in ARMSX3).
 *
 * Where the data lives, and why this reads it from disk rather than through JNI:
 *
 *   config/dev_hdd0/home/<user>/trophy/<NPWRxxxxx_00>/
 *     TROPCONF.SFM  — the trophy DEFINITIONS, installed from the game's TROPHY.TRP.
 *                     Plain XML: <title-name>, then one <trophy id hidden ttype pid>
 *                     per trophy with <name> and <detail> children.
 *     TROPUSR.DAT   — the user's UNLOCK STATE. Big-endian binary, written by
 *                     sceNpTrophyUnlockTrophy via TROPUSRLoader::Save.
 *     ICON0.PNG     — the game's icon; TROP000.PNG… the per-trophy icons.
 *
 * The emulator's own loaders (rpcs3/Loader/TROPUSR.cpp, and the overlay's
 * load_trophies in Emu/RSX/Overlays/Trophies/overlay_trophy_list_dialog.cpp) sit behind
 * vfs::get, so reaching them needs the core dlopen()ed AND its VFS mounted — neither is
 * guaranteed in the library, which is exactly where this screen is used. These files are
 * inside the app's own external files dir, so plain java.io reads them with no core at
 * all: no JNI, no native rebuild, and the browser works before a game has ever booted.
 *
 * The parse mirrors TROPUSR.h/.cpp field for field; see [readTropUsr] for the one
 * non-obvious part (the entry stride).
 */
object TrophyRepository {

    private const val TAG = "Trophies"

    /** TROPUSR.DAT magic, from TROPUSR.cpp's TROPUSR_MAGIC. */
    private const val TROPUSR_MAGIC = 0x818F54AD.toInt()

    /**
     * Microseconds from 0001-01-01 to 1970-01-01 (719162 days).
     *
     * A trophy timestamp is a CellRtcTick — sceNpTrophyUnlockTrophy stores
     * cellRtcGetCurrentTick's value straight into the entry — and cellRtc counts
     * microseconds from year 1 UTC (see tick_to_date_time in cellRtc.cpp). Subtracting
     * this turns it into a Unix epoch.
     */
    private const val RTC_EPOCH_US = 62135596800L * 1_000_000L

    enum class Grade { Unknown, Platinum, Gold, Silver, Bronze }

    data class Trophy(
        val id: Int,
        /** Real name from TROPCONF.SFM. Masked by the UI while a hidden trophy is locked. */
        val name: String,
        val description: String,
        val grade: Grade,
        val hidden: Boolean,
        val unlocked: Boolean,
        /** Unix millis, or null when the file carries no timestamp (never unlocked, or an
         *  unlock written by something that did not stamp it). */
        val unlockedAt: Long?,
        /** TROP%03d.PNG for this trophy, or null when the icon is missing. */
        val icon: File?,
    )

    data class Game(
        /** The trophy folder name, e.g. NPWR05636_00. The only stable id here — a trophy
         *  set is keyed by comm id, not by the game's title id. */
        val commId: String,
        val title: String,
        val detail: String,
        val icon: File?,
        val trophies: List<Trophy>,
    ) {
        val total: Int get() = trophies.size
        val unlocked: Int get() = trophies.count { it.unlocked }
        val percent: Int get() = if (total > 0) 100 * unlocked / total else 0
    }

    /** Root of the emulator's HDD, i.e. what RPCS3 mounts as /dev_hdd0. */
    private fun hdd0(): File = File(RPCSX.getHdd0Dir())

    /**
     * The trophy directories to scan.
     *
     * Prefers the logged-in user (Rpcs3Bridge logs in "00000001"), but falls back to
     * whichever user folder actually holds a trophy dir: getUser() reaches through JNI
     * into the core, which returns null when the core is not open yet, and a browser that
     * showed nothing until a game had booted would look broken.
     */
    private fun trophyRoots(): List<File> {
        val home = File(hdd0(), "home")
        val users = home.listFiles().orEmpty().filter { it.isDirectory }
        val preferred = runCatching { RPCSX.instance.getUser() }.getOrNull()?.takeIf { it.isNotBlank() }
        val ordered = if (preferred != null) {
            users.sortedBy { it.name != preferred }
        } else {
            users
        }
        val roots = ordered.map { File(it, "trophy") }.filter { it.isDirectory }
        // One user is the norm; only that user's sets are shown. Scanning every user would
        // merge two people's progress into one list.
        return roots.take(1)
    }

    /**
     * Every trophy set on disk, newest-played first is NOT assumed — sorted by title so the
     * list is stable across sessions.
     *
     * Blocking disk work: call from Dispatchers.IO.
     */
    fun load(): List<Game> {
        val dirs = trophyRoots().flatMap { it.listFiles().orEmpty().filter { d -> d.isDirectory } }
        return dirs.mapNotNull { readGame(it) }.sortedBy { it.title.lowercase() }
    }

    /**
     * The RUNNING game's trophy set, or null when it has none (or none can be identified).
     *
     * Blocking disk work: call from Dispatchers.IO.
     *
     * IDENTIFYING THE SET is the whole difficulty here, because trophy folders are named by
     * NPWR comm id and nothing on the folder says which title it belongs to. Two sources,
     * in order:
     *
     *  1. The core's own `current_trophy_name`, via [RPCSX.getCurrentTrophyName]. This is
     *     exactly what RPCS3's home menu uses to pick the set for its native overlay list,
     *     written by sceNpTrophyCreateContext. Authoritative, and works for disc and
     *     installed titles alike.
     *  2. The title's TROPDIR, whose subfolders ARE the NPWR ids the title ships. Used only
     *     when (1) is empty, which happens for a real reason: a game creates its trophy
     *     context lazily, often not until you reach a menu, so early in a boot the core
     *     genuinely does not know yet. This covers INSTALLED titles only — a disc game's
     *     TROPDIR is inside the ISO and never lands on the HDD.
     */
    fun loadCurrentGame(): Game? {
        val roots = trophyRoots()
        if (roots.isEmpty()) return null

        for (commId in currentGameCommIds()) {
            val dir = roots.asSequence().map { File(it, commId) }.firstOrNull { it.isDirectory }
                ?: continue
            readGame(dir)?.let { return it }
        }
        return null
    }

    /**
     * Candidate trophy folder names for the running game, best guess first. Empty when
     * nothing identifies it.
     */
    private fun currentGameCommIds(): List<String> {
        val fromCore = runCatching { RPCSX.instance.getCurrentTrophyName() }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (fromCore != null) return listOf(fromCore)

        val titleId = runCatching { RPCSX.instance.getTitleId() }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        // TROPDIR/<NPWRxxxxx_00>/TROPHY.TRP — the folder names are the comm ids.
        return File(hdd0(), "game/$titleId/TROPDIR").listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { it.name }
    }

    private fun readGame(dir: File): Game? {
        val conf = File(dir, "TROPCONF.SFM")
        if (!conf.isFile) {
            Log.i(TAG, "skipping ${dir.name}: no TROPCONF.SFM")
            return null
        }
        val parsed = runCatching { readTropConf(conf) }.getOrElse {
            Log.w(TAG, "failed to parse ${conf.absolutePath}", it)
            return null
        }
        if (parsed.trophies.isEmpty()) return null

        // Unlock state is optional: TROPUSR.DAT only exists once the game has registered its
        // trophy context. Without it every trophy simply reads as locked, which is correct.
        val state = runCatching { readTropUsr(File(dir, "TROPUSR.DAT")) }.getOrElse {
            Log.w(TAG, "failed to parse TROPUSR.DAT in ${dir.name}", it)
            emptyMap()
        }

        val trophies = parsed.trophies.map { def ->
            val entry = state[def.id]
            Trophy(
                id = def.id,
                name = def.name,
                description = def.detail,
                // ttype from the XML is what the native overlay uses; the grade duplicated in
                // TROPUSR table 4 is the fallback for a set with a missing/odd ttype.
                grade = def.grade.takeIf { it != Grade.Unknown } ?: entry?.grade ?: Grade.Unknown,
                hidden = def.hidden,
                unlocked = entry?.unlocked == true,
                unlockedAt = entry?.takeIf { it.unlocked }?.timestamp?.let(::tickToUnixMillis),
                // Locale.ROOT: the default locale would render the digits in its own numeral
                // system for e.g. Arabic, and the file name is ASCII.
                icon = File(dir, String.format(java.util.Locale.ROOT, "TROP%03d.PNG", def.id))
                    .takeIf { it.isFile },
            )
        }

        return Game(
            commId = dir.name,
            title = parsed.title.ifBlank { dir.name },
            detail = parsed.detail,
            icon = File(dir, "ICON0.PNG").takeIf { it.isFile },
            trophies = trophies,
        )
    }

    /**
     * A CellRtcTick to Unix millis, or null when it is absent or implausible.
     *
     * Range-checked rather than trusted: a tick of 0 means "no timestamp", and a corrupt
     * entry would otherwise render as a date in the year 1 or 30000.
     */
    private fun tickToUnixMillis(tick: Long): Long? {
        if (tick <= RTC_EPOCH_US) return null
        val millis = (tick - RTC_EPOCH_US) / 1000L
        // 1980-01-01 .. 2100-01-01. The PS3 itself did not exist before the lower bound.
        return millis.takeIf { it in 315_532_800_000L..4_102_444_800_000L }
    }

    // ---------------------------------------------------------------------------
    // TROPCONF.SFM (definitions)
    // ---------------------------------------------------------------------------

    private data class TrophyDef(
        val id: Int,
        val name: String,
        val detail: String,
        val grade: Grade,
        val hidden: Boolean,
    )

    private data class TropConf(
        val title: String,
        val detail: String,
        val trophies: List<TrophyDef>,
    )

    /**
     * Parse the definitions.
     *
     * The file is plain XML with a signature COMMENT before the root, and no XML
     * declaration — XmlPullParser handles both. Attribute names and the 'y' test on
     * `hidden` follow the native overlay's reload() exactly.
     */
    private fun readTropConf(file: File): TropConf {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            .newPullParser()
        var title = ""
        var titleDetail = ""
        val trophies = ArrayList<TrophyDef>()

        file.inputStream().use { stream ->
            parser.setInput(stream, null)
            // Fields of the <trophy> currently being read; null id = not inside one.
            var id: Int? = null
            var hidden = false
            var grade = Grade.Unknown
            var name = ""
            var detail = ""
            // Which leaf we are collecting text into. <name>/<detail> appear both at
            // trophyconf level (title-name/title-detail are separate tags) and inside a
            // <trophy>, so the text handler has to know where it is.
            var leaf = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (val tag = parser.name) {
                        "trophy" -> {
                            id = parser.getAttributeValue(null, "id")?.trim()?.toIntOrNull()
                            hidden = parser.getAttributeValue(null, "hidden")
                                ?.firstOrNull()?.lowercaseChar() == 'y'
                            grade = gradeOf(parser.getAttributeValue(null, "ttype"))
                            name = ""
                            detail = ""
                            leaf = ""
                        }
                        else -> leaf = tag
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text ?: ""
                        // Appended unconditionally, not skipped when blank: a parser is free to
                        // split a run of text at an entity reference, and dropping the blank
                        // pieces would silently glue "a & b" into "a&b". leaf is cleared on
                        // every END_TAG, so inter-element whitespace is never collected.
                        when {
                            leaf == "title-name" && id == null -> title += text
                            leaf == "title-detail" && id == null -> titleDetail += text
                            leaf == "name" && id != null -> name += text
                            leaf == "detail" && id != null -> detail += text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "trophy") {
                            id?.let {
                                trophies += TrophyDef(
                                    id = it,
                                    name = name.trim(),
                                    detail = detail.trim(),
                                    grade = grade,
                                    hidden = hidden,
                                )
                            }
                            id = null
                        }
                        leaf = ""
                    }
                }
                event = parser.next()
            }
        }

        return TropConf(title.trim(), titleDetail.trim(), trophies.sortedBy { it.id })
    }

    private fun gradeOf(ttype: String?): Grade = when (ttype?.firstOrNull()?.uppercaseChar()) {
        'B' -> Grade.Bronze
        'S' -> Grade.Silver
        'G' -> Grade.Gold
        'P' -> Grade.Platinum
        else -> Grade.Unknown
    }

    // ---------------------------------------------------------------------------
    // TROPUSR.DAT (unlock state)
    // ---------------------------------------------------------------------------

    private data class UsrEntry(val unlocked: Boolean, val timestamp: Long, val grade: Grade)

    /**
     * Parse the unlock state, keyed by trophy id.
     *
     * Layout, from TROPUSR.h:
     *   0x00  u32 magic, u32 unk1, u32 tables_count, u32 unk2, char reserved[32]
     *   0x30  tables_count * { u32 type, u32 entries_size, u32 unk1, u32 entries_count,
     *                          u64 offset, u64 reserved }        (32 bytes each)
     *   then, per table, entries_count records at `offset`.
     *
     * All big-endian.
     *
     * THE STRIDE IS NOT entries_size. entries_size is the size of an entry's PAYLOAD after
     * its 16-byte header (type/size/id/unk1), so a record is 16 + entries_size bytes:
     * table 4 reports 0x50 and its records are 96 bytes, table 6 reports 0x60 and its
     * records are 112 — which is exactly sizeof(TROPUSREntry4/6), the stride RPCS3 gets for
     * free by reading the structs directly. Using entries_size as the stride parses
     * garbage that still looks superficially plausible (verified against a real file: it
     * yielded 5 entries out of 29 and grade "unknown" for all of them).
     *
     * Table 4 carries the grade; table 6 the unlock flag and timestamps.
     */
    private fun readTropUsr(file: File): Map<Int, UsrEntry> {
        if (!file.isFile) return emptyMap()
        val bytes = file.readBytes()
        if (bytes.size < 0x30) return emptyMap()

        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        if (buf.getInt(0) != TROPUSR_MAGIC) {
            Log.w(TAG, "${file.name}: bad magic")
            return emptyMap()
        }
        val tableCount = buf.getInt(8)
        if (tableCount <= 0 || tableCount > 32) return emptyMap()

        val grades = HashMap<Int, Grade>()
        val states = HashMap<Int, Pair<Boolean, Long>>()

        for (t in 0 until tableCount) {
            val head = 0x30 + t * 32
            if (head + 32 > bytes.size) break
            val type = buf.getInt(head)
            val entrySize = buf.getInt(head + 4)
            val entryCount = buf.getInt(head + 12)
            val offset = buf.getLong(head + 16)
            if (entrySize <= 0 || entryCount <= 0 || offset < 0) continue
            val stride = 16 + entrySize
            // Longest field this reads is table 6's timestamp2, at body+24..body+31.
            val needed = 16 + 32
            for (i in 0 until entryCount) {
                val base = offset + i.toLong() * stride
                // Bounds-check the bytes actually read, not just the nominal record: a bogus
                // entries_size would otherwise let the last record's fields run off the end.
                if (base < 0 || base + maxOf(stride, needed) > bytes.size) break
                val body = (base + 16).toInt()
                when (type) {
                    4 -> {
                        val id = buf.getInt(body)
                        grades[id] = usrGradeOf(buf.getInt(body + 4))
                    }
                    6 -> {
                        val id = buf.getInt(body)
                        val unlocked = buf.getInt(body + 4) == 1
                        // timestamp1 at body+16, timestamp2 at body+24. RPCS3's
                        // GetTrophyTimestamp returns timestamp2; UnlockTrophy writes the same
                        // tick to both, so they agree in practice.
                        val timestamp = buf.getLong(body + 24)
                        states[id] = unlocked to timestamp
                    }
                    // Other tables are unused here, as in RPCS3.
                }
            }
        }

        return states.mapValues { (id, state) ->
            UsrEntry(unlocked = state.first, timestamp = state.second, grade = grades[id] ?: Grade.Unknown)
        }
    }

    /** TROPUSRLoader::trophy_grade — note it is NOT the same numbering as ttype. */
    private fun usrGradeOf(value: Int): Grade = when (value) {
        1 -> Grade.Platinum
        2 -> Grade.Gold
        3 -> Grade.Silver
        4 -> Grade.Bronze
        else -> Grade.Unknown
    }
}
