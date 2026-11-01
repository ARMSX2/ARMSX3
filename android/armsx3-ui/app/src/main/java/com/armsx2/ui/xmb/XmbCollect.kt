package com.armsx2.ui.xmb

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Settings screens as XMB lists.
 *
 * A settings tab is composed out of sight with [LocalXmbCollector] set, and each of its rows
 * (ToggleRow, IntSliderRow, SegmentedRow, SegmentedGridRow, and the CollapsibleSections they sit
 * in) then hands the XMB a node in place of drawing itself. So the tab's own code still decides
 * what is shown, what each setting is set to and how a change is saved, per game or for all, as on
 * the settings screen; the XMB only draws it. Rows come out in the tab's order: each also lays
 * out a line one pixel high, and where that line falls down the hidden tab is its place in the list.
 */
val LocalXmbCollector = staticCompositionLocalOf<XmbCollector?> { null }

class XmbCollector(
    /** On and Off as the XMB words them. */
    val on: String,
    val off: String,
    /** The icon the rows show: their category's. */
    val icon: XmbIcon = XmbIcon(),
) {
    private class Entry(val node: XmbNode, val y: Float, val order: Long)

    private val entries = mutableStateMapOf<Any, Entry>()
    private var next = 0L

    /** An icon of its own for a row, where the list gives its rows one by what each is. */
    var iconFor: ((XmbNode) -> XmbIcon?)? = null

    fun put(id: Any, node: XmbNode) {
        val old = entries[id]
        val shown = iconFor?.invoke(node)?.let { node.withIcon(it) } ?: node
        entries[id] = Entry(shown, old?.y ?: Float.MAX_VALUE, old?.order ?: next++)
    }

    fun place(id: Any, y: Float) {
        val old = entries[id] ?: return
        if (old.y != y) entries[id] = Entry(old.node, y, old.order)
    }

    fun remove(id: Any) {
        entries.remove(id)
    }

    /** A collector for a section inside this one. */
    fun child() = XmbCollector(on, off, icon).also { it.iconFor = iconFor }

    /**
     * The rows in the tab's order; a section with nothing in it is left out. Worked out again only
     * when a row (or a section's rows) changes: the open lists read this on every redraw, and a
     * sort through every section below on each read slowed the whole XMB down.
     */
    private val ordered = androidx.compose.runtime.derivedStateOf {
        entries.values
            .sortedWith(compareBy<Entry>({ it.y }, { it.order }))
            .map { it.node }
            .filter { it !is XmbNode.Folder || !it.live || it.children().isNotEmpty() }
    }

    val nodes: List<XmbNode> get() = ordered.value
}

/** A row of a hidden tab: [node] handed to the XMB in this row's place. */
@Composable
fun XmbCollected(collector: XmbCollector, node: XmbNode) {
    val id = remember { Any() }
    SideEffect { collector.put(id, node) }
    DisposableEffect(collector) { onDispose { collector.remove(id) } }
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .onGloballyPositioned { collector.place(id, it.positionInRoot().y) },
    )
}

/** A section of a hidden tab: a list of its own, holding what [content] hands over. */
@Composable
fun XmbCollectedSection(collector: XmbCollector, key: String, title: String, sub: String? = null, content: @Composable () -> Unit) {
    val child = remember(collector) { collector.child() }
    XmbCollected(collector, XmbNode.Folder(key, title, collector.icon, sub, live = true) { child.nodes })
    CompositionLocalProvider(LocalXmbCollector provides child) {
        Column { content() }
    }
}

/** The step a pad press moves a range of whole numbers: one, or about a fortieth of a wide one. */
fun xmbIntStep(min: Int, max: Int): Float {
    val span = (max - min).coerceAtLeast(1)
    return if (span <= 40) 1f else (span / 40f).roundToInt().coerceAtLeast(1).toFloat()
}

/**
 * Where hidden tabs are composed: off the left of the screen, drawn at no opacity, a fixed width
 * so their rows lay out as on a settings screen, and tall enough for any tab to scroll inside.
 */
@Composable
internal fun XmbHiddenHost(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().wrapContentSize(Alignment.TopStart, unbounded = true)) {
        Box(
            Modifier
                .offset(x = (-6000).dp)
                .requiredWidth(560.dp)
                .requiredHeight(1200.dp)
                .graphicsLayer { alpha = 0f },
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
        }
    }
}

/**
 * A settings tab as an XMB list of its own: [content] composed out of sight while the list is
 * open, its rows handed over (see [XmbCollector]). For a tab reached from somewhere other than
 * the Settings column, as the in-game Controls tab reaches Skins and Hotkeys.
 */
@Composable
fun xmbTabFolder(key: String, title: String, tex: String, content: @Composable () -> Unit): XmbNode {
    val (on, off) = xmbOnOff()
    val icon = XmbIcon("explore_category_sysconf", tex, "\u2699")
    val collector = remember(key, on, off) { XmbCollector(on, off, icon) }
    return XmbNode.Folder(
        key, title, icon, live = true,
        host = { CompositionLocalProvider(LocalXmbCollector provides collector) { content() } },
    ) { collector.nodes }
}
