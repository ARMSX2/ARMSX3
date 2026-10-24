package com.armsx2.ui.emulation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.armsx2.i18n.str
import com.armsx2.input.DimensionsToyPad
import com.armsx2.input.InfinityBase
import com.armsx2.input.PositionPortal
import com.armsx2.input.SkylanderPortal
import com.armsx2.input.ToyPortals
import com.armsx2.input.UsbPassthrough
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.common.FileBrowserDialog
import com.armsx2.ui.common.canBrowse
import com.armsx2.ui.home.LibraryKeyboard
import com.armsx2.ui.settings.controllerFocusable
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The emulated toy portals, in the in-game menu: Skylanders, Disney Infinity and LEGO Dimensions.
 *
 * Every tab reads the same way: Create figure and Add figure files at the top, then the portal itself,
 * drawn with what stands on each of its spots, then the player's figures. Select a spot to lift its
 * figure off or, on the Infinity base and the LEGO toy pad, to choose where the next figure goes.
 *
 * The game sees each change at once, as a toy set down or lifted, and carries on underneath the menu.
 * With a real portal of that kind passed through (UsbPassthrough) the game is not looking at this one,
 * so it says so instead of offering figures that would go nowhere.
 */
@Composable
fun ToyPortalPanel(modifier: Modifier = Modifier) {
    // Opens on the game's own series when its title names one
    var tab by remember {
        val title = MainActivityRuntime.currentGame.value?.title.orEmpty().lowercase()
        mutableIntStateOf(
            when {
                "infinity" in title -> UsbPassthrough.INFINITY
                "dimensions" in title -> UsbPassthrough.DIMENSIONS
                else -> UsbPassthrough.SKYLANDERS
            }
        )
    }
    val real by UsbPassthrough.realPortals

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // The series' full names where they fit, the short ones people use on a narrow screen. Each
            // tab gets room by the length of its name, so the long names are not the ones cut short.
            val names = if (maxWidth < 420.dp) listOf("Skylanders", "Infinity", "Dimensions")
            else listOf("Skylanders", "Disney Infinity", "LEGO Dimensions")
            val padding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEachIndexed { index, name ->
                    val pick = { tab = index }
                    val label: @Composable () -> Unit = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    val focus = Modifier.weight(name.length.toFloat()).controllerFocusable("portal.tab.$index", onConfirm = pick)

                    if (index == tab) {
                        Button(onClick = pick, modifier = focus, contentPadding = padding) { label() }
                    } else {
                        OutlinedButton(onClick = pick, modifier = focus, contentPadding = padding) { label() }
                    }
                }
            }
        }

        if (tab in real) {
            Text(str("portal.real"), style = MaterialTheme.typography.bodyMedium)
        } else {
            when (tab) {
                UsbPassthrough.SKYLANDERS -> SkylanderContent()
                UsbPassthrough.INFINITY -> PositionContent(InfinityBase)
                else -> PositionContent(DimensionsToyPad)
            }
        }
    }
}

/** A line under the lists: what the last action did, or what went wrong with it. */
private class Note(val text: String, val error: Boolean = true)

