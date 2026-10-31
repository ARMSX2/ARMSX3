package com.armsx2.config

import androidx.core.content.edit
import com.armsx2.runtime.MainActivityRuntime
import com.armsx3.core.ARMSX3
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * RPCS3's per-title recommended settings, fetched from its config database.
 *
 * This is the same database the desktop build offers under "Download Config Database": a
 * curated set of settings that make specific titles work, maintained by the RPCS3 project.
 * The core already knows how to use it. Emulator::Load asks for a title's config through
 * the get_database_config callback and passes it to BootGame as db_config, which layers it
 * under the user's own settings.
 *
 * Desktop does the download and the JSON parsing in Qt, which is not part of this build, so
 * that half lives here. The response is
 *
 *     { "return_code": 0, "games": { "BLUS30443": { "config": "<yaml>" }, ... } }
 *
 * and is split into one file per title under config/config_db/, which is all the native
 * callback then has to read.
 *
 * Splitting rather than keeping the blob whole is deliberate: the callback runs on the boot
 * path, and re-parsing a database of every PS3 game to find one entry would be wasteful.
 */
object ConfigDatabase {
    private const val URL_V1 = "https://api.rpcs3.net/config/?api=v1"
    private const val KEY_UPDATED = "configDb.updatedAt"
    private const val KEY_COUNT = "configDb.titleCount"
    private const val KEY_FORMAT = "configDb.formatVersion"
    private const val KEY_ENABLED = "configDb.enabled"

    /** Bump to discard already-split configs, e.g. after changing [UNSAFE_ON_ANDROID]. */
    private const val FORMAT_VERSION = 3

    /**
     * Settings from the database that must not be applied on this port.
     *
     * The database is maintained against desktop RPCS3. A setting that is merely a tuning
     * choice there can be broken or unimplemented here, and the entries are per-title, so a
     * bad one silently breaks exactly the game it was supposed to help.
     *
     * Matched on the setting name, which is the part before the colon in the YAML. The rest
     * of the entry is kept: these files are mostly LLE library lists and buffer settings,
     * which are the reason the database is worth having.
     *
     * Keep this to settings with EVIDENCE, not suspicion. Every entry should name what broke.
     */
    private val UNSAFE_ON_ANDROID = setOf(
        // Max SPURS Threads: a workaround for LOW-CORE-COUNT DESKTOPS, which no Android device is.
        //
        // Its own tooltip says it "may improve performance ... especially on systems with limited
        // number of hardware threads". Every ARMSX3 device has 8 cores -- armv8.1-a is the compile
        // floor, so nothing weaker can run this at all -- and capping SPURS below that starves the
        // job chain instead of helping it.
        //
        // Measured on Sonic Unleashed (BLUS30244), Snapdragon 8 Elite: the database forces 3, and
        // uncapping to 6 took the problem hub area from 11.1 to 19.1 fps, with useful SPU work
        // rising 27% -> 37%. Nothing else in the entry mattered; this one value was serialising
        // the primary SPURS chain.
        //
        // 19 of the database's 2194 titles set this (12 at 3, 7 at 4), so the blast radius is
        // small and it is wrong for all of them on this platform for the same structural reason.
        // Only Unleashed was measured, and the tooltip does warn the cap is sometimes load-bearing
        // against crashes -- if a title in that list regresses, this is the entry to revisit.
        "Max SPURS Threads",

        // "Multithreaded RSX" was listed here on the belief that it froze
        // Minecraft. It did not: the freeze was Accurate SPU DMA plus Accurate Cache Line
        // Stores turning every SPU DMA into an atomic reservation store, and it reproduced
        // with Multithreaded RSX off and an empty database. Multithreaded RSX is a real
        // upstream feature backed by the RSXOffload thread, not a desktop-only path, so
        // there was never evidence against it. Use the enable toggle to A/B the database
        // rather than denying a setting on suspicion.
    )

