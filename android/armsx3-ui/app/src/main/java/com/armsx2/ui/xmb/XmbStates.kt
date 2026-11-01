package com.armsx2.ui.xmb

import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.ui.InGameOverlay
import com.armsx3.NativeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Save states as XMB lists, for the in-game XMB: Save State lists the ten slots, Load State the
 * ones that hold a state (and the state saved on exit), each with its picture and when it was
 * saved, as the console lists saved data. Cross saves or loads and goes back to the game; Triangle
 * deletes. The same slots and calls as the save state screen (SaveStatePicker).
 */
object XmbStates {
    const val SLOTS = 10

    class Slot(val index: Int, val used: Boolean, val savedAt: Long, val picture: ImageBitmap?)

    /** The slots as last read; null until read. */
    val slots = mutableStateOf<List<Slot>?>(null)
    val autosave = mutableStateOf(false)

    /** Read every slot again. IO thread. */
    fun read() {
        slots.value = (0 until SLOTS).map { i ->
            val used = runCatching { NativeApp.getGamePathSlot(i) }.getOrNull().isNullOrEmpty().not()
            val at = if (used) runCatching { NativeApp.getSlotSavedAt(i) }.getOrDefault(0L) else 0L
            val picture = if (used) runCatching {
                val bytes = NativeApp.getImageSlot(i)
                if (bytes == null || bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull() else null
            Slot(i, used, at, picture)
        }
        autosave.value = runCatching { NativeApp.hasAutosaveState() }.getOrDefault(false)
    }
}

/** The Save State and Load State items for the in-game Game column. */
@Composable
fun xmbStateNodes(controller: XmbController): List<XmbNode> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val full = "explore_plugin_full"
    val yes = fw(full, "msg_yes", "xmb.yes")
    val no = fw(full, "msg_no", "xmb.no")
    val ok = fw(full, "msg_ok", "xmb.ok")
    val saveTitle = str("savestate.title.save")
    val loadTitle = str("xmb.loadState")
    val slotWord = str("memcard.slot1").substringBefore(' ')
    val empty = str("xmb.state.empty")
    val overwrite = str("xmb.state.overwrite")
    val deleteTitle = str("action.delete")
    val deleteAsk = str("savestate.delete.body")
    val autosaveTitle = str("savestate.autosave.title")
    val autosaveSub = str("savestate.autosave.savedOnExit")
    val importTitle = str("savestate.import")
    val none = str("savestate.empty.title")
    val saveIcon = XmbIcon("explore_category_game", "item_tex_ps3util", "↥")
    val loadIcon = XmbIcon("explore_category_game", "item_tex_ps3util", "↧")

    fun date(at: Long): String? = if (at <= 0L) null else android.text.format.DateUtils.formatDateTime(
        context,
        at,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_ALL,
    )

    fun refresh() = scope.launch(Dispatchers.IO) { XmbStates.read() }

    /** Run [call] off the main thread, then back to the game, or say what went wrong. */
    fun run(call: () -> Boolean, failure: () -> String) {
        scope.launch {
            val done = withContext(Dispatchers.IO) { runCatching(call).getOrDefault(false) }
            if (done) {
                controller.closeAll()
                if (com.armsx2.ui.WindowImpl.overlayVisible.value) InGameOverlay.toggle()
            } else {
                controller.tell(failure(), ok)
            }
        }
    }

    fun saveFailure(): String {
        val hardcore = runCatching { NativeApp.isHardcoreMode() }.getOrDefault(false)
        return I18n.get(if (hardcore) "savestate.error.hardcore" else "savestate.error.save")
    }

    fun loadFailure(slot: Int): String {
        val incompatible = runCatching { NativeApp.hasStateInSlot(slot) && !NativeApp.isStateSlotReadable(slot) }.getOrDefault(false)
        return I18n.get(if (incompatible) "savestate.error.incompatible" else "savestate.error.load")
    }

    fun deleteOption(slot: Int) = XmbOption(deleteTitle) {
        controller.ask(deleteAsk.format(slot + 1), yes, no, focusNo = true) {
            scope.launch {
                val done = withContext(Dispatchers.IO) { runCatching { NativeApp.deleteStateFromSlot(slot) }.getOrDefault(false) }
                if (done) refresh() else controller.tell(I18n.get("savestate.delete.failed"), ok)
            }
        }
    }

    fun slotIcon(s: XmbStates.Slot, fallback: XmbIcon) =
        s.picture?.let { XmbIcon(bitmap = it, glyph = fallback.glyph, shadow = false) } ?: fallback

    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val slot = withContext(Dispatchers.IO) { com.armsx2.ui.saves.importSaveStateToNextFreeSlot(context, uri) }
            if (slot >= 0) {
                refresh()
                controller.tell("$importTitle: $slotWord ${slot + 1}", ok)
            } else {
                controller.tell(I18n.get(if (slot == com.armsx2.ui.saves.SS_IMPORT_SLOTS_FULL) "savestate.import.slotsFull" else "savestate.import.failed"), ok)
            }
        }
    }

    // Read while either list is open, so what was saved shows straight away.
    val reader: @Composable () -> Unit = { LaunchedEffect(Unit) { withContext(Dispatchers.IO) { XmbStates.read() } } }

    // The focused slot's picture beside the list, large, with when it was saved under it.
    val preview: @Composable (List<String>) -> Unit = { keys ->
        val slot = keys.firstNotNullOfOrNull { k ->
            k.takeIf { it.startsWith("save.slot.") || it.startsWith("load.slot.") }?.substringAfterLast('.')?.toIntOrNull()
        }
        val s = slot?.let { XmbStates.slots.value?.getOrNull(it) }
        XmbStatePreview(s, s?.let { "$slotWord ${it.index + 1}" }, s?.let { date(it.savedAt) } ?: empty)
    }

    val save = XmbNode.Folder("ingame.save", saveTitle, saveIcon, live = true, host = reader, aside = preview) {
        XmbStates.slots.value.orEmpty().map { s ->
            XmbNode.Action(
                "save.slot.${s.index}",
                "$slotWord ${s.index + 1}",
                slotIcon(s, saveIcon),
                date(s.savedAt) ?: if (s.used) null else empty,
                options = if (s.used) listOf(deleteOption(s.index)) else emptyList(),
            ) {
                val write = { run({ NativeApp.saveStateToSlot(s.index) }, ::saveFailure) }
                if (s.used) controller.ask(overwrite, yes, no, focusNo = true) { write() } else write()
            }
        }
    }
    val load = XmbNode.Folder("ingame.load", loadTitle, loadIcon, live = true, host = reader, aside = preview) {
        val slots = XmbStates.slots.value.orEmpty()
        if (XmbStates.slots.value == null) emptyList() else buildList {
            if (XmbStates.autosave.value) {
                add(XmbNode.Action("load.autosave", autosaveTitle, loadIcon, autosaveSub) {
                    run({ NativeApp.loadAutosaveState() }) { I18n.get("savestate.error.load") }
                })
            }
            slots.filter { it.used }.forEach { s ->
                add(
                    XmbNode.Action(
                        "load.slot.${s.index}",
                        "$slotWord ${s.index + 1}",
                        slotIcon(s, loadIcon),
                        date(s.savedAt),
                        options = listOf(deleteOption(s.index)),
                    ) { run({ NativeApp.loadStateFromSlot(s.index) }) { loadFailure(s.index) } },
                )
            }
            if (none.isNotEmpty() && slots.none { it.used } && !XmbStates.autosave.value) {
                add(XmbNode.Action("load.none", none, loadIcon) {})
            }
            add(XmbNode.Action("load.import", importTitle, loadIcon) { importer.launch(arrayOf("*/*")) })
        }
    }
    return listOf(save, load)
}

/** A slot's picture as the console previews saved data: 16:9, framed, its name and date under it. */
@Composable
private fun XmbStatePreview(slot: XmbStates.Slot?, title: String?, sub: String?) {
    val font = rememberXmbFont()
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier
                .fillMaxWidth(0.92f)
                .aspectRatio(16f / 9f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            val picture = slot?.picture
            if (picture != null) {
                androidx.compose.foundation.Image(
                    picture,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = androidx.compose.ui.Modifier.matchParentSize(),
                )
            } else {
                XmbIconView(XmbIcon("explore_category_game", "item_tex_ps3util", "\u21A5"), 64.dp)
            }
        }
        if (title != null) {
            androidx.compose.material3.Text(
                title,
                style = xmbText(font, 20f),
                modifier = androidx.compose.ui.Modifier.padding(top = 10.dp),
            )
        }
        if (!sub.isNullOrBlank()) {
            androidx.compose.material3.Text(sub, style = xmbText(font, 14f, 0.85f))
        }
    }
}