/** Skylanders: the portal finds a spot for each figure itself, up to 8. */
@Composable
private fun SkylanderContent() {
    // Read when the tab opens, so files copied in meanwhile show up, and again after adding some
    var figures by remember { mutableStateOf(SkylanderPortal.figures()) }
    val folder = remember { SkylanderPortal.folder().absolutePath }
    var notes by remember { mutableStateOf(emptyList<Note>()) }
    var creating by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val fullText = str("portal.full")
    val notFigureText = str("portal.notFigure")
    val createdText = str("portal.create.done")
    val createFailedText = str("portal.create.failed")
    val addFigures = rememberFigureAdder(SkylanderPortal.extensions, SkylanderPortal::add) {
        figures = SkylanderPortal.figures()
        notes = it
    }

    if (creating) {
        CreatorContent(
            remember { SkylanderPortal.characters() },
            { it.name },
            note = str("portal.create.senseis"),
            onBack = { creating = false },
        ) { character ->
            scope.launch {
                val made = withContext(Dispatchers.IO) { SkylanderPortal.create(character) }
                figures = SkylanderPortal.figures()
                notes = listOf(
                    if (made != null) Note(createdText.format(made.substringBeforeLast('.')), error = false)
                    else Note(createFailedText.format(character.name))
                )
                creating = false
            }
        }
        return
    }

    ActionsRow(onCreate = { creating = true }, onAdd = addFigures)

    SkylanderStage(SkylanderPortal.placed, selected) { selected = it }

    val spot = selected
    SpotBar(
        name = spot?.let { str("portal.skylanders.spot").format(it + 1) },
        figure = spot?.let { SkylanderPortal.placed[it] },
        hint = str("portal.hint.skylanders"),
    ) {
        if (spot != null) SkylanderPortal.lift(spot)
        notes = emptyList()
    }

    Header(str("portal.figures"))

    for (file in figures) {
        val onPortal = file.name in SkylanderPortal.placed.values
        FigureRow(file.nameWithoutExtension, str(if (onPortal) "portal.onIt" else "portal.place"), "portal.place.${file.name}", enabled = !onPortal) {
            notes = when (SkylanderPortal.place(file)) {
                SkylanderPortal.Result.Placed -> emptyList()
                SkylanderPortal.Result.Full -> listOf(Note(fullText))
                SkylanderPortal.Result.NotAFigure -> listOf(Note(notFigureText.format(file.name)))
            }
        }
    }

    Footer(figures.isEmpty(), folder, notes)
}

/** Disney Infinity and LEGO Dimensions: select a spot on the drawing, then the figure to put there. */
@Composable
private fun PositionContent(portal: PositionPortal) {
    var figures by remember(portal) { mutableStateOf(portal.figures()) }
    val folder = remember(portal) { portal.folder().absolutePath }
    var selected by remember(portal) { mutableIntStateOf(portal.positions.first().first) }
    var notes by remember(portal) { mutableStateOf(emptyList<Note>()) }
    var creating by remember(portal) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notFigureText = str("portal.notFigure")
    val createdText = str("portal.create.done")
    val createFailedText = str("portal.create.failed")
    val names = portal.positions.associate { (position, label) ->
        position to label.second.let { arg -> if (arg == null) str(label.first) else str(label.first).format(arg) }
    }
    val addFigures = rememberFigureAdder(portal.extensions, portal::add) {
        figures = portal.figures()
        notes = it
    }

    if (creating) {
        CreatorContent(remember(portal) { portal.characters() }, portal::label, onBack = { creating = false }) { character ->
            scope.launch {
                val made = withContext(Dispatchers.IO) { portal.create(character) }
                figures = portal.figures()
                notes = listOf(
                    if (made != null) Note(createdText.format(made.substringBeforeLast('.')), error = false)
                    else Note(createFailedText.format(portal.label(character)))
                )
                creating = false
            }
        }
        return
    }

    ActionsRow(onCreate = { creating = true }, onAdd = addFigures)

    if (portal === InfinityBase) {
        InfinityStage(portal.placed, selected) { selected = it }
    } else {
        DimensionsStage(portal.placed, selected) { selected = it }
    }

    SpotBar(
        name = names[selected],
        figure = portal.placed[selected],
        hint = str("portal.hint.place").format(names[selected].orEmpty()),
    ) {
        portal.lift(selected)
        notes = emptyList()
    }

    Header(str("portal.figures"))

    for (file in figures) {
        val onPortal = file.name in portal.placed.values
        FigureRow(file.nameWithoutExtension, str(if (onPortal) "portal.onIt" else "portal.place"), "portal.place.${file.name}", enabled = !onPortal) {
            notes = if (portal.place(selected, file)) emptyList() else listOf(Note(notFigureText.format(file.name)))
        }
    }

    Footer(figures.isEmpty(), folder, notes)
}