    /**
     * Settings that are fine in general but have specific VALUES that are wrong on a handheld.
     *
     * Separate from UNSAFE_ON_ANDROID because denying the whole setting would throw away the
     * useful values with the harmful one. Matched on name AND value; anything not listed is kept.
     */
    private val UNSAFE_VALUES_ON_ANDROID = mapOf(
        // Frame limit "Off" and "Infinite" both uncap the presented frame rate entirely.
        //
        // RSXThread.cpp resolves both to limit = 0., which skips the pacing block outright:
        // "Off" via `case frame_limit_type::none` (only when Max CPU Preempt Count is 0, which is
        // the default and the shipped value), and "Infinite" via `case frame_limit_type::infinite`.
        //
        // Measured on Minecraft: PlayStation(R)3 Edition (BLUS31426), Snapdragon 8 Elite: the
        // database sets "Off" and the game ran at roughly 1000 fps. On a desktop that is a
        // reasonable thing to recommend. On a handheld it is a hot SoC, a flat battery, and the
        // "games running too fast" reports.
        //
        // 11 of the database's 2195 titles are affected (5 "Off", 6 "Infinite"). Dropping the line
        // falls back to the global Frame limit, which is Auto -> vblank_rate -> 60.
        //
        // "PS3 Native" also resolves to limit = 0. but is NOT listed: it has its own pacing path
        // further down the same function and is the only mode that honours the game's own
        // cellGcmSetFlipMode(VSYNC) request, which titles that pace themselves to 30 fps need.
        "Frame limit" to setOf("Off", "Infinite"),
    )

