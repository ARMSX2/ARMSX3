package com.armsx2.ui.xmb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.armsx2.GameInfo
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.mods.ModManager
import com.armsx2.ui.saves.SaveManagerViewModel
import com.armsx2.ui.saves.SaveStateItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The library's tools as XMB lists: the PKG/Data Manager, the save states, and the mods. Each is
 * the app's own screen composed out of sight (XmbCollector) or its own view model read live, so
 * it installs, loads and removes exactly as the screens do.
 */

/** The PKG/Data Manager: install from storage, what is installed, licences, game updates and DLC. */
@Composable
internal fun xmbPackagesNode(): XmbNode {
    val (on, off) = xmbOnOff()
    val icon = XmbIcon("explore_category_game", "item_tex_disc_install", "📦")
    val collector = remember(on, off) { XmbCollector(on, off, icon) }
    return XmbNode.Folder(
        "game.install",
        str("packages.title"),
        icon,
        str("xmb.packages.desc"),
        live = true,
        host = {
            CompositionLocalProvider(LocalXmbCollector provides collector) {
                com.armsx2.ui.packages.PackageInstallerScreen(onBack = {})
            }
        },
    ) { collector.nodes }
}

/** Every game that can take mods, each opening its mods (the game's Mods tab, as a list). */
@Composable
internal fun xmbModsNode(): XmbNode {
    val context = LocalContext.current
    val (on, off) = xmbOnOff()
    val icon = XmbIcon("explore_category_game", "item_tex_ps3game_folder", "🛠")
    val none = str("mods.library.none")
    val count = str("mods.library.count")
    val empty = str("mods.library.empty")
    val games by produceState(emptyList<Pair<GameInfo, Pair<Int, Int>>>()) {
        value = withContext(Dispatchers.IO) {
            com.armsx2.data.library.GameLibraryRepository(context).loadCached().games
                .mapNotNull { game ->
                    val serial = game.serial?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    if (!ModManager.isModdable(game.extension, serial)) return@mapNotNull null
                    val mods = ModManager.list(serial)
                    game to (mods.size to mods.count { it.enabled })
                }
                .distinctBy { it.first.serial }
                .sortedBy { it.first.title.lowercase() }
        }
    }
    val collectors = remember(on, off) { HashMap<String, XmbCollector>() }
    return XmbNode.Folder("game.mods", str("mods.library.title"), icon, str("mods.requirement").substringBefore(". ") + ".") {
        if (games.isEmpty()) {
            listOf(XmbNode.Action("mods.none", empty, icon) {})
        } else {
            games.map { (game, counts) ->
                val serial = game.serial.orEmpty()
                val collector = collectors.getOrPut(serial) { XmbCollector(on, off, icon) }
                XmbNode.Folder(
                    "mods.game.$serial",
                    game.title,
                    XmbIcon(model = XmbMedia.icon0(game, null), glyph = "🎮"),
                    if (counts.first == 0) none else count.format(counts.first, counts.second),
                    live = true,
                    host = {
                        CompositionLocalProvider(LocalXmbCollector provides collector) {
                            com.armsx2.ui.mods.ModsTab(serial)
                        }
                    },
                ) { collector.nodes }
            }
        }
    }
}

/**
 * Load / Manage Saves: each game's save states, with their pictures, newest slot first as the
 * manager orders them. Cross loads one (booting its game when nothing is running), Triangle offers
 * Load and Delete. Then Backup, and deleting every state.
 */
@Composable
internal fun xmbSavesNode(): XmbNode {
    val vm: SaveManagerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "xmb.saves")
    val context = LocalContext.current
    val ok = str("action.ok")
    val cancel = str("action.cancel")
    val loadWord = str("games.loadState")
    val loadAsk = str("xmb.saves.loadAsk")
    val deleteWord = str("action.delete")
    val deleteAsk = str("savestate.delete.body")
    val autosave = str("savestate.autosave.title")
    val slot = str("memcard.slot1").substringBefore(' ')
    val backup = str("savestate.backup")
    val wipe = str("savestate.wipe")
    val wipeAsk = listOf(str("savestate.wipe.confirm.title"), str("savestate.wipe.confirm.body")).joinToString("\n")
    val noneTitle = str("savestate.empty.title")
    val noneDesc = str("savestate.empty.description")
    val icon = XmbIcon("explore_category_game", "item_tex_ps3util", "💾")
    val locale = remember(I18n.current) { java.util.Locale.forLanguageTag(I18n.current) }
    val date = remember(locale) {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMd")
        val time = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a"
        java.text.SimpleDateFormat("$pattern $time", locale)
    }
    // Pictures made once each, not on every read of the list.
    val pictures = remember { HashMap<String, ImageBitmap>() }

    fun ask(question: String, confirm: String, onYes: () -> Unit) {
        XmbDialogs.target()?.ask(question, confirm, cancel, focusNo = true, onYes = onYes)
    }

    fun name(item: SaveStateItem): String = when (val s = item.slot) {
        null -> item.file.nameWithoutExtension
        com.armsx3.Rpcs3Bridge.AUTOSAVE_SLOT -> autosave
        else -> "$slot ${s + 1}"
    }

    fun stateNode(item: SaveStateItem): XmbNode {
        val path = item.file.absolutePath
        val picture = item.preview?.let { p -> pictures.getOrPut(path) { p.asImageBitmap() } }
        val load = { ask(loadAsk, loadWord) { vm.load(item) } }
        val delete = { ask(deleteAsk.format((item.slot ?: 0) + 1), deleteWord) { vm.delete(item) } }
        return XmbNode.Action(
            "saves.state.$path",
            name(item),
            if (picture != null) XmbIcon(bitmap = picture, shadow = false) else icon,
            date.format(java.util.Date(item.file.lastModified())),
            options = listOf(XmbOption(loadWord) { load() }, XmbOption(deleteWord) { delete() }),
        ) { load() }
    }

    return XmbNode.Folder(
        "game.saves",
        str("savestate.title.loadManage"),
        icon,
        live = true,
        host = {
            LaunchedEffect(Unit) { vm.open(null) }
            val s = vm.state.value
            LaunchedEffect(s.message, s.error) {
                val m = s.error ?: s.message ?: return@LaunchedEffect
                XmbDialogs.target()?.tell(m, ok, error = s.error != null)
                vm.dismissMessage()
            }
        },
    ) {
        val s = vm.state.value
        buildList {
            when {
                s.loading && s.saves.isEmpty() -> add(XmbNode.Action("saves.loading", "…", icon) {})
                s.saves.isEmpty() -> add(XmbNode.Action("saves.none", noneTitle, icon, noneDesc) {})
            }
            s.saves.groupBy { it.serial.uppercase() }.forEach { (serial, items) ->
                val first = items.firstOrNull { it.preview != null } ?: items.first()
                val picture = first.preview?.let { p -> pictures.getOrPut(first.file.absolutePath) { p.asImageBitmap() } }
                add(
                    XmbNode.Folder(
                        "saves.game.$serial",
                        items.first().gameTitle.ifBlank { serial },
                        if (picture != null) XmbIcon(bitmap = picture, shadow = false) else icon,
                        "$serial   ${items.size}",
                        live = true,
                    ) { vm.state.value.saves.filter { it.serial.equals(serial, ignoreCase = true) }.map(::stateNode) },
                )
            }
            if (s.saves.isNotEmpty()) add(XmbNode.Action("saves.backup", backup, icon) { vm.backupAll() })
            add(XmbNode.Action("saves.wipe", wipe, icon) { ask(wipeAsk, deleteWord) { vm.wipeAll() } })
        }
    }
}
