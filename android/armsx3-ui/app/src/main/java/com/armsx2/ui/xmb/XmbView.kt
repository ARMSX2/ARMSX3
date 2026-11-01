package com.armsx2.ui.xmb

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws an [XmbController]'s columns the way the console does, and takes touch.
 *
 * The geometry is the console's own, measured off its XMB running in ARMSX3 at 1920x1080 and kept
 * here in 1280x720 units scaled to the screen: the category bar at y 193 with icons 137 apart (80
 * across when focused, 50 otherwise) and the focused one at x 376, its name 41 under it; the
 * focused item's centre 140 under the bar, a game's icon 207x113 there and 97x53 everywhere else,
 * the items 53 apart, the first after the focused one 120 down and the first before it 113 above
 * the bar. Only the focused game shows its name, with its last played date and a PS3 badge; other
 * items keep their names. The clock sits in a bar along the top right, and the button hint in the
 * bottom right corner.
 *
 * Touch: swipe sideways to change category and up or down to move through items, tap an item to
 * focus it and again to open it, long-press for its options, and tap outside a sub-list, picker or
 * panel (or the corner hint) to back out.
 */
@Composable
fun XmbView(
    controller: XmbController,
    columns: List<XmbColumn>,
    modifier: Modifier = Modifier,
    /** 0..1 while the XMB is coming up after the boot animation; 1 when it is fully there. */
    reveal: Float = 1f,
    /** Drawn behind the items for the focused top-level item: a game's PIC1, say. */
    backdrop: @Composable (XmbNode?) -> Unit = {},
    showClock: Boolean = true,
    /** Registers [controller] as the pad's target while shown. */
    takesPad: Boolean = true,
    /** Circle with nothing open goes back to a game, as the in-game XMB's does; hinted in the corner. */
    backToGame: Boolean = false,
) {
    controller.attach(columns)
    // The system back gesture closes what is open in the XMB before anything behind it hears it.
    androidx.activity.compose.BackHandler(enabled = controller.busy || controller.levels.isNotEmpty()) {
        controller.back()
    }
    if (takesPad) {
        DisposableEffect(controller) {
            XmbInput.controller = controller
            onDispose { if (XmbInput.controller === controller) XmbInput.controller = null }
        }
    }
    val font = rememberXmbFont()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        // dp per 720p unit. Landscape fits the 1280x720 frame to the screen; portrait goes by width,
        // a little larger, so the bar still reads at a phone's width.
        val u = if (w >= h) minOf(w / 1280f, h / 720f) else w / 900f
        val m = remember(u, w, h) { Metrics(u, w, h) }
        val px = LocalDensity.current.density

        val colAnim = remember { Animatable(controller.column.toFloat()) }
        LaunchedEffect(controller.column) {
            colAnim.animateTo(controller.column.toFloat(), tween(220, easing = FastOutSlowInEasing))
        }
        val current = controller.currentColumn()
        val rootFocus = current?.let { controller.focusOf(it) } ?: 0
        val focusAnim = remember(current?.key) { Animatable(rootFocus.toFloat()) }
        LaunchedEffect(current?.key, rootFocus) {
            focusAnim.animateTo(rootFocus.toFloat(), tween(170, easing = FastOutSlowInEasing))
        }
        val depth = controller.levels.size
        val levelFade by animateFloatAsState(if (depth == 0) 1f else 0f, tween(180), label = "xmb-level")
        // Into a list, the console slides the bar and the column left and the list takes the
        // column's place.
        val levelAnim by animateFloatAsState(if (depth == 0) 0f else 1f, tween(240, easing = FastOutSlowInEasing), label = "xmb-shift")

        // The focused top-level item once it has stayed focused a moment: a game's background waits,
        // as on the console, rather than flashing past while scrolling.
        val focusedRoot = controller.rootNode()
        var settledRoot by remember { mutableStateOf<XmbNode?>(null) }
        LaunchedEffect(focusedRoot?.key) {
            settledRoot = null
            delay(650)
            settledRoot = focusedRoot
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal }) {
            backdrop(if (depth == 0 && !controller.busy) settledRoot else null)
        }

        val gestures = rememberUpdatedState(Gestures(controller, m, columns, px))
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = reveal
                    translationY = (1f - reveal) * 24f * px
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { gestures.value.tap(it) },
                        onLongPress = { gestures.value.longPress(it) },
                    )
                }
                .pointerInput(Unit) {
                    var ax = 0f
                    var ay = 0f
                    var axis = 0
                    detectDragGestures(
                        onDragStart = { ax = 0f; ay = 0f; axis = 0 },
                        onDrag = { change, amount ->
                            change.consume()
                            ax += amount.x
                            ay += amount.y
                            if (axis == 0) axis = if (abs(ax) > abs(ay)) 1 else 2
                            val g = gestures.value
                            if (axis == 1) {
                                val step = g.m.catSpacing * 0.8f * px
                                while (ax <= -step) { g.controller.move(1, 0); ax += step }
                                while (ax >= step) { g.controller.move(-1, 0); ax -= step }
                            } else {
                                val step = g.m.itemSpacing * 1.1f * px
                                while (ay <= -step) { g.controller.move(0, 1); ay += step }
                                while (ay >= step) { g.controller.move(0, -1); ay -= step }
                            }
                        },
                    )
                },
        ) {
            if (showClock) XmbClock(m, font)

            Box(Modifier.fillMaxSize().graphicsLayer { translationX = -m.levelShift * levelAnim * px }) {
                columns.forEachIndexed { c, column ->
                    key(column.key) { CategoryIcon(column, c, colAnim, m, font, levelFade, levelAnim) }
                }
                // Items of the columns on screen: the focused one, and the one it is sliding to.
                columns.forEachIndexed { c, column ->
                    if (abs(c - controller.column) <= 1) {
                        key("items-" + column.key) { ColumnItems(controller, column, c, colAnim, focusAnim, m, font, levelFade) }
                    }
                }
            }
            // The settings tabs behind the open lists, composed out of sight (see XmbCollector).
            controller.levels.forEach { level ->
                (level.node as? XmbNode.Folder)?.host?.let { host -> key("host-" + level.node.key) { XmbHiddenHost(host) } }
            }
            controller.levels.lastOrNull()?.let { level ->
                key("level-$depth-${level.node.key}") { SubList(level, m, font, quiet = controller.editing != null) }
            }
            // A preview beside the open list (the toy portals' pictures), out of the way of a picker.
            val asideLevel = controller.levels.lastOrNull { (it.node as? XmbNode.Folder)?.aside != null }
            if (asideLevel != null && controller.editing == null && controller.options == null) {
                val keys = buildList {
                    controller.focusedNode()?.key?.let(::add)
                    controller.levels.asReversed().forEach { add(it.node.key) }
                }
                val appear = remember(asideLevel) { Animatable(0f) }
                LaunchedEffect(asideLevel) { appear.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
                Box(
                    Modifier
                        .offset { IntOffset(((m.width * 0.58f) * density).roundToInt(), ((m.catY + 64f * m.u) * density).roundToInt()) }
                        .size((m.width * 0.38f).dp, (m.height - m.catY - 120f * m.u).coerceAtLeast(80f).dp)
                        .graphicsLayer { alpha = appear.value },
                    contentAlignment = Alignment.Center,
                ) {
                    (asideLevel.node as XmbNode.Folder).aside?.invoke(keys)
                }
            }
            controller.editing?.let { e -> key(e) { PickerPanel(e, m, font) } }
            controller.options?.let { o -> key(o) { OptionsPanel(o, m, font) } }

            // The corner hint: Back while something is open, the focused item's options otherwise.
            val node = controller.focusedNode()
            val back = XmbText.get("xmb_ingame", "msg_help_back", ": " + com.armsx2.i18n.str("xmb.back"))
            when {
                controller.busy || depth > 0 -> CornerHint(m, font, "\u25CB", CircleRed, back) { controller.back() }
                node is XmbNode.Game || (node is XmbNode.Action && node.options.isNotEmpty()) ||
                    (node is XmbNode.Setting && node.options.isNotEmpty()) -> CornerHint(
                    m,
                    font,
                    "\u25B3",
                    TriangleGreen,
                    XmbText.get("explore_plugin_full", "msg_option_with_icon", ": " + com.armsx2.i18n.str("xmb.options")),
                ) { controller.openOptions() }
                backToGame -> CornerHint(
                    m,
                    font,
                    "\u25CB",
                    CircleRed,
                    XmbText.get("xmb_ingame", "msg_help_back_to_game", ": " + com.armsx2.i18n.str("xmb.backToGame")),
                ) { controller.back() }
            }
        }

        controller.panel?.let { panel -> PanelHost(controller, panel, m, font) }
        controller.dialog?.let { d -> key(d) { DialogBox(controller, d, m, font) } }
        // The RPCN avatar picker, opened from Account Management: the app's own, as it is.
        if (com.armsx2.ui.settings.RpcnAvatarPicker.open.value && controller.panel == null) {
            com.armsx2.ui.settings.RpcnAvatarPickerOverlay()
        }
    }
    XmbKeyboardWatcher()
}