    /**
     * ARMSX3's own per-title settings, layered on top of whatever the RPCS3 database says.
     *
     * The upstream database is maintained against desktop, where some of our problems do not
     * exist, so there is nowhere upstream to put a fix that is specific to this port. These are
     * applied whether or not the database was ever downloaded, and re-applied after every
     * refresh -- refresh() clears the directory first, so anything written once would not
     * survive it.
     *
     * Keep this SMALL and evidenced. Every entry names the issue it answers.
     */
    private val LOCAL_OVERRIDES: Map<String, String> = mapOf(
        // Tales of Symphonia Chronicles: reported running at 60fps when the game targets 30,
        // with battles playing at double speed (issue #77).
        //
        // PS3 Native is the only Frame limit mode that honours the game's own
        // cellGcmSetFlipMode(CELL_GCM_DISPLAY_VSYNC); every other mode flips immediately, so a
        // title that paces itself by flipping on alternate vblanks free-runs to the 60 cap.
        //
        // The risk if this is ever wrong for a title: PS3 Native applies NO limit when the game
        // does not request vsync, so it would run unbounded instead of capped. Only add a serial
        // here once someone has confirmed the mode helps on that game.
        "BLUS31213" to "Video:\n  Frame limit: PS3 Native\n",
        "BLES01935" to "Video:\n  Frame limit: PS3 Native\n",

        // Tales of Graces f (issue #210): the database's PS3 Native holds the field at 30 but lets battle
        // drop to 30 to 50 fps, where the PS3 holds 60, since a frame that misses its vblank by a little
        // waits a whole extra one. At 60 battle holds 60, and the bundled "Field at 30 fps with a 60 fps
        // limit" patch keeps the field at 30 and normal speed. Tied to the patch: without it the field
        // runs at 60 and double speed here.
        "BLUS30903" to "Video:\n  Frame limit: 60\n",

        // J-Stars Victory VS+: Shader Precision High. On the Android default, Low, a battle draws
        // nothing: the game keeps presenting at 50 fps with its music playing, but every frame is
        // black, while the menus draw fine. Half-precision math in the battle's shaders is the
        // difference; High draws the battle (BLUS31519, Odin 3, Turnip, 2026-10-24). EU (BLES02136)
        // and JP (BLJS10257) run the same game, and High is the accurate setting, so it cannot hurt
        // them. A Shader Precision set for the game itself still wins.
        "BLUS31519" to "Video:\n  Shader Precision: High\n",
        "BLES02136" to "Video:\n  Shader Precision: High\n",
        "BLJS10257" to "Video:\n  Shader Precision: High\n",

        // Helldivers: the database forces the PPU interpreter because the recompiler hung it on a
        // black screen (RPCS3 issue #5831). The hang was LuaJIT's integer arithmetic on
        // mullwo./subfo. and bso, whose overflow flag the recompiler never set correctly; fixed in
        // the translator, after which the game goes in-game on the recompiler at 60 fps against
        // 36 on the interpreter (Odin 3, 2026-09-17).
        "NPUA80930" to "Core:\n  PPU Decoder: Recompiler (LLVM)\n",

        // Don't Starve: Giant Edition, forced to the interpreter for the same reason and fixed by
        // the same overflow flag work. Goes in-game on the recompiler at its 30 fps cap
        // (Odin 3, 2026-09-17).
        "NPUB31590" to "Core:\n  PPU Decoder: Recompiler (LLVM)\n",

        // Killzone 3: the database turns Write Color Buffers on so RPCS3's SPU MLAA pass can read
        // each frame back from memory. The "Disable MLAA (Post-processing on SPU)" patch, bundled
        // on for this title since 4009bb5551, removes that pass, and the RPCS3 wiki says the
        // setting is only needed without the patch. Off skips the readback of every color buffer.
        // Tied to the patch: anyone who turns the patch off needs this back on.
        //
        // Preferred SPU Threads pinned to 0 (Auto), issue #151. A tester running it at 3 froze at
        // the same spot every time, as the prologue streams in the factory approach: SPU jobs read
        // a structure the game had already freed and overwrote SPURS kernel code at LS 0x28e0.
        // Capping how many SPU threads run at once makes jobs run late, which is what exposes that
        // race. Back on 0 he got through, and the crash never appeared in 38 of our own runs at 0.
        // Pinned here, per title, so a global 3 cannot bring it back.
        "BCUS98234" to "Video:\n  Write Color Buffers: false\nCore:\n  Preferred SPU Threads: 0\n",
        "BCES01007" to "Video:\n  Write Color Buffers: false\nCore:\n  Preferred SPU Threads: 0\n",

        // Spider-Man: Web of Shadows: Accurate SPU Reservations off.
        //
        // With it on, the SPURS kernel's own atomics on its control block (swap, compare-and-swap
        // and decrement helpers at LS 0x0221c / 0x01e68 / 0x01f28, each changing one quadword)
        // take vm::writer_lock about 550,000 times a second, and each one stops every PPU thread.
        // Off, they take the SPURS shortcut and the one-quadword path from RPCS3 PR #19568: about
        // 1,400 barriers a second, long writer_lock stalls down from 50-75 ms to 1.6 ms per second
        // of play, and the game runs clearly smoother swinging through the city (Odin 3, 09-29).
        //
        // Off is off-spec and has broken other titles (see ConfigStore KEY_PERGAME_RSV_CLEARED), so
        // it is this title only, and the per-game database toggle takes it back.
        "BLUS30218" to "Core:\n  Accurate SPU Reservations: false\n",

        // The Elder Scrolls V: Skyrim: Accurate SPU Reservations off, for the same reason as Web of
        // Shadows.
        //
        // With it on, the SPURS kernel's stores to its control block (LS 0x011e4, 0x00660, 0x01238,
        // 0x00c24) take vm::writer_lock about 380,000 times a second in busy scenes: 48 ms of PPU
        // waits a second, single waits up to 36 ms. Off, about 21,000 a second, 18 ms, longest 7 ms,
        // and the game plays normally from the title screen through a loaded save (Odin 3, 10-27).
        // Measured on the US Legendary Edition. The original release (patched to 1.09) is the same
        // engine build, so every release gets it.
        "BLUS31202" to "Core:\n  Accurate SPU Reservations: false\n",
        "BLES01885" to "Core:\n  Accurate SPU Reservations: false\n",
        "BLES01886" to "Core:\n  Accurate SPU Reservations: false\n",
        "BLUS30778" to "Core:\n  Accurate SPU Reservations: false\n",
        "BLES01329" to "Core:\n  Accurate SPU Reservations: false\n",
        "BLES01330" to "Core:\n  Accurate SPU Reservations: false\n",

        // Blades of Time: SPU Cache off.
        //
        // When the game's saved SPU list is built at startup, its JobMgr#1 thread reads a null
        // pointer 0.1 s after it starts (guest pc 0x002af630) and the emulator freezes. That is
        // every boot after the first, since the first one writes the list. A boot that compiles
        // SPU code as the game runs gets past it, with the PPU cache warm too (Odin 3, Turnip,
        // 10-02). Off keeps every boot on that path; the cost is SPU compile stutter early in each
        // session. The EU disc is the same game and was not tested on its own.
        "BLUS30833" to "Core:\n  SPU Cache: false\n",
        "BLES01395" to "Core:\n  SPU Cache: false\n",

        // Grand Theft Auto IV: SPU XFloat Accuracy on Approximate instead of the database's Accurate.
        //
        // The database pins Accurate because Niko falls through the world on Approximate: the
        // collision job seeds its searches with the SPU's largest float, which Approximate reads as
        // a NaN. The bundled "ARMSX3 Approximate collision fix" (Ps3PatchRepo.BUNDLED, on by default
        // for these three serials) seeds them with FLT_MAX instead, and with it the game stays on
        // the ground on Approximate (Odin 3, 10-12). Accurate costs SPU time the game is short of.
        //
        // The patch finds the job by hash, taken from BLUS30682 v01.02, which is the same executable
        // as BLUS30127 / NPUB30702 v01.08. A version without the update may carry a different job,
        // and there Approximate drops Niko through the world again; the per-game database toggle
        // takes this back. Tied to the patch: anyone who turns the patch off needs Accurate back.
        "BLUS30682" to "Core:\n  SPU XFloat Accuracy: Approximate\n",
        "BLUS30127" to "Core:\n  SPU XFloat Accuracy: Approximate\n",
        "NPUB30702" to "Core:\n  SPU XFloat Accuracy: Approximate\n",

        // 3D Dot Game Heroes: PPU Event Wake-Up Delay 35 us (upstream yahfz #19824, RPCS3 issue #4433).
        //
        // An SPU signals the PPU through an event queue when a SPURS task is done, and the PPU can
        // wake and free the task's context before the SPU has finished saving it; the late save
        // corrupts the heap and the next malloc crashes. The delay holds the woken PPU back for this
        // long. yahfz found 25 us on his PC; a tester (nassau-tk, Retroid Pocket 6) plays at 35 with
        // no freeze, 10-25, so ARMSX3 uses 35. Off (0) for every other game. Installs that cached 25
        // get this appended after it at the next start (applyLocalOverrides), and the last one wins.
        "BLUS30490" to "Core:\n  PPU Event Wake-Up Delay: 35\n",
        "BLES00875" to "Core:\n  PPU Event Wake-Up Delay: 35\n",
        "BLJM60180" to "Core:\n  PPU Event Wake-Up Delay: 35\n",
        "BLUD80019" to "Core:\n  PPU Event Wake-Up Delay: 35\n",

        // Blitz: The League II: SPU MMIO Delay on at 300 us (upstream elad335 #19851, RPCS3 issue
        // #11529), Pause Game During SPU Compilation, and a 3072 MB VRAM allocation limit.
        //
        // The game runs its jobs on three raw SPUs, which park themselves after a set number of idle
        // passes; that is what stalled the data install and froze matches, and the bundled "ARMSX3
        // raw SPU park fix" patch (canary_patches.yml) keeps them running. The delay holds the PPU
        // back after every mailbox write, as RPCS3 needs for this game; Elad's 100 us is enough on his
        // PC, 300 is what a ROG Ally X player needed and what the Odin 3 runs. The pause hides
        // on-demand SPU compiles from the game; with it a cold install went through on the first try
        // (10-19), before the park fix existed, so it may no longer be needed.
        //
        // A match load peaks at about 2.2 GB of video memory here (ring buffers ~1.1 GB, texture cache
        // ~0.9 GB; Odin 3, 10-19), past the 2048 MB cap phones get, and the RSX thread died on it;
        // 3072 MB holds it with about 870 MB to spare. This title only: the cap exists because video
        // memory is the phone's RAM. All of this is off for every other game: in Sonic '06 the delay
        // alone halves the frame rate (Odin 3, 10-14). The EU disc is the same game and was not tested.
        "BLUS30203" to "Core:\n  SPU MMIO Delay Enabled: true\n  SPU MMIO Delay: 300\n  Pause Game During SPU Compilation: true\nVideo:\n  Vulkan:\n    VRAM allocation limit (MB): 3072\n",
        "BLES00397" to "Core:\n  SPU MMIO Delay Enabled: true\n  SPU MMIO Delay: 300\n  Pause Game During SPU Compilation: true\nVideo:\n  Vulkan:\n    VRAM allocation limit (MB): 3072\n",
        // Sonic Generations: MFC command shuffling at 1, upstream's "Delay Each Odd MFC Command"
        // (elad335, RPCS3 #19873 + #19891: "stable and graphics are good with the MFC option").
        // Without it the RSDX job system stops an SPU with STOP 0 after a while (RPCS3 issue #13072).
        // Retail US/EU, the EU PSN release and the six demos. Off for every other game.
        "BLUS30612" to "Core:\n  MFC Commands Shuffling Limit: 1\n",
        "BLES01236" to "Core:\n  MFC Commands Shuffling Limit: 1\n",
        "NPEB00813" to "Core:\n  MFC Commands Shuffling Limit: 1\n",
        "NPEB90337" to "Core:\n  MFC Commands Shuffling Limit: 1\n",
        "NPEB90343" to "Core:\n  MFC Commands Shuffling Limit: 1\n",
        "NPHB00369" to "Core:\n  MFC Commands Shuffling Limit: 1\n",
        "NPHB00446" to "Core:\n  MFC Commands Shuffling Limit: 1\n",
        "NPUB90567" to "Core:\n  MFC Commands Shuffling Limit: 1\n",
        "NPUB90604" to "Core:\n  MFC Commands Shuffling Limit: 1\n",

    )

