package com.armsx2.ui.xmb

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.input.DimensionsToyPad
import com.armsx2.input.InfinityBase
import com.armsx2.input.PositionPortal
import com.armsx2.input.SkylanderPortal
import com.armsx2.input.ToyPortals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier

/**
 * The toy portals as XMB lists, for the in-game Game column: the Skylanders portal, the Disney
 * Infinity base and the LEGO Dimensions toy pad, each with what stands on it, the figure files to
 * put there, Create figure and Add figure files. The same portals and files as the Portal panel
 * (ToyPortalPanel): SkylanderPortal, InfinityBase and DimensionsToyPad.
 */
@Composable
fun xmbPortalNode(controller: XmbController): XmbNode {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ok = fw("explore_plugin_full", "msg_ok", "xmb.ok")
    val icon = XmbIcon("explore_category_game", "item_tex_ps3util", "◎")
    val figureIcon = XmbIcon("explore_category_game", "item_tex_ps3game_folder", "◎")
    val portalTitle = str("portal.button")
    val placeTitle = str("portal.addFigure")
    val liftTitle = str("portal.lift")
    val emptyLabel = str("portal.emptyPosition")
    val onPortal = str("portal.pick.onPortal")
    val createTitle = str("portal.create")
    val addTitle = str("portal.add")
    val noFigures = str("portal.noFigures")
    val addedText = str("portal.added")
    val notAddedText = str("portal.notAdded")
    val failedText = str("portal.copyFailed")

    // Add figure files: the system picker, any number at once, copied into the portal's folder.
    // Which portal's folder the picked files go to, kept across the recomposition the picker causes.
    val addTo = remember { java.util.concurrent.atomic.AtomicReference<((List<ToyPortals.Offered>) -> ToyPortals.Added)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val add = addTo.get() ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) { add(uris.map { ToyPortals.offered(context, it) }) }
            val lines = buildList {
                if (added.count > 0) add(addedText.format(added.count))
                if (added.notFigures.isNotEmpty()) add(notAddedText.format(added.notFigures.joinToString()))
                if (added.failed.isNotEmpty()) add(failedText.format(added.failed.joinToString()))
            }
            if (lines.isNotEmpty()) controller.tell(lines.joinToString("\n"), ok)
        }
    }
    fun adder(key: String, add: (List<ToyPortals.Offered>) -> ToyPortals.Added) = XmbNode.Action(key, addTitle, icon) {
        addTo.set(add)
        picker.launch(arrayOf("*/*"))
    }

    fun name(file: String) = file.substringBeforeLast('.')

    /** Create figure: every figure the portal can make, by letter when there are many. */
    fun creator(key: String, characters: () -> List<ToyPortals.Character>, label: (ToyPortals.Character) -> String, create: (ToyPortals.Character) -> String?) =
        XmbNode.Folder(key, createTitle, icon) {
            fun item(c: ToyPortals.Character) = XmbNode.Action("$key.${c.first}.${c.second}.${c.name}", label(c), figureIcon) {
                scope.launch {
                    val made = withContext(Dispatchers.IO) { create(c) }
                    controller.tell(I18n.get(if (made != null) "portal.create.done" else "portal.create.failed").format(label(c)), ok)
                }
            }
            val all = characters().sortedBy { label(it).lowercase() }
            if (all.size <= 40) {
                all.map(::item)
            } else {
                all.groupBy { label(it).firstOrNull()?.uppercaseChar()?.takeIf(Char::isLetter) ?: '#' }
                    .toSortedMap()
                    .map { (letter, group) -> XmbNode.Folder("$key.$letter", letter.toString(), icon, "${group.size}") { group.map(::item) } }
            }
        }

    val skylanders = XmbNode.Folder(
        "portal.skylanders", "Skylanders", icon, live = true,
        aside = { _ -> com.armsx2.ui.emulation.XmbSkylanderArt(SkylanderPortal.placed, Modifier.fillMaxWidth(0.8f).aspectRatio(1f)) },
    ) {
        buildList {
            SkylanderPortal.placed.toSortedMap().forEach { (slot, file) ->
                add(XmbNode.Action("portal.sky.placed.$slot", name(file), figureIcon, "$onPortal: $liftTitle", options = listOf(XmbOption(liftTitle) { SkylanderPortal.lift(slot) })) {
                    SkylanderPortal.lift(slot)
                })
            }
            add(
                XmbNode.Folder("portal.sky.place", placeTitle, icon) {
                    val standing = SkylanderPortal.placed.values.toSet()
                    val figures = SkylanderPortal.figures().filter { it.name !in standing }
                    if (figures.isEmpty()) {
                        listOf(XmbNode.Action("portal.sky.none", noFigures.format(SkylanderPortal.folder().absolutePath), icon) {})
                    } else {
                        figures.map { f ->
                            XmbNode.Action("portal.sky.file.${f.name}", name(f.name), figureIcon) {
                                when (SkylanderPortal.place(f)) {
                                    SkylanderPortal.Result.Placed -> controller.back()
                                    SkylanderPortal.Result.Full -> controller.tell(I18n.get("portal.full"), ok)
                                    SkylanderPortal.Result.NotAFigure -> controller.tell(I18n.get("portal.notFigure").format(f.name), ok)
                                }
                            }
                        }
                    }
                },
            )
            add(creator("portal.sky.create", SkylanderPortal::characters, { it.name }, SkylanderPortal::create))
            add(adder("portal.sky.add", SkylanderPortal::add))
        }
    }

    /** The position a list is about, from the keys of the focused item and the open lists. */
    fun focusOf(keys: List<String>): Int? =
        keys.firstNotNullOfOrNull { k -> k.substringAfter("$KEY_POS", "").takeIf { k.contains(KEY_POS) }?.substringBefore('.')?.toIntOrNull() }

    fun positional(key: String, title: String, portal: PositionPortal, art: @Composable (Map<Int, String>, Int?) -> Unit) = XmbNode.Folder(
        key, title, icon, live = true,
        aside = { keys -> art(portal.placed, focusOf(keys)) },
    ) {
        buildList {
            portal.positions.forEach { (position, label) ->
                val (labelKey, arg) = label
                val where = if (arg != null) I18n.get(labelKey).format(arg) else I18n.get(labelKey)
                val standing = portal.placed[position]
                add(
                    XmbNode.Folder("$key.pos.$position", where, if (standing != null) figureIcon else icon, standing?.let(::name) ?: emptyLabel) {
                        buildList {
                            if (standing != null) {
                                add(XmbNode.Action("$key.pos.$position.lift", liftTitle, icon, name(standing)) {
                                    portal.lift(position)
                                    controller.back()
                                })
                            }
                            val figures = portal.figures()
                            if (figures.isEmpty()) {
                                add(XmbNode.Action("$key.none", noFigures.format(portal.folder().absolutePath), icon) {})
                            }
                            figures.forEach { f: File ->
                                // A figure stands in one place: off any other first, as the panel does.
                                val elsewhere = portal.placed.entries.firstOrNull { it.value == f.name && it.key != position }?.key
                                add(
                                    XmbNode.Action("$key.pos.$position.${f.name}", name(f.name), figureIcon, if (f.name == standing) where else null) {
                                        elsewhere?.let(portal::lift)
                                        if (portal.place(position, f)) controller.back()
                                        else controller.tell(I18n.get("portal.notFigure").format(f.name), ok)
                                    },
                                )
                            }
                        }
                    },
                )
            }
            add(creator("$key.create", portal::characters, portal::label, portal::create))
            add(adder("$key.add", portal::add))
        }
    }

    return XmbNode.Folder("ingame.portal", portalTitle, icon, "Skylanders, Disney Infinity, LEGO Dimensions") {
        listOf(
            skylanders,
            positional("portal.infinity", "Disney Infinity", InfinityBase) { placed, focus ->
                com.armsx2.ui.emulation.XmbInfinityArt(placed, focus, Modifier.fillMaxWidth().aspectRatio(1f / 0.74f))
            },
            positional("portal.dimensions", "LEGO Dimensions", DimensionsToyPad) { placed, focus ->
                com.armsx2.ui.emulation.XmbDimensionsArt(placed, focus, Modifier.fillMaxWidth().aspectRatio(1f / 0.74f))
            },
        )
    }
}

/** The part of a position item's key before its number ("portal.infinity.pos.3"). */
private const val KEY_POS = ".pos."
