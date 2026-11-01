package com.armsx2.ui.xmb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.armsx2.GameInfo
import java.io.File

/**
 * What the XMB shows, independent of where it is shown (the library in XMB mode, or the in-game
 * menu): columns of items, the way the console lays them out, and the state of moving through them.
 * [XmbView] draws it and feeds it touch; [XmbInput] feeds it the pad.
 */

/**
 * An icon: a firmware image when the firmware has it ([rco]/[name]), drawn over a soft shadow of
 * itself unless [shadow] is off; else [bitmap], a file or a Coil model, else [glyph].
 */
class XmbIcon(
    val rco: String? = null,
    val name: String? = null,
    val glyph: String = "",
    val file: File? = null,
    val model: Any? = null,
    val bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    val shadow: Boolean = true,
)

/** A pick in an options panel (Triangle) or a choice in a setting. */
class XmbOption(val title: String, val action: () -> Unit)

/** How a setting is changed. */
sealed class XmbEditor {
    class Toggle(val get: () -> Boolean, val set: (Boolean) -> Unit) : XmbEditor()

    class Choice(val labels: List<String>, val get: () -> Int, val set: (Int) -> Unit) : XmbEditor() {
        /** A colour beside each choice, as Theme Settings > Color shows its colours. */
        var swatches: List<androidx.compose.ui.graphics.Color>? = null
    }

    class Range(
        val min: Float,
        val max: Float,
        val step: Float,
        val get: () -> Float,
        val set: (Float) -> Unit,
        val format: (Float) -> String,
    ) : XmbEditor()
}

sealed class XmbNode(val key: String, val title: String, val icon: XmbIcon) {
    /** Second line under the title (or, for a setting, its current value). */
    open val subtitle: String? get() = null

    class Action(
        key: String,
        title: String,
        icon: XmbIcon,
        private val sub: String? = null,
        val options: List<XmbOption> = emptyList(),
        val onConfirm: () -> Unit,
    ) : XmbNode(key, title, icon) {
        override val subtitle: String? get() = sub
    }

    /** Opens a list of its own, beside the item, as the console's settings do. */
    class Folder(
        key: String,
        title: String,
        icon: XmbIcon,
        private val sub: String? = null,
        val children: () -> List<XmbNode>,
    ) : XmbNode(key, title, icon) {
        override val subtitle: String? get() = sub
    }

    /** Opens a full panel over the XMB, for a screen with more to it than a list. */
    class Panel(
        key: String,
        title: String,
        icon: XmbIcon,
        private val sub: String? = null,
        val content: @Composable (close: () -> Unit) -> Unit,
    ) : XmbNode(key, title, icon) {
        override val subtitle: String? get() = sub
    }

    class Setting(
        key: String,
        title: String,
        icon: XmbIcon,
        val editor: XmbEditor,
        val value: () -> String,
        val description: String? = null,
        val changed: () -> Boolean = { false },
    ) : XmbNode(key, title, icon) {
        override val subtitle: String? get() = value()
    }

    class Game(
        val game: GameInfo,
        title: String,
        icon: XmbIcon,
        private val sub: String?,
        val onLaunch: () -> Unit,
        val onOptions: () -> Unit,
        val onSettings: () -> Unit,
        /** Where the game's own XMB media (PIC1, SND0, ICON1) is, when it has a folder. */
        val mediaDir: File? = null,
    ) : XmbNode(game.uri.toString(), title, icon) {
        override val subtitle: String? get() = sub
    }
}

class XmbColumn(
    val key: String,
    val title: String,
    val icon: XmbIcon,
    val items: List<XmbNode>,
    /** The item focused on first visit. */
    val defaultFocus: Int = 0,
)

/**
 * Where the XMB is: which column, which item in each, what sub-lists are open, and the editor,
 * options panel or full panel on top. Everything here is snapshot state, so [XmbView] redraws on
 * any change and the pad, touch and code can all drive it.
 */
class XmbController(initialColumn: String? = null, initialFocus: Map<String, Int> = emptyMap()) {
    class Level(val node: XmbNode, val children: List<XmbNode>, focus: Int) {
        var focus by mutableIntStateOf(focus)
    }

    class Editing(val node: XmbNode.Setting, choice: Int, value: Float) {
        var choice by mutableIntStateOf(choice)
        var value by mutableFloatStateOf(value)
    }

    class Options(val title: String, val options: List<XmbOption>) {
        var focus by mutableIntStateOf(0)
    }

    /** A question in the console's dialog: [message] over [buttons] side by side (Yes, No). */
    class Dialog(val message: String, val buttons: List<XmbOption>, focus: Int = 0) {
        var focus by mutableIntStateOf(focus)
    }

    /** Set by the view each frame: the columns as they are now. */
    internal var columns: List<XmbColumn> = emptyList()