private val CircleRed = Color(0xFFE85C5C)
private val TriangleGreen = Color(0xFF5CD68A)

/** The layout in dp, from the console's 720p units. */
internal class Metrics(val u: Float, val width: Float, val height: Float) {
    val catY = 193f * u
    val catIcon = 80f * u
    val catIconSmall = 50f * u
    val catSpacing = 137f * u
    val focusX = minOf(376f * u, width * 0.3f)
    val labelY = catY + 41f * u
    val labelSp = 17.5f * u
    val focusedY = catY + 140f * u
    val firstBelow = focusedY + 120f * u
    val firstAbove = catY - 113f * u
    val itemSpacing = 53f * u
    val gameW = 207f * u
    val gameH = 113f * u
    val gameSmall = 97f / 207f
    val square = 84f * u
    val squareSmall = 52f / 84f
    val titleSp = 23f * u
    val subtitleSp = 14.5f * u
    val smallTitleSp = 19f * u
    val textGap = 28f * u
    val badgeX = focusX + 411f * u
    /** How far the bar and column slide left as a list opens. */
    val levelShift = 168f * u
    /** The left edge of the side panel the console opens on the right for a choice or options. */
    val panelLeft = width * 0.69f
    /** A row of that panel. */
    val panelRow = 30f * u