/** Create figure and Add figure files, at the top of every portal's tab. */
@Composable
private fun ActionsRow(onCreate: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(onClick = onCreate, modifier = Modifier.weight(1f).controllerFocusable("portal.create", onConfirm = onCreate)) {
            Text(str("portal.create"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalButton(onClick = onAdd, modifier = Modifier.weight(1f).controllerFocusable("portal.add", onConfirm = onAdd)) {
            Text(str("portal.add"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Under the drawing: the selected spot and what stands on it, with Lift off, or what to do next. */
@Composable
private fun SpotBar(name: String?, figure: String?, hint: String, onLift: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        if (name != null && figure != null) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    figure.substringBeforeLast('.'),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(onClick = onLift, modifier = Modifier.controllerFocusable("portal.lift", onConfirm = onLift)) {
                Text(str("portal.lift"))
            }
        } else {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One spot on a drawn portal: the figure standing there, or what goes there when nothing does. A
 * figure set down lands with a small bounce, as it would on the real thing.
 */
@Composable
private fun Spot(
    position: Int,
    figure: String?,
    emptyLabel: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    big: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    val scale by animateFloatAsState(
        if (figure != null) 1f else 0.94f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "spot",
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (figure != null) accent.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White else accent.copy(alpha = if (figure != null) 0.9f else 0.45f),
                shape,
            )
            .clickable(onClick = onSelect)
            .controllerFocusable("portal.spot.$position", shape, onConfirm = onSelect)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            figure?.substringBeforeLast('.') ?: emptyLabel,
            style = if (big) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (figure != null) FontWeight.SemiBold else FontWeight.Normal,
            color = if (figure != null) Color.White else Color.White.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The Skylanders portal's dark plastic. */
private val PortalBody = Color(0xFF15131F)

/** Skylanders: the round portal in the middle, its eight spots four to a side. */
@Composable
private fun SkylanderStage(placed: Map<Int, String>, selected: Int?, onSelect: (Int) -> Unit) {
    val empty = str("portal.emptyPosition")

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (slot in 0..3) Spot(slot, placed[slot], empty, slot == selected, { onSelect(slot) })
        }
        SkylanderPortalArt(placed.size, Modifier.size(132.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (slot in 4..7) Spot(slot, placed[slot], empty, slot == selected, { onSelect(slot) })
        }
    }
}

/** The Skylanders portal: rings of light turning on its top, brighter the more figures stand on it. */
@Composable
private fun SkylanderPortalArt(figures: Int, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val light = Color(0xFF63D8FF)
    val turning = rememberInfiniteTransition(label = "portal")
    val angle by turning.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "angle")
    val pulse by turning.animateFloat(0.6f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    val level by animateFloatAsState(0.35f + 0.65f * figures.coerceIn(0, 8) / 8f, tween(500), label = "level")

    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(PortalBody, r)
        drawCircle(accent.copy(alpha = 0.6f), r * 0.97f, style = Stroke(r * 0.06f))
        drawCircle(
            Brush.radialGradient(
                listOf(light.copy(alpha = 0.85f * pulse * level), accent.copy(alpha = 0.4f * level), Color.Transparent),
                center,
                r * 0.9f,
            ),
            r * 0.9f,
        )
        rotate(angle) {
            for (i in 0 until 3) {
                drawArc(
                    light.copy(alpha = 0.8f * level),
                    startAngle = i * 120f,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(center.x - r * 0.72f, center.y - r * 0.72f),
                    size = Size(r * 1.44f, r * 1.44f),
                    style = Stroke(r * 0.07f, cap = StrokeCap.Round),
                )
            }
        }
        rotate(-angle * 0.6f) {
            for (i in 0 until 4) {
                drawArc(
                    accent.copy(alpha = 0.7f * level),
                    startAngle = i * 90f + 20f,
                    sweepAngle = 40f,
                    useCenter = false,
                    topLeft = Offset(center.x - r * 0.5f, center.y - r * 0.5f),
                    size = Size(r, r),
                    style = Stroke(r * 0.05f, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** Where a spot sits on a drawn portal: centred at ([fx], [fy]) of the drawing's [w] by [h], [cw] wide. */
private fun Modifier.onArt(w: Dp, h: Dp, fx: Float, fy: Float, cw: Dp, ch: Dp) =
    offset(x = w * fx - cw / 2, y = h * fy - ch / 2).width(cw)

/**
 * Disney Infinity, drawn as the base looks: the hexagon at the back for a play set or power disc and a
 * round pad either side at the front for each player's figure. The power discs that go under the
 * figures and the play set have their spots in a row beneath, under the pad they belong to.
 */
@Composable
private fun InfinityStage(placed: Map<Int, String>, selected: Int, onSelect: (Int) -> Unit) {
    val player = str("portal.infinity.player")
    val playset = str("portal.spot.playset")
    val disc = str("portal.spot.disc")

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val w = minOf(maxWidth, 440.dp)
            val h = w * 0.74f
            val ch = 36.dp

            Box(Modifier.size(w, h)) {
                // Positions are the core's (InfinityBase): 0 the hexagon, 3 Player 1's pad, 6 Player 2's
                InfinityBaseArt(placed[0] != null, placed[3] != null, placed[6] != null, Modifier.fillMaxSize())
                Spot(0, placed[0], playset, selected == 0, { onSelect(0) }, Modifier.onArt(w, h, 0.5f, 0.25f, w * 0.26f, ch), ch, big = true)
                Spot(3, placed[3], player.format(1), selected == 3, { onSelect(3) }, Modifier.onArt(w, h, 0.29f, 0.7f, w * 0.28f, ch), ch, big = true)
                Spot(6, placed[6], player.format(2), selected == 6, { onSelect(6) }, Modifier.onArt(w, h, 0.71f, 0.7f, w * 0.28f, ch), ch, big = true)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (column in listOf(listOf(4, 5), listOf(1, 2), listOf(7, 8))) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (position in column) {
                        Spot(position, placed[position], disc, position == selected, { onSelect(position) }, height = 32.dp)
                    }
                }
            }
        }
    }
}

/**
 * The Infinity base: frosted white plastic under a grey band, the hexagon set into the band, the two
 * round pads in front. A pad glows while something stands on it, as the real one lights up.
 */
@Composable
private fun InfinityBaseArt(hexagon: Boolean, player1: Boolean, player2: Boolean, modifier: Modifier) {
    val glow = Color(0xFF7FD0FF)
    val breathing = rememberInfiniteTransition(label = "infinity")
    val pulse by breathing.animateFloat(0.6f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "pulse")
    val hexLit by animateFloatAsState(if (hexagon) 1f else 0f, tween(400), label = "hexagon")
    val leftLit by animateFloatAsState(if (player1) 1f else 0f, tween(400), label = "player1")
    val rightLit by animateFloatAsState(if (player2) 1f else 0f, tween(400), label = "player2")

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun path(vararg points: Pair<Float, Float>) = Path().apply {
            points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(w * x, h * y) else lineTo(w * x, h * y) }
            close()
        }

        // Flat at the back and the front, pointed at the sides
        val body = path(0.16f to 0.02f, 0.84f to 0.02f, 0.99f to 0.45f, 0.82f to 0.97f, 0.18f to 0.97f, 0.01f to 0.45f)
        // Its shadow on the table
        translate(top = 6.dp.toPx()) { drawPath(body, Color.Black.copy(alpha = 0.35f)) }
        drawPath(body, Brush.verticalGradient(listOf(Color(0xFFF1F4F8), Color(0xFFC9D0DA))))

        // The grey band across the back, dipping under the hexagon
        val band = path(
            0.16f to 0.02f, 0.84f to 0.02f, 0.94f to 0.31f, 0.68f to 0.31f, 0.58f to 0.52f,
            0.42f to 0.52f, 0.32f to 0.31f, 0.06f to 0.31f,
        )
        drawPath(band, Brush.verticalGradient(listOf(Color(0xFFAEB3BB), Color(0xFF8D939B))))
        drawPath(body, Color.White.copy(alpha = 0.9f), style = Stroke(2.dp.toPx()))

        // The hexagon, points to the sides as on the base
        val r = w * 0.14f
        val hexCentre = Offset(w * 0.5f, h * 0.25f)
        val hex = Path().apply {
            for (i in 0 until 6) {
                val a = Math.toRadians(60.0 * i).toFloat()
                val point = Offset(hexCentre.x + r * cos(a), hexCentre.y + r * sin(a))
                if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            close()
        }
        drawPath(hex, Color(0xFFF8F9FB))
        drawPath(hex, Brush.radialGradient(listOf(glow.copy(alpha = 0.9f * pulse * hexLit), Color.Transparent), hexCentre, r * 1.1f))
        drawPath(hex, lerp(Color(0xFF7D838B), glow, hexLit), style = Stroke(2.5.dp.toPx()))

        // The round pads
        for ((x, lit) in listOf(0.29f to leftLit, 0.71f to rightLit)) {
            val centre = Offset(w * x, h * 0.7f)
            val radius = w * 0.15f
            drawCircle(Color(0xFFF4F6F9), radius, centre)
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.9f * pulse * lit), Color.Transparent), centre, radius), radius, centre)
            drawCircle(lerp(Color(0xFFB4BBC4), glow, lit), radius, centre, style = Stroke(2.5.dp.toPx()))
        }
    }
}

/**
 * LEGO Dimensions, drawn as the toy pad looks: the round centre pad at the top, the left and right pads
 * below it either side, each lit in a colour that slowly changes as the real one is.
 */
@Composable
private fun DimensionsStage(placed: Map<Int, String>, selected: Int, onSelect: (Int) -> Unit) {
    val toy = str("portal.spot.toy")

    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.TopCenter) {
        val w = minOf(maxWidth, 460.dp)
        val h = w * 0.74f
        val ch = minOf(30.dp, h * 0.1f)

        Box(Modifier.size(w, h)) {
            // Positions are the core's (DimensionsToyPad): 1 the centre, 0, 3 and 4 the left, 2, 5 and 6 the right
            val left = listOf(0, 3, 4)
            val right = listOf(2, 5, 6)
            ToyPadArt(
                centre = placed[1] != null,
                left = left.count { placed[it] != null } / 3f,
                right = right.count { placed[it] != null } / 3f,
                modifier = Modifier.fillMaxSize(),
            )
            Spot(1, placed[1], toy, selected == 1, { onSelect(1) }, Modifier.onArt(w, h, 0.5f, 0.36f, w * 0.24f, 36.dp), 36.dp, big = true)
            for ((row, fy) in listOf(0.64f, 0.755f, 0.87f).withIndex()) {
                Spot(left[row], placed[left[row]], toy, selected == left[row], { onSelect(left[row]) }, Modifier.onArt(w, h, 0.265f, fy, w * 0.36f, ch), ch)
                Spot(right[row], placed[right[row]], toy, selected == right[row], { onSelect(right[row]) }, Modifier.onArt(w, h, 0.735f, fy, w * 0.36f, ch), ch)
            }
        }
    }
}

/**
 * The LEGO toy pad: a dark grey slab with a brick stud plate in each top corner, the round centre pad,
 * and the two side pads, each an L that runs up beside the centre one. A pad glows more the more toys
 * stand on it.
 */
@Composable
private fun ToyPadArt(centre: Boolean, left: Float, right: Float, modifier: Modifier) {
    val cycling = rememberInfiniteTransition(label = "toypad")
    val hue by cycling.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "hue")
    val centreLit by animateFloatAsState(if (centre) 1f else 0.25f, tween(400), label = "centre")
    val leftLit by animateFloatAsState(0.25f + 0.75f * left, tween(400), label = "left")
    val rightLit by animateFloatAsState(0.25f + 0.75f * right, tween(400), label = "right")
    val panel = Color(0xFFE6E9EE)

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(w * 0.03f)
        fun path(vararg points: Pair<Float, Float>) = Path().apply {
            points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(w * x, h * y) else lineTo(w * x, h * y) }
            close()
        }

        translate(top = 6.dp.toPx()) { drawRoundRect(Color.Black.copy(alpha = 0.35f), cornerRadius = corner) }
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF3A3B40), Color(0xFF26272B))), cornerRadius = corner)
        drawRoundRect(Color(0xFF55575E), cornerRadius = corner, style = Stroke(2.dp.toPx()))

        // A 2x2 brick's studs in each top corner
        for (x in listOf(0.12f, 0.88f)) {
            val plate = w * 0.085f
            drawRoundRect(
                Color(0xFF1E1F22),
                topLeft = Offset(w * x - plate / 2, h * 0.13f - plate / 2),
                size = Size(plate, plate),
                cornerRadius = CornerRadius(plate * 0.12f),
            )
            for (dx in listOf(-1, 1)) for (dy in listOf(-1, 1)) {
                drawCircle(Color(0xFF3C3E43), plate * 0.17f, Offset(w * x + dx * plate * 0.24f, h * 0.13f + dy * plate * 0.24f))
            }
        }

        // The side pads: up beside the centre pad, then the full half below it
        for ((side, lit, offset) in listOf(Triple(0, leftLit, 0f), Triple(1, rightLit, 240f))) {
            val m = if (side == 0) { x: Float -> x } else { x: Float -> 1f - x }
            val zone = path(
                m(0.06f) to 0.3f, m(0.34f) to 0.3f, m(0.34f) to 0.56f, m(0.47f) to 0.56f,
                m(0.47f) to 0.95f, m(0.06f) to 0.95f,
            )
            val light = Color.hsv((hue + offset) % 360f, 0.55f, 1f)
            drawPath(zone, lerp(panel, light, 0.55f * lit))
            drawPath(zone, light.copy(alpha = 0.9f * lit), style = Stroke(2.5.dp.toPx()))
        }

        // The centre pad, ringed
        val c = Offset(w * 0.5f, h * 0.36f)
        val radius = w * 0.13f
        val light = Color.hsv((hue + 120f) % 360f, 0.55f, 1f)
        drawCircle(lerp(panel, light, 0.55f * centreLit), radius, c)
        drawCircle(Color.White.copy(alpha = 0.5f), radius * 0.72f, c, style = Stroke(2.dp.toPx()))
        drawCircle(light.copy(alpha = 0.9f * centreLit), radius, c, style = Stroke(2.5.dp.toPx()))

        // The red light at the front
        drawRoundRect(
            Color(0xFFE5483D),
            topLeft = Offset(w * 0.47f, h * 0.955f),
            size = Size(w * 0.06f, h * 0.03f),
            cornerRadius = CornerRadius(h * 0.01f),
        )
    }
}