    /**
     * Write the local overrides, merging under any database entry for the same title.
     *
     * Ours go LAST in the file so they win: the core parses the YAML in order and a later key
     * of the same name replaces an earlier one.
     */
    fun ensureLocalOverrides() = applyLocalOverrides()

    private fun applyLocalOverrides() {
        val dir = liveDir().apply { mkdirs() }

        for ((serial, yaml) in LOCAL_OVERRIDES) {
            runCatching {
                val file = File(dir, "$serial.yml")
                val existing = if (file.isFile) file.readText() else ""
                // Already applied. Checking for the override itself rather than for the setting's
                // name, which skipped an override whenever the database set the same key, the one
                // case it exists for.
                if (existing.contains(yaml.trimEnd())) return@runCatching
                file.writeText(if (existing.isBlank()) yaml else existing.trimEnd() + "\n" + yaml)
            }
        }
    }

    /** Drop denied settings from one title's YAML, keeping everything else intact. */
    private fun sanitise(config: String): String =
        config.lineSequence()
            .filterNot { line ->
                val name = line.substringBefore(':').trim().removePrefix("- ")
                if (name in UNSAFE_ON_ANDROID) return@filterNot true

                // Value-based entries: only drop the line when the value is one of the bad ones,
                // so a title that legitimately asks for "30" keeps it.
                val bad = UNSAFE_VALUES_ON_ANDROID[name] ?: return@filterNot false
                line.contains(':') && line.substringAfter(':').trim() in bad
            }
            .joinToString("\n")