    fun colX(c: Float, anim: Float) = focusX + (c - anim) * catSpacing

    /** Centre y of the item [d] places from the focused one (fractional while moving). */
    fun itemY(d: Float): Float = when {
        d >= 1f -> firstBelow + (d - 1f) * itemSpacing
        d >= 0f -> focusedY + d * (firstBelow - focusedY)
        d >= -1f -> focusedY + d * (focusedY - firstAbove)
        else -> firstAbove + (d + 1f) * itemSpacing
    }
}

private class Gestures(val controller: XmbController, val m: Metrics, val columns: List<XmbColumn>, val px: Float) {
    fun tap(p: Offset) {
        val x = p.x / px
        val y = p.y / px
        if (inCorner(x, y)) {
            if (controller.busy || controller.levels.isNotEmpty()) controller.back() else controller.openOptions()
            return
        }
        controller.options?.let { o ->
            if (x < m.panelLeft) {
                controller.back()
                return
            }
            val row = o.focus + ((y - m.focusedY) / m.panelRow).roundToInt()
            if (row in o.options.indices) {
                o.focus = row
                controller.confirm()
            }
            return
        }
        controller.editing?.let { e ->
            if (x < m.panelLeft) {
                controller.back()
                return
            }
            val ed = e.node.editor
            if (ed is XmbEditor.Range) {
                val left = m.panelLeft + 28f * m.u
                val right = m.width - 36f * m.u
                val f = ((x - left) / (right - left)).coerceIn(0f, 1f)
                val raw = ed.min + f * (ed.max - ed.min)
                e.value = (Math.round(raw / ed.step) * ed.step).coerceIn(ed.min, ed.max)
                ed.set(e.value)
                return
            }
            val count = if (ed is XmbEditor.Choice) ed.labels.size else 2
            val row = e.choice + ((y - m.focusedY) / m.panelRow).roundToInt()
            if (row in 0 until count) {
                e.choice = row
                controller.confirm()
            }
            return
        }
        if (controller.panel != null) return
        // The category bar answers at any depth; tapping one closes what is open there.
        if (abs(y - m.catY) < m.catIcon * 0.7f) {
            val c = ((x - m.focusX) / m.catSpacing + controller.column).roundToInt()
            if (c in columns.indices) {
                if (controller.levels.isNotEmpty()) controller.closeAll()
                controller.selectColumn(c)
            }
            return
        }
        val level = controller.levels.lastOrNull()
        if (level != null) {
            // Left of the list is the column it came from: back to it.
            if (x < m.focusX - 40f * m.u) {
                controller.back()
                return
            }
            val i = hitItem(y, level.focus, level.children.size, square = true) ?: return
            if (i == level.focus) controller.confirm() else controller.setFocus(i)
            return
        }
        val column = controller.currentColumn() ?: return
        val i = hitItem(y, controller.focusOf(column), column.items.size) ?: return
        if (i == controller.focusOf(column)) controller.confirm() else controller.setFocus(i)
    }

    fun longPress(p: Offset) {
        val y = p.y / px
        if (controller.busy || controller.levels.isNotEmpty()) return
        val column = controller.currentColumn() ?: return
        val i = hitItem(y, controller.focusOf(column), column.items.size) ?: return
        controller.setFocus(i)
        controller.openOptions()
    }

    private fun inCorner(x: Float, y: Float) = x > m.width - 230f * m.u && y > m.height - 110f * m.u

    private fun hitItem(y: Float, focus: Int, count: Int, square: Boolean = false): Int? {
        var best: Int? = null
        var bestDist = Float.MAX_VALUE
        for (i in 0 until count) {
            val dist = abs(m.itemY((i - focus).toFloat()) - y)
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best?.takeIf { bestDist < (if (it == focus && !square) m.gameH * 0.6f else if (it == focus) m.square * 0.6f else m.itemSpacing * 0.55f) }
    }
}

internal val XmbShadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 2f), blurRadius = 6f)

@Composable
internal fun rememberXmbFont(): FontFamily {
    // Asked again when the theme is applied, which is once the firmware can be found.
    val typeface by produceState<android.graphics.Typeface?>(null, XmbTheme.revision.intValue) {
        value = withContext(Dispatchers.IO) { XmbFirmware.typeface() }
    }
    return remember(typeface) {
        typeface?.let { FontFamily(androidx.compose.ui.text.font.Typeface(it)) } ?: FontFamily.SansSerif
    }
}

internal fun xmbText(font: FontFamily, sizeSp: Float, alpha: Float = 1f): TextStyle =
    TextStyle(fontFamily = font, fontSize = sizeSp.sp, color = Color.White.copy(alpha = alpha), shadow = XmbShadow)