    private var wantedColumn: String? = initialColumn
    var column by mutableIntStateOf(0)
    val focus = mutableStateMapOf<String, Int>().apply { putAll(initialFocus) }
    val levels = mutableStateListOf<Level>()
    var editing by mutableStateOf<Editing?>(null)
    var options by mutableStateOf<Options?>(null)
    var panel by mutableStateOf<XmbNode.Panel?>(null)
    var dialog by mutableStateOf<Dialog?>(null)

    /** Circle with nothing open: the library ignores it, the in-game menu closes. */
    var onBackAtRoot: () -> Unit = {}

    internal fun attach(columns: List<XmbColumn>) {
        this.columns = columns
        wantedColumn?.let { key ->
            columns.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { column = it }
            wantedColumn = null
        }
        if (columns.isNotEmpty() && column > columns.lastIndex) column = columns.lastIndex
    }

    fun currentColumn(): XmbColumn? = columns.getOrNull(column)

    fun focusOf(c: XmbColumn): Int =
        (focus[c.key] ?: c.defaultFocus).coerceIn(0, (c.items.size - 1).coerceAtLeast(0))

    /** The item the cursor is on at the top level of the current column. */
    fun rootNode(): XmbNode? = currentColumn()?.let { it.items.getOrNull(focusOf(it)) }

    /** The item the cursor is on, at whatever depth. */
    fun focusedNode(): XmbNode? = levels.lastOrNull()?.let { it.children.getOrNull(it.focus) } ?: rootNode()

    val busy: Boolean get() = editing != null || options != null || panel != null || dialog != null

    /** Ask [message] with [yes] and [no], as the console asks before turning off or quitting. */
    fun ask(message: String, yes: String, no: String, focusNo: Boolean = false, onYes: () -> Unit) {
        XmbSounds.play(XmbSounds.Sfx.OPTION)
        dialog = Dialog(message, listOf(XmbOption(yes, onYes), XmbOption(no) {}), if (focusNo) 1 else 0)
    }

    /** Tell [message] with a single [ok]. */
    fun tell(message: String, ok: String) {
        XmbSounds.play(XmbSounds.Sfx.ERROR)
        dialog = Dialog(message, listOf(XmbOption(ok) {}))
    }

    fun selectColumn(index: Int) {
        if (index == column || index !in columns.indices) return
        closeAll()
        column = index
        XmbSounds.play(XmbSounds.Sfx.CURSOR)
    }

    fun setFocus(index: Int) {
        val level = levels.lastOrNull()
        if (level != null) {
            val i = index.coerceIn(0, (level.children.size - 1).coerceAtLeast(0))
            if (i != level.focus) {
                level.focus = i
                XmbSounds.play(XmbSounds.Sfx.CURSOR)
            }
            return
        }
        val c = currentColumn() ?: return
        val i = index.coerceIn(0, (c.items.size - 1).coerceAtLeast(0))
        if (i != focusOf(c)) {
            focus[c.key] = i
            XmbSounds.play(XmbSounds.Sfx.CURSOR)
        }
    }

    fun move(dx: Int, dy: Int) {
        dialog?.let { d ->
            val step = if (dx != 0) dx else dy
            val next = (d.focus + step).coerceIn(0, (d.buttons.size - 1).coerceAtLeast(0))
            if (next != d.focus) {
                d.focus = next
                XmbSounds.play(XmbSounds.Sfx.CURSOR)
            }
            return
        }
        options?.let { o ->
            if (dy != 0) {
                val next = (o.focus + dy).coerceIn(0, (o.options.size - 1).coerceAtLeast(0))
                if (next != o.focus) {
                    o.focus = next
                    XmbSounds.play(XmbSounds.Sfx.CURSOR)
                }
            }
            return
        }
        editing?.let { e ->
            when (val ed = e.node.editor) {
                is XmbEditor.Range -> {
                    val delta = if (dx != 0) dx else -dy
                    if (delta != 0) {
                        val next = (e.value + delta * ed.step).coerceIn(ed.min, ed.max)
                        if (next != e.value) {
                            e.value = next
                            ed.set(next)
                            XmbSounds.play(XmbSounds.Sfx.CURSOR)
                        }
                    }
                }
                is XmbEditor.Choice, is XmbEditor.Toggle -> {
                    val count = if (ed is XmbEditor.Choice) ed.labels.size else 2
                    if (dy != 0) {
                        val next = (e.choice + dy).coerceIn(0, (count - 1).coerceAtLeast(0))
                        if (next != e.choice) {
                            e.choice = next
                            XmbSounds.play(XmbSounds.Sfx.CURSOR)
                        }
                    }
                }
            }
            return
        }
        if (panel != null) return
        if (levels.isNotEmpty()) {
            if (dx < 0) {
                back()
                return
            }
            if (dy != 0) levels.last().let { setFocus(it.focus + dy) }
            return
        }
        if (dx != 0) {
            selectColumn((column + dx).coerceIn(0, (columns.size - 1).coerceAtLeast(0)))
            return
        }
        if (dy != 0) currentColumn()?.let { setFocus(focusOf(it) + dy) }
    }