    // ---- What the database sets for one title, and what the user took back ----

    /**
     * The settings a title's database entry applies, as "Section@@Key" to value, in file order.
     *
     * The last occurrence of a key wins, matching how the core applies them, so a
     * [LOCAL_OVERRIDES] entry appended to the end reads back as the effective value here too.
     * Two spaces per level, which is what the database ships and what RPCS3 writes.
     */
    fun entriesFor(serial: String?): List<Pair<String, String>> {
        val title = serial?.trim().orEmpty()
        if (title.isEmpty()) return emptyList()
        val file = File(directory(), "$title.yml")
        if (!file.isFile) return emptyList()

        val out = LinkedHashMap<String, String>()
        val path = ArrayList<String>()

        runCatching {
            for (raw in file.readLines()) {
                val line = raw.trimEnd()
                val first = line.indexOfFirst { it != ' ' }
                if (first < 0 || line[first] == '#') continue
                val colon = line.indexOf(':', first)
                if (colon < 0) continue

                val indent = first / 2
                while (path.size > indent) path.removeAt(path.size - 1)
                path.add(line.substring(first, colon).trim())

                val value = line.substring(colon + 1).trim()
                // A header opens a section instead of setting anything.
                if (value.isNotEmpty()) out[path.joinToString("@@")] = value
            }
        }

        return out.toList()
    }