/**
 * Adding figure files from the device's storage to a portal's folder. Our own browser where it can see
 * that storage (github build with All files access), which keeps the player in the app with the game
 * paused behind the menu; the system picker otherwise. [onDone] gets what to say about it. Returns the
 * action that opens it.
 */
@Composable
private fun rememberFigureAdder(
    extensions: Set<String>,
    copy: (List<ToyPortals.Offered>) -> ToyPortals.Added,
    onDone: (List<Note>) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var browsing by remember { mutableStateOf(false) }
    val addedText = str("portal.added")
    val notAddedText = str("portal.notAdded")
    val failedText = str("portal.copyFailed")

    // Off the main thread: the files are read, and the system picker's are named by asking their provider
    fun take(offered: () -> List<ToyPortals.Offered>) {
        scope.launch {
            val added = withContext(Dispatchers.IO) { copy(offered()) }
            onDone(
                buildList {
                    if (added.count > 0) add(Note(addedText.format(added.count), error = false))
                    if (added.notFigures.isNotEmpty()) add(Note(notAddedText.format(added.notFigures.joinToString())))
                    if (added.failed.isNotEmpty()) add(Note(failedText.format(added.failed.joinToString())))
                }
            )
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) take { uris.map { ToyPortals.offered(context, it) } }
    }

    if (browsing) {
        FileBrowserDialog(
            title = str("portal.add"),
            extensions = extensions + "zip",
            onPick = { file ->
                browsing = false
                take { listOf(ToyPortals.offered(file)) }
            },
            onDismiss = { browsing = false },
            allowMultiple = true,
            onPickMultiple = { files ->
                browsing = false
                take { files.map { ToyPortals.offered(it) } }
            },
            selectedLabel = str("portal.addSelected"),
        )
    }

    // Figure files have no type of their own to filter on. Without a system picker the activity says so.
    return { if (canBrowse()) browsing = true else picker.launch(arrayOf("*/*")) }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** How many of RPCS3's figures a page of the Create list shows; the pages hold the rest */
private const val PAGE_SIZE = 50

/**
 * RPCS3's list of what a figure can be made of, as its portal managers' Create buttons offer it, a page
 * at a time, with a search by name. Picking one makes a new figure file, which the figures list then
 * shows.
 */
@Composable
private fun CreatorContent(
    characters: List<ToyPortals.Character>,
    label: (ToyPortals.Character) -> String,
    note: String? = null,
    onBack: () -> Unit,
    onPick: (ToyPortals.Character) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val matches = remember(query, characters) {
        val q = query.trim()
        if (q.isEmpty()) characters else characters.filter { label(it).contains(q, ignoreCase = true) }
    }
    val pages = maxOf(1, (matches.size + PAGE_SIZE - 1) / PAGE_SIZE)
    val shown = page.coerceIn(0, pages - 1)
    val makeText = str("portal.create.make")

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            str("portal.create.title"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onBack, modifier = Modifier.controllerFocusable("portal.create.back", onConfirm = onBack)) {
            Text(str("action.back"))
        }
    }

    SearchRow(query, str("portal.create.search")) {
        query = it
        page = 0
    }

    // What the list leaves out and why (the Imaginators Senseis)
    note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    for (character in matches.drop(shown * PAGE_SIZE).take(PAGE_SIZE)) {
        FigureRow(label(character), makeText, "portal.create.${character.first}.${character.second}", enabled = true) {
            onPick(character)
        }
    }

    if (matches.isEmpty()) {
        Text(str("portal.create.none"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (pages > 1) {
        PageRow(shown, pages) { page = it }
    }
}

/**
 * The Create list's search, typed with the controller keyboard (LibraryKeyboard), which takes touch as
 * well. A text field here could not be reached with the pad at all.
 */
@Composable
private fun SearchRow(query: String, placeholder: String, onChange: (String) -> Unit) {
    val edit = { LibraryKeyboard.open(query, onChange, placeholder) }
    val clear = { onChange("") }
    val shape = RoundedCornerShape(14.dp)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .weight(1f)
                .height(46.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .clickable(onClick = edit)
                .controllerFocusable("portal.create.search", shape, onConfirm = edit)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⌕", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                query.ifEmpty { placeholder },
                color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (query.isNotEmpty()) {
            TextButton(onClick = clear, modifier = Modifier.controllerFocusable("portal.create.clear", onConfirm = clear)) {
                Text("✕")
            }
        }
    }
}

/** Previous and next page of the Create list, and where in it the player is. */
@Composable
private fun PageRow(page: Int, pages: Int, onPage: (Int) -> Unit) {
    val previous = { if (page > 0) onPage(page - 1) }
    val next = { if (page < pages - 1) onPage(page + 1) }

    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = previous, enabled = page > 0, modifier = Modifier.controllerFocusable("portal.create.previous", onConfirm = previous)) {
            Text("‹")
        }
        Text(
            str("portal.create.page").format(page + 1, pages),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = next, enabled = page < pages - 1, modifier = Modifier.controllerFocusable("portal.create.next", onConfirm = next)) {
            Text("›")
        }
    }
}

/** Where the files go, or that there are none yet, and what the last action did */
@Composable
private fun Footer(noFigures: Boolean, folder: String, notes: List<Note>) {
    if (noFigures) {
        Text(str("portal.noFigures").format(folder), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    for (note in notes) {
        Text(
            note.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (note.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }

    if (!noFigures) {
        Text(
            str("portal.folder").format(folder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun FigureRow(name: String, action: String, controllerId: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = if (enabled) Modifier.controllerFocusable(controllerId, onConfirm = onClick) else Modifier,
        ) { Text(action) }
    }
}