    fun confirm() {
        dialog?.let { d ->
            val pick = d.buttons.getOrNull(d.focus)
            dialog = null
            XmbSounds.play(XmbSounds.Sfx.DECIDE)
            pick?.action?.invoke()
            return
        }
        options?.let { o ->
            val pick = o.options.getOrNull(o.focus)
            options = null
            XmbSounds.play(XmbSounds.Sfx.DECIDE)
            pick?.action?.invoke()
            return
        }
        editing?.let { e ->
            when (val ed = e.node.editor) {
                is XmbEditor.Toggle -> ed.set(e.choice == 0)
                is XmbEditor.Choice -> ed.set(e.choice)
                is XmbEditor.Range -> ed.set(e.value)
            }
            editing = null
            XmbSounds.play(XmbSounds.Sfx.DECIDE)
            return
        }
        if (panel != null) return
        open(focusedNode() ?: return)
    }

    /** Act on [node]: what Cross does on it. */
    fun open(node: XmbNode) {
        when (node) {
            is XmbNode.Action -> {
                XmbSounds.play(XmbSounds.Sfx.DECIDE)
                node.onConfirm()
            }
            is XmbNode.Folder -> {
                val children = node.children()
                if (children.isEmpty()) {
                    XmbSounds.play(XmbSounds.Sfx.ERROR)
                    return
                }
                XmbSounds.play(XmbSounds.Sfx.DECIDE)
                levels.add(Level(node, children, 0))
            }
            is XmbNode.Panel -> {
                XmbSounds.play(XmbSounds.Sfx.DECIDE)
                panel = node
            }
            is XmbNode.Setting -> {
                XmbSounds.play(XmbSounds.Sfx.DECIDE)
                editing = when (val ed = node.editor) {
                    is XmbEditor.Toggle -> Editing(node, if (ed.get()) 0 else 1, 0f)
                    is XmbEditor.Choice -> Editing(node, ed.get().coerceIn(0, (ed.labels.size - 1).coerceAtLeast(0)), 0f)
                    is XmbEditor.Range -> Editing(node, 0, ed.get())
                }
            }
            is XmbNode.Game -> {
                XmbSounds.play(XmbSounds.Sfx.DECIDE)
                node.onLaunch()
            }
        }
    }

    fun back() {
        when {
            dialog != null -> dialog = null
            options != null -> options = null
            editing != null -> {
                // A range applies as it moves, so backing out keeps where it was left; a list
                // applies only on Cross, so backing out is a cancel.
                editing = null
            }
            panel != null -> panel = null
            levels.isNotEmpty() -> levels.removeAt(levels.lastIndex)
            else -> {
                XmbSounds.play(XmbSounds.Sfx.CANCEL)
                onBackAtRoot()
                return
            }
        }
        XmbSounds.play(XmbSounds.Sfx.CANCEL)
    }

    /** Triangle: the focused item's options. */
    fun openOptions() {
        if (busy) return
        when (val node = focusedNode()) {
            is XmbNode.Game -> {
                XmbSounds.play(XmbSounds.Sfx.OPTION)
                node.onOptions()
            }
            is XmbNode.Action -> if (node.options.isNotEmpty()) {
                XmbSounds.play(XmbSounds.Sfx.OPTION)
                options = Options(node.title, node.options)
            } else {
                XmbSounds.play(XmbSounds.Sfx.ERROR)
            }
            else -> XmbSounds.play(XmbSounds.Sfx.ERROR)
        }
    }

    /** Square: the focused game's own settings. */
    fun square() {
        if (busy) return
        (focusedNode() as? XmbNode.Game)?.let {
            XmbSounds.play(XmbSounds.Sfx.DECIDE)
            it.onSettings()
        }
    }

    fun closeAll() {
        dialog = null
        options = null
        editing = null
        panel = null
        levels.clear()
    }
}

/**
 * The pad's way in. HomeInputController hands every press here while [active], and the in-game
 * menu does the same while it shows the XMB. [blocked] holds input during the boot animation.
 */
object XmbInput {
    @Volatile var controller: XmbController? = null
    @Volatile var blocked = false

    /** While [blocked] by the boot animation, Cross or Circle skips it. */
    @Volatile var skip: (() -> Unit)? = null

    fun active(): Boolean = controller != null

    fun move(dx: Int, dy: Int): Boolean {
        val c = controller ?: return false
        if (!blocked) c.move(dx, dy)
        return true
    }

    fun confirm(): Boolean {
        val c = controller ?: return false
        if (blocked) skip?.invoke() else c.confirm()
        return true
    }

    fun back(): Boolean {
        val c = controller ?: return false
        if (blocked) skip?.invoke() else c.back()
        return true
    }

    fun options(): Boolean {
        val c = controller ?: return false
        if (!blocked) c.openOptions()
        return true
    }

    fun square(): Boolean {
        val c = controller ?: return false
        if (!blocked) c.square()
        return true
    }

    /** L1 / R1: a page of items at a time. */
    fun page(dir: Int): Boolean {
        val c = controller ?: return false
        if (!blocked && !c.busy) repeat(5) { c.move(0, dir) }
        return true
    }
}