    /** Keys of a title's database entry the user switched off. */
    fun ignoredFor(serial: String?): Set<String> {
        val title = serial?.trim().orEmpty()
        if (title.isEmpty()) return emptySet()
        val raw = MainActivityRuntime.prefs.getString(ignoreKey(title), null) ?: return emptySet()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
        }.getOrDefault(emptySet())
    }

    fun setIgnored(serial: String, path: String, ignored: Boolean) {
        val current = ignoredFor(serial).toMutableSet()
        if (ignored) current.add(path) else current.remove(path)
        val arr = org.json.JSONArray()
        current.forEach { arr.put(it) }
        MainActivityRuntime.prefs.edit { putString(ignoreKey(serial), arr.toString()) }
    }

    private fun ignoreKey(serial: String) = "configDb.ignore.$serial"

    /**
     * Tell the core which keys of this title's database entry not to apply.
     *
     * The core layers the database over the user's config, so a per-game choice loses to a
     * database entry for the same key unless the key is withheld here. Written before boot and
     * read by get_database_config; the file is removed when nothing is withheld, so a title
     * that never needed one does not accumulate an empty file.
     */
    fun writeUserKeys(serial: String?, ownedPaths: Set<String>) {
        val title = serial?.trim().orEmpty()
        if (title.isEmpty()) return

        val dir = File(ARMSX3.rootDirectory, "config/config_db_user")
        val file = File(dir, "$title.keys")
        // Only keys this title's entry actually sets: withholding a key it never had would be a
        // line of noise in the file and in the log.
        val settable = entriesFor(title).map { it.first }.toSet()
        val keys = (ownedPaths + ignoredFor(title)).filter { it in settable }.sorted()

        runCatching {
            if (keys.isEmpty()) {
                file.delete()
                return
            }
            dir.mkdirs()
            file.writeText(keys.joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * Where the native side looks. get_database_config reads config/config_db/<TITLE>.yml
     * on the boot path, so this exact name is what makes the database live.
     */
    private fun liveDir(): File = File(ARMSX3.rootDirectory, "config/config_db")

    /**
     * Where the split files sit while the database is switched off.
     *
     * Disabling moves them aside rather than deleting them, so a tester can flip the
     * database on and off across boots to compare without re-downloading 2000-odd titles
     * each time, which is the whole point of having the switch.
     */
    private fun parkedDir(): File = File(ARMSX3.rootDirectory, "config/config_db_off")

    private fun directory(): File =
        (if (isEnabled()) liveDir() else parkedDir()).apply { mkdirs() }

    /** Whether downloaded configs are being applied at boot. */
    fun isEnabled(): Boolean = MainActivityRuntime.prefs.getBoolean(KEY_ENABLED, true)

    /**
     * Turn the database on or off, keeping whatever was downloaded.
     *
     * A single rename either exposes the files under the name the native callback reads or
     * hides them from it, so nothing has to be re-parsed and the core needs no flag of its
     * own. Takes effect on the next boot, since the config is read during Emulator::Load.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled()) return
        val from = if (enabled) parkedDir() else liveDir()
        val to = if (enabled) liveDir() else parkedDir()
        runCatching {
            if (from.isDirectory) {
                if (to.exists()) to.deleteRecursively()
                from.renameTo(to)
            }
        }
        MainActivityRuntime.prefs.edit { putBoolean(KEY_ENABLED, enabled) }

        // Ours are ARMSX3 fixes, not part of the downloaded database, so they stay live
        // either way -- the toggle above just moved them into the parked directory.
        applyLocalOverrides()
    }

    /**
     * Delete everything downloaded and go back to stock settings.
     *
     * Both directories go, so this is a true revert rather than a disable: the row returns
     * to offering a download and no title carries a database setting any more.
     */
    fun remove() {
        runCatching { liveDir().deleteRecursively() }
        runCatching { parkedDir().deleteRecursively() }
        MainActivityRuntime.prefs.edit {
            putInt(KEY_COUNT, 0)
            remove(KEY_UPDATED)
            putBoolean(KEY_ENABLED, true)
        }
    }

    /**
     * Throw away configs split by an older build.
     *
     * The files on disk are the output of [sanitise], so when the deny list changes they are
     * stale in a way that matters: a user who downloaded before a setting was found to be
     * broken would keep applying it forever, since nothing re-downloads on its own.
     *
     * Discarding rather than re-fetching, because this runs at startup and must not make a
     * network call. The row goes back to offering a download.
     */
    fun purgeIfStale() {
        if (MainActivityRuntime.prefs.getInt(KEY_FORMAT, 0) == FORMAT_VERSION) return
        if (titleCount() == 0) return

        // Both, since the parked copy is restored verbatim by the enable toggle and would
        // otherwise carry stale sanitising back in the moment someone switched it on.
        runCatching { liveDir().listFiles()?.forEach { it.delete() } }
        runCatching { parkedDir().listFiles()?.forEach { it.delete() } }
        MainActivityRuntime.prefs.edit {
            putInt(KEY_COUNT, 0)
            remove(KEY_UPDATED)
            putInt(KEY_FORMAT, FORMAT_VERSION)
        }
    }

    /** Titles currently stored, 0 when the database has never been fetched. */
    fun titleCount(): Int = MainActivityRuntime.prefs.getInt(KEY_COUNT, 0)

    /** Epoch millis of the last successful fetch, or 0. */
    fun updatedAt(): Long = MainActivityRuntime.prefs.getLong(KEY_UPDATED, 0L)

    fun hasConfigFor(serial: String?): Boolean {
        val id = serial?.takeIf { it.isNotBlank() } ?: return false
        return File(directory(), "${id.uppercase()}.yml").isFile
    }

    /**
     * Fetch and split the database. Blocking, so call it off the main thread.
     *
     * Returns the number of titles written, or -1 on failure. Existing files are replaced
     * wholesale rather than merged: the database is authoritative and a stale entry for a
     * title that has since been fixed would otherwise persist forever.
     */
    fun refresh(): Int {
        val body = runCatching {
            val connection = (URL(URL_V1).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                // The API rejects requests without one.
                setRequestProperty("User-Agent", "ARMSX3")
            }
            try {
                if (connection.responseCode !in 200..299) return -1
                connection.inputStream.bufferedReader().readText()
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return -1

        return runCatching {
            val json = JSONObject(body)
            // Negative codes are the server reporting an error rather than data:
            // -1 internal, -2 maintenance. Treat them as a failed fetch and keep
            // whatever is already on disk.
            if (json.optInt("return_code", -255) < 0) return -1

            val games = json.optJSONObject("games") ?: return -1
            val dir = directory()
            dir.listFiles()?.forEach { it.delete() }

            var written = 0
            for (serial in games.keys()) {
                val config = games.optJSONObject(serial)?.optString("config").orEmpty()
                if (config.isBlank()) continue
                val safe = sanitise(config)
                if (safe.isBlank()) continue
                runCatching {
                    File(dir, "${serial.uppercase()}.yml").writeText(safe)
                    written++
                }
            }

            // After the split, not before: refresh() clears the directory above.
            applyLocalOverrides()

            MainActivityRuntime.prefs.edit {
                putInt(KEY_COUNT, written)
                putLong(KEY_UPDATED, System.currentTimeMillis())
                putInt(KEY_FORMAT, FORMAT_VERSION)
            }
            written
        }.getOrDefault(-1)
    }
}