/** An icon at [size]: the firmware's, a file or model, or the glyph. */
@Composable
internal fun XmbIconView(icon: XmbIcon, size: Dp, modifier: Modifier = Modifier, width: Dp = size) {
    // The XMB theme's picture for it when the theme has one, else the firmware's.
    val theme = XmbTheme.revision.intValue
    val firmware by produceState<ImageBitmap?>(null, icon.rco, icon.name, theme) {
        val rco = icon.rco
        val name = icon.name
        value = if (rco != null && name != null) {
            withContext(Dispatchers.IO) { XmbTheme.image(rco, name) ?: XmbFirmware.image(rco, name, icon.shadow) }
        } else {
            null
        }
    }
    Box(modifier.width(width).height(size), contentAlignment = Alignment.Center) {
        val fw = firmware ?: icon.bitmap ?: icon.picture?.invoke()
        when {
            fw != null -> Image(fw, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            icon.file != null || icon.model != null -> AsyncImage(
                model = icon.file ?: icon.model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            icon.glyph.isNotEmpty() -> Text(
                icon.glyph,
                style = TextStyle(fontSize = (size.value * 0.5f).sp, color = Color.White, shadow = XmbShadow),
            )
        }
        icon.inset?.let { inset ->
            AsyncImage(
                model = inset,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = size * 0.14f, y = size * 0.08f)
                    .size(size * 0.62f)
                    .border(1.dp, Color.White.copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
private fun CategoryIcon(
    column: XmbColumn,
    index: Int,
    colAnim: Animatable<Float, *>,
    m: Metrics,
    font: FontFamily,
    levelFade: Float,
    levelAnim: Float,
) {
    val box = m.catIcon
    Box(
        Modifier
            .offset {
                val x = m.colX(index.toFloat(), colAnim.value) - box / 2f
                IntOffset((x * density).roundToInt(), ((m.catY - box / 2f) * density).roundToInt())
            }
            .size(box.dp)
            .graphicsLayer {
                val near = (1f - abs(index - colAnim.value)).coerceIn(0f, 1f)
                val scale = (m.catIconSmall + (m.catIcon - m.catIconSmall) * near) / m.catIcon
                scaleX = scale
                scaleY = scale
                // In a list only the category it is in stays, as on the console.
                alpha = (0.78f + 0.22f * near) * (1f - levelAnim * (1f - near))
            },
    ) {
        XmbIconView(column.icon, box.dp)
        // The firmware's icons are a little see-through by design; the console draws the focused
        // one solid white, so it is drawn again over itself as it comes into focus.
        XmbIconView(
            column.icon,
            box.dp,
            Modifier.graphicsLayer { alpha = (1f - abs(index - colAnim.value)).coerceIn(0f, 1f) },
        )
    }
    Text(
        column.title,
        style = xmbText(font, m.labelSp),
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .offset {
                val x = m.colX(index.toFloat(), colAnim.value) - 120f * m.u
                IntOffset((x * density).roundToInt(), ((m.labelY - m.labelSp * 0.7f) * density).roundToInt())
            }
            .width((240f * m.u).dp)
            .graphicsLayer { alpha = (1f - abs(index - colAnim.value) * 2f).coerceIn(0f, 1f) * levelFade },
    )
}

@Composable
private fun ColumnItems(
    controller: XmbController,
    column: XmbColumn,
    index: Int,
    colAnim: Animatable<Float, *>,
    focusAnim: Animatable<Float, *>,
    m: Metrics,
    font: FontFamily,
    levelFade: Float,
) {
    val isCurrent = index == controller.column
    val focus = controller.focusOf(column)
    val first = (focus - 4).coerceAtLeast(0)
    val last = (focus + 12).coerceAtMost(column.items.size - 1)
    for (i in first..last) {
        val node = column.items[i]
        key(node.key) {
            val isGame = node is XmbNode.Game
            val iconW = if (isGame) m.gameW else m.square
            val iconH = if (isGame) m.gameH else m.square
            val small = if (isGame) m.gameSmall else m.squareSmall
            // How focused this item is, 0..1, as the cursor moves.
            fun nearOf(f: Float) = (1f - abs(i - f)).coerceIn(0f, 1f)
            fun scaleOf(near: Float) = small + (1f - small) * near
            fun alphaOf(f: Float): Float {
                val d = i - f
                val columnAlpha = (1f - abs(index - colAnim.value)).coerceIn(0f, 1f)
                // Behind a list the column stays, dimmed, as the console leaves it.
                val depthAlpha = if (i == focus) 1f else 1f - 0.55f * (1f - levelFade)
                // The ones before the focused item fade as they climb past the bar.
                val far = if (d < -1f) (1f + (d + 1f) * 0.5f).coerceIn(0f, 1f) else 1f
                return columnAlpha * depthAlpha * far
            }

            Box(
                Modifier
                    .offset {
                        val f = if (isCurrent) focusAnim.value else focus.toFloat()
                        val x = m.colX(index.toFloat(), colAnim.value) - iconW / 2f
                        IntOffset((x * density).roundToInt(), ((m.itemY(i - f) - iconH / 2f) * density).roundToInt())
                    }
                    .size(iconW.dp, iconH.dp)
                    .graphicsLayer {
                        val f = if (isCurrent) focusAnim.value else focus.toFloat()
                        val s = scaleOf(nearOf(f))
                        scaleX = s
                        scaleY = s
                        alpha = alphaOf(f)
                    },
            ) {
                // The focused game plays its ICON1, as on the console.
                val pam = if (isGame && isCurrent && i == focus && controller.levels.isEmpty() && XmbMode.animatedIcons.value) {
                    remember(node.key) { XmbMedia.icon1((node as XmbNode.Game).mediaDir) }
                } else {
                    null
                }
                if (pam != null) {
                    XmbAnimatedIcon(pam, Modifier.fillMaxSize()) { XmbIconView(node.icon, iconH.dp, width = iconW.dp) }
                } else {
                    XmbIconView(node.icon, iconH.dp, width = iconW.dp)
                    // Solid white as it comes into focus, as the category icons are (see CategoryIcon).
                    if (!isGame) {
                        XmbIconView(
                            node.icon,
                            iconH.dp,
                            Modifier.graphicsLayer {
                                alpha = nearOf(if (isCurrent) focusAnim.value else focus.toFloat())
                            },
                            width = iconW.dp,
                        )
                    }
                }
            }

            // The text: the focused item's name and second line; other items keep their names,
            // except games, which show only the focused one's, as the console's Game column does.
            val focused = i == focus
            if (focused || !isGame) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .offset {
                            val f = if (isCurrent) focusAnim.value else focus.toFloat()
                            val s = scaleOf(nearOf(f))
                            val x = m.colX(index.toFloat(), colAnim.value) + iconW * s / 2f + m.textGap
                            val lineH = if (focused) m.titleSp * 1.25f else m.smallTitleSp * 1.25f
                            IntOffset((x * density).roundToInt(), ((m.itemY(i - f) - lineH * 0.6f) * density).roundToInt())
                        }
                        .widthIn(max = (m.width - m.focusX - 160f * m.u).coerceAtLeast(120f).dp)
                        .graphicsLayer {
                            val f = if (isCurrent) focusAnim.value else focus.toFloat()
                            alpha = alphaOf(f) * levelFade * (if (focused) 1f else 0.82f)
                        },
                ) {
                    Text(
                        node.title,
                        style = xmbText(font, if (focused) m.titleSp else m.smallTitleSp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = node.subtitle
                    if (focused && sub != null) {
                        Text(sub, style = xmbText(font, m.subtitleSp, 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // The format badge on the focused game's second line, as the console marks a PS3 title.
                if (focused && isGame) {
                    XmbIconView(
                        XmbIcon("explore_category_game", "item_tex_ps3format", shadow = false),
                        (14f * m.u).dp,
                        width = (52f * m.u).dp,
                        modifier = Modifier
                            .offset {
                                IntOffset((m.badgeX * density).roundToInt(), ((m.focusedY + 6f * m.u) * density).roundToInt())
                            }
                            .graphicsLayer { alpha = levelFade * (1f - abs(index - colAnim.value)).coerceIn(0f, 1f) },
                    )
                }
            }
        }
    }
}

/**
 * A list opened from an item, where the column was (the column has slid left): its rows laid out
 * as the column's are, a small icon and the name, the focused one with its explanation under it,
 * and a small arrow pointing back to the item it came from. With a picker open ([quiet]) only the
 * focused row keeps its name, as on the console.
 */
@Composable
private fun SubList(level: XmbController.Level, m: Metrics, font: FontFamily, quiet: Boolean) {
    val notDefault = com.armsx2.i18n.str("settings.notDefault")
    val anim = remember(level) { Animatable(level.focus.toFloat()) }
    LaunchedEffect(level, level.focus) { anim.animateTo(level.focus.toFloat(), tween(150, easing = FastOutSlowInEasing)) }
    val appear = remember(level) { Animatable(0f) }
    LaunchedEffect(level) { appear.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
    val others by animateFloatAsState(if (quiet) 0f else 1f, tween(160), label = "xmb-sub-quiet")
    val iconX = m.focusX
    val textX = m.focusX + 34f * m.u
    val children = level.children
    val first = (level.focus - 6).coerceAtLeast(0)
    val last = (level.focus + 10).coerceAtMost(children.size - 1)

    // The arrow back to the item the list belongs to.
    Text(
        "\u25C2",
        style = xmbText(font, m.smallTitleSp, 0.85f),
        modifier = Modifier
            .offset { IntOffset(((m.focusX - 58f * m.u) * density).roundToInt(), ((m.focusedY - m.smallTitleSp * 0.7f) * density).roundToInt()) }
            .graphicsLayer { alpha = appear.value },
    )
    for (i in first..last) {
        val node = children[i]
        key(node.key) {
            val focused = i == level.focus
            fun near() = (1f - abs(i - anim.value)).coerceIn(0f, 1f)
            fun alpha(): Float {
                val d = i - anim.value
                // Rows climbing past the bar fade, as the column's do.
                val far = if (d < -1f) (1f + (d + 1f) * 0.5f).coerceIn(0f, 1f) else 1f
                return appear.value * far
            }
            val icon = 26f * m.u
            Box(
                Modifier
                    .offset {
                        val y = m.itemY(i - anim.value)
                        IntOffset(((iconX - icon / 2f + (1f - appear.value) * 30f * m.u) * density).roundToInt(), ((y - icon / 2f) * density).roundToInt())
                    }
                    .size(icon.dp)
                    .graphicsLayer {
                        val s = 1f + 0.35f * near()
                        scaleX = s
                        scaleY = s
                        alpha = alpha() * (0.7f + 0.3f * near())
                    },
            ) {
                XmbIconView(node.icon, icon.dp)
            }
            if (focused || others > 0f) {
                Column(
                    modifier = Modifier
                        .offset {
                            val y = m.itemY(i - anim.value)
                            val lineH = (if (focused) m.titleSp else m.smallTitleSp) * 1.25f
                            IntOffset(((textX + (1f - appear.value) * 30f * m.u) * density).roundToInt(), ((y - lineH * 0.6f) * density).roundToInt())
                        }
                        .widthIn(max = (m.panelLeft - textX - 20f * m.u).coerceAtLeast(120f).dp)
                        .graphicsLayer { alpha = alpha() * (if (focused) 1f else 0.75f * others) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            node.title,
                            style = xmbText(font, if (focused) m.titleSp else m.smallTitleSp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // Changed from its default, marked as the settings screens mark it.
                        if ((node as? XmbNode.Setting)?.changed?.invoke() == true) {
                            Text(
                                "  " + notDefault,
                                style = xmbText(font, m.subtitleSp * 0.85f).copy(color = NotDefaultRed, fontWeight = FontWeight.Bold),
                                maxLines = 1,
                            )
                        }
                    }
                    if (focused) {
                        val setting = node as? XmbNode.Setting
                        val line = setting?.let { listOfNotNull(it.value(), it.description).joinToString("   ") } ?: node.subtitle
                        if (!line.isNullOrBlank()) {
                            Text(line, style = xmbText(font, m.subtitleSp, 0.92f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        (node as? XmbNode.Action)?.meter?.let { level ->
                            Box(
                                Modifier
                                    .padding(top = (6f * m.u).dp)
                                    .width((240f * m.u).dp)
                                    .height((6f * m.u).dp)
                                    .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(50)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(level().coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .background(Color.White, RoundedCornerShape(50)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The console's side panel: a band of light down the right of the screen, sliding in, in which a
 * setting's choices or an item's options are listed with the focused one level with the row it
 * belongs to.
 */
@Composable
private fun SidePanel(m: Metrics, content: @Composable (slide: Float) -> Unit) {
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) { slide.animateTo(0f, tween(200, easing = FastOutSlowInEasing)) }
    Box(
        Modifier
            .offset { IntOffset(((m.panelLeft + slide.value * (m.width - m.panelLeft)) * density).roundToInt(), 0) }
            .width((m.width - m.panelLeft).dp)
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.10f)),
                ),
            ),
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.28f)))
        content(slide.value)
    }
}

/** A list of [labels] in the side panel, [focus] level with the focused row; [swatches] beside them. */
@Composable
private fun PanelList(
    labels: List<String>,
    focus: Int,
    m: Metrics,
    font: FontFamily,
    swatches: List<Color>? = null,
) {
    val anim = remember { Animatable(focus.toFloat()) }
    LaunchedEffect(focus) { anim.animateTo(focus.toFloat(), tween(120, easing = FastOutSlowInEasing)) }
    labels.forEachIndexed { k, label ->
        val focused = k == focus
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .offset {
                    val y = m.focusedY + (k - anim.value) * m.panelRow - m.panelRow / 2f
                    IntOffset(((24f * m.u) * density).roundToInt(), (y * density).roundToInt())
                }
                .width((m.width - m.panelLeft - 40f * m.u).coerceAtLeast(60f).dp)
                .height(m.panelRow.dp)
                .graphicsLayer {
                    val y = m.focusedY + (k - anim.value) * m.panelRow
                    alpha = if (y < m.catY + m.catIcon || y > m.height - 30f * m.u) 0f else 1f
                }
                .background(if (focused) Color.White.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape((3f * m.u).dp))
                .padding(horizontal = (8f * m.u).dp),
        ) {
            swatches?.getOrNull(k)?.let { c ->
                Box(
                    Modifier
                        .size((16f * m.u).dp)
                        .background(c, RoundedCornerShape((2f * m.u).dp))
                        .border(1.dp, Color.White.copy(alpha = if (focused) 0.9f else 0.35f), RoundedCornerShape((2f * m.u).dp)),
                )
                Spacer(Modifier.width((10f * m.u).dp))
            }
            Text(label, style = xmbText(font, m.smallTitleSp, if (focused) 1f else 0.62f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A setting's picker, in the side panel: its choices, On and Off, or a slider. */
@Composable
private fun PickerPanel(e: XmbController.Editing, m: Metrics, font: FontFamily) {
    SidePanel(m) { _ ->
        when (val ed = e.node.editor) {
            is XmbEditor.Range -> Column(
                Modifier
                    .offset { IntOffset(((28f * m.u) * density).roundToInt(), ((m.focusedY - 34f * m.u) * density).roundToInt()) }
                    .width((m.width - m.panelLeft - 64f * m.u).coerceAtLeast(60f).dp),
            ) {
                Text(ed.format(e.value), style = xmbText(font, m.titleSp * 0.9f))
                Spacer(Modifier.height((10f * m.u).dp))
                Box(Modifier.fillMaxWidth().height((5f * m.u).dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))) {
                    val f = if (ed.max > ed.min) (e.value - ed.min) / (ed.max - ed.min) else 0f
                    Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White, RoundedCornerShape(50)))
                }
            }
            is XmbEditor.Choice -> PanelList(ed.labels, e.choice, m, font, ed.swatches)
            is XmbEditor.Toggle -> {
                val (on, off) = xmbOnOff()
                PanelList(listOf(on, off), e.choice, m, font)
            }
        }
    }
}

/** An item's options (Triangle), in the side panel. */
@Composable
private fun OptionsPanel(o: XmbController.Options, m: Metrics, font: FontFamily) {
    SidePanel(m) { _ ->
        PanelList(o.options.map { it.title }, o.focus, m, font)
    }
}

/** A full panel over the XMB, for an item whose screen is more than a list. */
@Composable
private fun PanelHost(controller: XmbController, panel: XmbNode.Panel, m: Metrics, font: FontFamily) {
    val appear = remember(panel) { Animatable(0f) }
    LaunchedEffect(panel) { appear.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color(0xB8040408)),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = (40f * m.u).dp, end = (24f * m.u).dp, top = (18f * m.u).dp, bottom = (8f * m.u).dp),
            ) {
                XmbIconView(panel.icon, (44f * m.u).dp)
                Spacer(Modifier.width((16f * m.u).dp))
                Text(panel.title, style = xmbText(font, m.titleSp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                HintChip(
                    m,
                    font,
                    "\u25CB",
                    CircleRed,
                    XmbText.get("xmb_ingame", "msg_help_back", ": " + com.armsx2.i18n.str("xmb.back")),
                ) { controller.back() }
            }
            Box(Modifier.fillMaxSize().padding(horizontal = (24f * m.u).dp)) {
                panel.content { controller.back() }
            }
        }
    }
}

/** A button hint in the bottom right corner, its right edge where the console's sits. */
@Composable
private fun CornerHint(m: Metrics, font: FontFamily, glyph: String, glyphColor: Color, label: String, onTap: () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(end = (70f * m.u).dp, bottom = (44f * m.u).dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        HintChip(m, font, glyph, glyphColor, label, onTap)
    }
}

/**
 * The console's dialog: a dark band across the middle with the question, and its buttons under
 * it. Tapping a button picks it; tapping outside the band is Circle.
 */
@Composable
private fun DialogBox(controller: XmbController, d: XmbController.Dialog, m: Metrics, font: FontFamily) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(160, easing = FastOutSlowInEasing)) }
    val edge = Color.White.copy(alpha = 0.32f)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color.Black.copy(alpha = 0.3f))
            .pointerInput(Unit) { detectTapGestures { controller.back() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xE6101016), Color(0xF2060609), Color(0xE6101016))))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(edge))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(vertical = (34f * m.u).dp, horizontal = (80f * m.u).dp),
            ) {
                Text(d.message, style = xmbText(font, m.titleSp * 0.82f), textAlign = TextAlign.Center)
                Spacer(Modifier.height((30f * m.u).dp))
                Row(horizontalArrangement = Arrangement.spacedBy((36f * m.u).dp)) {
                    d.buttons.forEachIndexed { i, button ->
                        val focused = i == d.focus
                        val shape = RoundedCornerShape((4f * m.u).dp)
                        Box(
                            Modifier
                                .widthIn(min = (160f * m.u).dp)
                                .background(Color.White.copy(alpha = if (focused) 0.24f else 0.05f), shape)
                                .border(1.dp, Color.White.copy(alpha = if (focused) 0.7f else 0.18f), shape)
                                .pointerInput(i) {
                                    detectTapGestures {
                                        d.focus = i
                                        controller.confirm()
                                    }
                                }
                                .padding(horizontal = (26f * m.u).dp, vertical = (9f * m.u).dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(button.title, style = xmbText(font, m.smallTitleSp, if (focused) 1f else 0.75f), maxLines = 1)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(edge))
        }
    }
}

@Composable
private fun HintChip(m: Metrics, font: FontFamily, glyph: String, glyphColor: Color, label: String, onTap: () -> Unit) {
    val shape = RoundedCornerShape((3f * m.u).dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.32f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .padding(horizontal = (10f * m.u).dp, vertical = (4f * m.u).dp),
    ) {
        Text(glyph, style = xmbText(font, m.subtitleSp * 1.1f).copy(color = glyphColor))
        Text(" $label", style = xmbText(font, m.subtitleSp * 1.15f), maxLines = 1)
    }
}

/** The clock in its bar along the top right, as the console shows it. */
@Composable
private fun XmbClock(m: Metrics, font: FontFamily) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(10_000)
        }
    }
    val battery by produceState(readBattery(context)) {
        while (true) {
            value = withContext(Dispatchers.IO) { readBattery(context) }
            delay(30_000)
        }
    }
    val avatar = com.armsx2.ui.settings.rememberCurrentAvatar()
    val discord = com.armsx2.DiscordPresence.self.value?.avatarUrl?.takeIf { it.isNotBlank() }
    val text = remember(now) {
        val locale = java.util.Locale.getDefault()
        val date = android.text.format.DateFormat.getBestDateTimePattern(locale, "dM")
        val time = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a"
        java.text.SimpleDateFormat("$date $time", locale).format(java.util.Date(now))
    }
    val barLeft = minOf(887f * m.u, m.width - 300f * m.u)
    Box(
        Modifier
            .offset { IntOffset((barLeft * density).roundToInt(), ((50f * m.u) * density).roundToInt()) }
            .width((m.width - barLeft).dp)
            .height((37f * m.u).dp)
            // The console's is mostly an outline: a faint fill and a light edge.
            .background(
                Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.08f))),
                RoundedCornerShape(topStart = (4f * m.u).dp, bottomStart = (4f * m.u).dp),
            )
            .border(1.dp, Color.White.copy(alpha = 0.34f), RoundedCornerShape(topStart = (4f * m.u).dp, bottomStart = (4f * m.u).dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = (60f * m.u).dp),
        ) {
            // The player's Discord picture first, when an account is linked, then the RPCN avatar
            // before the date, as the console shows its user.
            discord?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size((27f * m.u).dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                )
                Spacer(Modifier.width((9f * m.u).dp))
            }
            avatar?.let {
                Image(
                    it,
                    contentDescription = null,
                    modifier = Modifier.size((27f * m.u).dp).clip(RoundedCornerShape((4f * m.u).dp)).border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape((4f * m.u).dp)),
                )
                Spacer(Modifier.width((12f * m.u).dp))
            }
            Text(text, style = xmbText(font, m.labelSp * 1.2f), maxLines = 1)
            if (battery.first >= 0) {
                Spacer(Modifier.width((16f * m.u).dp))
                XmbBatteryGlyph(battery.first, battery.second, m)
                Spacer(Modifier.width((6f * m.u).dp))
                Text("${battery.first}%", style = xmbText(font, m.labelSp * 1.05f), maxLines = 1)
            }
        }
    }
}

/** The battery as the bar shows it: an outline filled to its level, a bolt over it while charging. */
@Composable
private fun XmbBatteryGlyph(level: Int, charging: Boolean, m: Metrics) {
    androidx.compose.foundation.Canvas(Modifier.width((28f * m.u).dp).height((14f * m.u).dp)) {
        val nub = size.width * 0.09f
        val body = androidx.compose.ui.geometry.Size(size.width - nub, size.height)
        val stroke = size.height * 0.12f
        val r = androidx.compose.ui.geometry.CornerRadius(size.height * 0.22f)
        drawRoundRect(Color.White, size = body, cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(body.width, size.height * 0.3f), size = androidx.compose.ui.geometry.Size(nub, size.height * 0.4f))
        val inset = stroke * 1.8f
        val fill = (level.coerceIn(0, 100) / 100f) * (body.width - inset * 2f)
        val low = level <= 15 && !charging
        drawRoundRect(
            if (low) Color(0xFFFF6B5E) else Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(fill.coerceAtLeast(0f), body.height - inset * 2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.08f),
        )
        if (charging) {
            val bolt = androidx.compose.ui.graphics.Path().apply {
                val w = body.width
                val h = body.height
                moveTo(w * 0.55f, h * 0.08f)
                lineTo(w * 0.32f, h * 0.56f)
                lineTo(w * 0.5f, h * 0.56f)
                lineTo(w * 0.42f, h * 0.92f)
                lineTo(w * 0.7f, h * 0.42f)
                lineTo(w * 0.52f, h * 0.42f)
                close()
            }
            drawPath(bolt, Color(0xFF2A2A2A))
            drawPath(bolt, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(size.height * 0.06f))
        }
    }
}

/** The battery's level (0..100, or -1 when unknown) and whether it is charging, from the sticky broadcast. */
private fun readBattery(context: android.content.Context): Pair<Int, Boolean> {
    val intent = runCatching {
        context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull() ?: return -1 to false
    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
    val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
    val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
    return (if (level >= 0 && scale > 0) level * 100 / scale else -1) to charging
}

/** The Not default tag: the settings screens' red, lightened to read on the XMB's colours. */
private val NotDefaultRed = Color(0xFFFF8A80)
