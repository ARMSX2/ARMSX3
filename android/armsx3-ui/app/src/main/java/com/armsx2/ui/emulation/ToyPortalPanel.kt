package com.armsx2.ui.emulation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
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
import com.armsx2.ui.common.PadInput
import com.armsx2.ui.common.canBrowse
import com.armsx2.ui.home.LibraryKeyboard
import com.armsx2.ui.settings.SettingsControllerNav
import com.armsx2.ui.settings.controllerFocusable
import com.armsx3.Rpcs3Bridge
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The emulated toy portals, in the in-game menu: Skylanders, Disney Infinity and LEGO Dimensions.
 *
 * Every tab works the same way. Create figure and Add figure files sit at the top, then the portal
 * itself, drawn with what stands on each spot: a figure's name where one stands, a + where one can go.
 * Selecting a spot opens a chooser for it (SpotPicker): what stands there now, with Lift off, then the
 * player's figures, each to put there, or to move there from another spot. B steps back out of the
 * chooser before it closes the panel (ToyPortalBack).
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

/** A line under the portal or a chooser: what the last action did, or what went wrong with it. */
private class Note(val text: String, val error: Boolean = true)

/**
 * What the in-game menu's B asks first while the portal panel is up: a chooser or the figure maker to
 * step back out of. Null on the panel's main view, where B closes the panel.
 */
object ToyPortalBack {
    var handler: (() -> Unit)? = null
}

/** Where a figure went, or why it did not. */
private sealed interface Placing {
    class On(val position: Int) : Placing
    class Refused(val why: String) : Placing
}

/** One portal as the panel works it, so all three go through the same flow ([PortalFlow]). */
private class PortalSpec(
    val key: Any,
    val placed: Map<Int, String>,
    val figures: () -> List<File>,
    val folder: String,
    val extensions: Set<String>,
    val add: (List<ToyPortals.Offered>) -> ToyPortals.Added,
    val characters: () -> List<ToyPortals.Character>,
    val label: (ToyPortals.Character) -> String,
    val creatorNote: String?,
    val create: (ToyPortals.Character) -> String?,
    /** Whether figures stand on spots the player picks. The Skylanders portal picks its own. */
    val positional: Boolean,
    /** The chooser's title, for a spot. */
    val pickTitle: (Int) -> String,
    /** Where a figure stands, as the chooser says it ("On Player 2"). */
    val whereLabel: (Int) -> String,
    /** What to say once a figure, by name, stands on a spot. */
    val placedNote: (String, Int) -> String,
    /** Puts a figure on a spot, off any other spot of this portal first. */
    val place: (Int, File) -> Placing,
    val lift: (Int) -> Unit,
    val hint: String,
)

/** Skylanders: the portal sets each figure down where it last stood, or in the first free place, up to 8. */
@Composable
private fun SkylanderContent() {
    val pickTitle = str("portal.pick.portal")
    val onPortal = str("portal.pick.onPortal")
    val placedText = str("portal.placedOnPortal")
    val fullText = str("portal.full")
    val notFigureText = str("portal.notFigure")
    val spec = PortalSpec(
        key = SkylanderPortal,
        placed = SkylanderPortal.placed,
        figures = SkylanderPortal::figures,
        folder = remember { SkylanderPortal.folder().absolutePath },
        extensions = SkylanderPortal.extensions,
        add = SkylanderPortal::add,
        characters = SkylanderPortal::characters,
        label = { it.name },
        creatorNote = str("portal.create.senseis"),
        create = SkylanderPortal::create,
        positional = false,
        pickTitle = { pickTitle },
        whereLabel = { onPortal },
        placedNote = { name, _ -> placedText.format(name) },
        place = { _, file ->
            when (SkylanderPortal.place(file)) {
                SkylanderPortal.Result.Placed ->
                    Placing.On(SkylanderPortal.placed.entries.firstOrNull { it.value == file.name }?.key ?: 0)
                SkylanderPortal.Result.Full -> Placing.Refused(fullText)
                SkylanderPortal.Result.NotAFigure -> Placing.Refused(notFigureText.format(file.name))
            }
        },
        lift = SkylanderPortal::lift,
        hint = str("portal.hint.skylanders"),
    )
    PortalFlow(spec) { onOpen -> SkylanderStage(SkylanderPortal.placed, onOpen) }
}

/** Disney Infinity and LEGO Dimensions: each figure stands on the spot the player picks for it. */
@Composable
private fun PositionContent(portal: PositionPortal) {
    val pickTitle = str("portal.pick.title")
    val onText = str("portal.pick.on")
    val placedText = str("portal.placedOn")
    val notFigureText = str("portal.notFigure")
    val names = portal.positions.associate { (position, label) ->
        position to label.second.let { arg -> if (arg == null) str(label.first) else str(label.first).format(arg) }
    }
    val spec = PortalSpec(
        key = portal,
        placed = portal.placed,
        figures = portal::figures,
        folder = remember(portal) { portal.folder().absolutePath },
        extensions = portal.extensions,
        add = portal::add,
        characters = portal::characters,
        label = portal::label,
        creatorNote = null,
        create = portal::create,
        positional = true,
        pickTitle = { pickTitle.format(names[it].orEmpty()) },
        whereLabel = { onText.format(names[it].orEmpty()) },
        placedNote = { name, position -> placedText.format(name, names[position].orEmpty()) },
        place = { position, file ->
            // Moved from another spot: off there first, as a toy is picked up and set down again
            portal.placed.entries.firstOrNull { it.value == file.name }?.key?.let { if (it != position) portal.lift(it) }
            if (portal.place(position, file)) Placing.On(position) else Placing.Refused(notFigureText.format(file.name))
        },
        lift = portal::lift,
        hint = str("portal.hint.spots"),
    )
    PortalFlow(spec) { onOpen ->
        if (portal === InfinityBase) InfinityStage(portal.placed, onOpen) else DimensionsStage(portal.placed, onOpen)
    }
}

/**
 * A portal's tab: the portal with its spots, a spot's chooser once one is selected, the figure maker
 * when asked for. A figure made from a spot's chooser goes straight onto that spot.
 */
@Composable
private fun PortalFlow(spec: PortalSpec, stage: @Composable (onOpen: (Int) -> Unit) -> Unit) {
    // Read when the tab opens, so files copied in meanwhile show up, and again after adding or making some
    var figures by remember(spec.key) { mutableStateOf(spec.figures()) }
    var notes by remember(spec.key) { mutableStateOf(emptyList<Note>()) }
    var picking by remember(spec.key) { mutableStateOf<Int?>(null) }
    var creating by remember(spec.key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val land = rememberPadLanding()
    val liftedText = str("portal.liftedOff")
    val createdText = str("portal.create.done")
    val createFailedText = str("portal.create.failed")
    val addFigures = rememberFigureAdder(spec.extensions, spec.add) {
        figures = spec.figures()
        notes = it
    }

    fun open(position: Int) {
        notes = emptyList()
        picking = position
        // Lift off when something stands there, else the first figure that could go there
        val free = figures.firstOrNull { it.name !in spec.placed.values }
        land(listOfNotNull("portal.pick.lift".takeIf { spec.placed[position] != null }, free?.let { "portal.pick.${it.name}" }, "portal.create"))
    }

    /** Back to the portal, the pad on [position]'s spot. */
    fun close(position: Int?) {
        creating = false
        picking = null
        land(listOfNotNull(position?.let { "portal.spot.$it" }, "portal.create"))
    }

    fun put(position: Int, file: File) {
        when (val result = spec.place(position, file)) {
            is Placing.On -> {
                close(result.position)
                notes = listOf(Note(spec.placedNote(file.nameWithoutExtension, result.position), error = false))
            }
            is Placing.Refused -> notes = listOf(Note(result.why))
        }
    }

    val startCreating = {
        creating = true
        land(listOf("portal.create.search"))
    }
    val stopCreating = {
        creating = false
        land(listOf("portal.create"))
    }

    val position = picking
    DisposableEffect(spec.key, position, creating) {
        ToyPortalBack.handler = when {
            creating -> stopCreating
            position != null -> ({ close(position) })
            else -> null
        }
        onDispose { ToyPortalBack.handler = null }
    }

    if (creating) {
        CreatorContent(remember(spec.key) { spec.characters() }, spec.label, spec.creatorNote, onBack = stopCreating) { character ->
            scope.launch {
                val made = withContext(Dispatchers.IO) { spec.create(character) }
                figures = spec.figures()
                val file = made?.let { name -> figures.firstOrNull { it.name == name } }
                val target = picking
                when {
                    file == null -> {
                        notes = listOf(Note(createFailedText.format(spec.label(character))))
                        stopCreating()
                    }
                    // Made from a spot's chooser: onto that spot
                    target != null -> {
                        creating = false
                        put(target, file)
                        notes = listOf(Note(createdText.format(file.nameWithoutExtension), error = false)) + notes
                    }
                    else -> {
                        notes = listOf(Note(createdText.format(file.nameWithoutExtension), error = false))
                        stopCreating()
                    }
                }
            }
        }
        return
    }

    if (position != null) {
        SpotPicker(
            title = spec.pickTitle(position),
            position = position,
            figures = figures,
            placed = spec.placed,
            positional = spec.positional,
            whereLabel = spec.whereLabel,
            onLift = {
                val name = spec.placed[position]?.substringBeforeLast('.')
                spec.lift(position)
                close(position)
                notes = listOfNotNull(name?.let { Note(liftedText.format(it), error = false) })
            },
            onPlace = { put(position, it) },
            onCreate = startCreating,
            onAdd = addFigures,
            onBack = { close(position) },
        )
        Footer(figures.isEmpty(), spec.folder, notes)
        return
    }

    ActionsRow(onCreate = startCreating, onAdd = addFigures)
    stage(::open)
    Text(spec.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Footer(figures.isEmpty(), spec.folder, notes)
}

/**
 * Choosing what stands on one spot: what is there now, with Lift off, then the player's figures, each to
 * put there or, on the Infinity base and the LEGO toy pad, to move there from the spot it stands on.
 */
@Composable
private fun SpotPicker(
    title: String,
    position: Int,
    figures: List<File>,
    placed: Map<Int, String>,
    positional: Boolean,
    whereLabel: (Int) -> String,
    onLift: () -> Unit,
    onPlace: (File) -> Unit,
    onCreate: () -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    val placeText = str("portal.place")
    val hereText = str("portal.pick.here")
    val moveText = str("portal.pick.move")
    val current = placed[position]

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onBack, modifier = Modifier.controllerFocusable("portal.pick.back", onConfirm = onBack)) {
            Text(str("action.back"))
        }
    }

    if (current != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(str("portal.pick.current"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    current.substringBeforeLast('.'),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(onClick = onLift, modifier = Modifier.controllerFocusable("portal.pick.lift", onConfirm = onLift)) {
                Text(str("portal.lift"))
            }
        }
    }

    ActionsRow(onCreate = onCreate, onAdd = onAdd)
    Header(str("portal.figures"))

    for (file in figures) {
        val at = placed.entries.firstOrNull { it.value == file.name }?.key
        val id = "portal.pick.${file.name}"
        val name = file.nameWithoutExtension
        when {
            at == null -> FigureRow(name, placeText, id, enabled = true) { onPlace(file) }
            !positional -> FigureRow(name, whereLabel(at), id, enabled = false)
            at == position -> FigureRow(name, hereText, id, enabled = false)
            else -> FigureRow(name, moveText, id, enabled = true, caption = whereLabel(at)) { onPlace(file) }
        }
    }
}

/** Create figure and Add figure files, at the top of every portal's tab and every chooser. */
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

/**
 * Landing the pad's selection on a control once the view under it changes. A selection whose control
 * goes falls to the top of the panel, so with the pad driving this lets go first, then selects the first
 * of the given controls that is on screen.
 */
@Composable
private fun rememberPadLanding(): (List<String>) -> Unit {
    var targets by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(targets) {
        val ids = targets ?: return@LaunchedEffect
        var attempts = 0
        while (ids.none { SettingsControllerNav.selectById(it) } && attempts < 10) {
            attempts++
            withFrameNanos {}
        }
        targets = null
    }
    return remember {
        { ids: List<String> ->
            if (PadInput.active.value) {
                SettingsControllerNav.clearSelection()
                targets = ids
            }
        }
    }
}

/** The spot the pad is on, while the pad drives. */
@Composable
private fun padSpot(): Int? =
    SettingsControllerNav.currentSelectedId()
        ?.takeIf { PadInput.active.value && it.startsWith("portal.spot.") }
        ?.removePrefix("portal.spot.")
        ?.toIntOrNull()

/**
 * One spot on a drawn portal: the figure standing there, or what can go there. A figure set down lands
 * with a small bounce, as it would on the real thing. The pad's spot grows and fills, which shows on the
 * light Infinity and LEGO pads too. [onOpen] null is a spot that takes nothing yet.
 */
@Composable
private fun Spot(
    position: Int,
    figure: String?,
    emptyLabel: String,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    big: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    val id = "portal.spot.$position"
    val padHere = onOpen != null && PadInput.active.value && SettingsControllerNav.currentSelectedId() == id
    val scale by animateFloatAsState(
        when {
            padHere -> 1.08f
            figure != null -> 1f
            else -> 0.94f
        },
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
            .background(
                when {
                    figure != null -> accent.copy(alpha = 0.85f)
                    padHere -> accent.copy(alpha = 0.5f)
                    onOpen == null -> Color.Black.copy(alpha = 0.3f)
                    else -> Color.Black.copy(alpha = 0.55f)
                }
            )
            .border(
                if (padHere) 2.5.dp else 1.dp,
                if (padHere) Color.White else accent.copy(alpha = if (figure != null) 0.9f else 0.45f),
                shape,
            )
            .then(
                if (onOpen != null) Modifier.clickable(onClick = onOpen).controllerFocusable(id, shape, onConfirm = onOpen)
                else Modifier
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            figure?.substringBeforeLast('.') ?: emptyLabel,
            style = if (big) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (figure != null || padHere) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                figure != null || padHere -> Color.White
                onOpen == null -> Color.White.copy(alpha = 0.45f)
                else -> Color.White.copy(alpha = 0.85f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The Skylanders portal's dark plastic. */
private val PortalBody = Color(0xFF15131F)

/** The Skylanders portal's light until the game sets one. */
private val PortalLight = Color(0xFF63D8FF)

/**
 * Skylanders: the round portal in the middle with the figures on it standing on its top, its eight
 * places four to a side. The + sits on the first free place, where the portal sets the next figure down
 * unless that figure stood elsewhere before. The portal glows the colour the game lights it, which for a
 * character in play is its element's.
 */
@Composable
private fun SkylanderStage(placed: Map<Int, String>, onOpen: (Int) -> Unit) {
    val empty = str("portal.emptyPosition")
    val add = "+ " + str("portal.addFigure")
    val next = (0..7).firstOrNull { placed[it] == null }
    // A few reads a second while the tab is up: three bytes from the core
    val light by produceState(PortalLight) {
        while (true) {
            val rgb = withContext(Dispatchers.IO) { Rpcs3Bridge.skylanderLight() }
            value = if (rgb != 0) Color(0xFF000000.toInt() or rgb) else PortalLight
            delay(250)
        }
    }

    @Composable
    fun place(slot: Int) {
        val open = placed[slot] != null || slot == next
        Spot(slot, placed[slot], if (slot == next) add else empty, if (open) ({ onOpen(slot) }) else null)
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (slot in 0..3) place(slot)
        }
        SkylanderPortalArt(placed.keys.sorted(), light, Modifier.size(132.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (slot in 4..7) place(slot)
        }
    }
}

/**
 * The Skylanders portal: rings of light turning on its top in the portal's [light], brighter the more
 * figures stand on it, and those figures ([standing], by place) standing there.
 */
@Composable
private fun SkylanderPortalArt(standing: List<Int>, light: Color, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val glow by animateColorAsState(light, tween(600), label = "light")
    val turning = rememberInfiniteTransition(label = "portal")
    val angle by turning.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "angle")
    val pulse by turning.animateFloat(0.6f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    val level by animateFloatAsState(0.35f + 0.65f * standing.size.coerceIn(0, 8) / 8f, tween(500), label = "level")
    val grows = (0..7).map { slot -> animateFloatAsState(if (slot in standing) 1f else 0f, SetDown, label = "figure$slot").value }

    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(PortalBody, r)
        drawCircle(accent.copy(alpha = 0.6f), r * 0.97f, style = Stroke(r * 0.06f))
        drawCircle(
            Brush.radialGradient(
                listOf(glow.copy(alpha = 0.85f * pulse * level), accent.copy(alpha = 0.4f * level), Color.Transparent),
                center,
                r * 0.9f,
            ),
            r * 0.9f,
        )
        rotate(angle) {
            for (i in 0 until 3) {
                drawArc(
                    glow.copy(alpha = 0.8f * level),
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

        // The figures on it: one or two side by side, more in a ring, nearer ones drawn over farther ones
        val n = standing.size
        val tall = when {
            n <= 2 -> r * 0.62f
            n <= 4 -> r * 0.5f
            else -> r * 0.42f
        }
        val feet = when (n) {
            0 -> emptyList()
            1 -> listOf(Offset(center.x, center.y + r * 0.32f))
            2 -> listOf(Offset(center.x - r * 0.3f, center.y + r * 0.32f), Offset(center.x + r * 0.3f, center.y + r * 0.32f))
            else -> standing.indices.map { i ->
                val a = (Math.PI * 2 * i / n - Math.PI / 2).toFloat()
                Offset(center.x + r * 0.42f * cos(a), center.y + r * 0.42f * sin(a) + tall * 0.45f)
            }
        }
        standing.zip(feet).sortedBy { it.second.y }.forEach { (slot, foot) ->
            toyFigure(foot, tall, Color.White, Color(0xFFCFE9FF), Color.Black.copy(alpha = 0.35f), glow, square = false, grow = grows[slot])
        }
    }
}

/** How a toy lands when it is set down: a little bounce, as on the real thing. */
private val SetDown = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)

/**
 * A toy standing on a pad: a plain figure on its base, the shape of any toy and the likeness of none.
 * [square] gives it the LEGO toy pad's brick base and head. [foot] is the middle of its base and [size]
 * its height; [grow] scales it up from its base as it is set down.
 */
private fun DrawScope.toyFigure(
    foot: Offset,
    size: Float,
    top: Color,
    bottom: Color,
    outline: Color,
    glow: Color,
    square: Boolean,
    grow: Float,
) {
    if (grow <= 0.01f) return
    scale(grow, foot) {
        val h = size
        val w = h * 0.62f
        val x = foot.x
        val y = foot.y
        val halo = Offset(x, y - h * 0.45f)
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.55f), Color.Transparent), halo, h * 0.75f), h * 0.75f, halo)

        val body = Path().apply {
            // Base, legs, body, arms and head
            if (square) addRoundRect(RoundRect(Rect(x - w * 0.5f, y - h * 0.13f, x + w * 0.5f, y), CornerRadius(h * 0.03f)))
            else addOval(Rect(x - w * 0.52f, y - h * 0.13f, x + w * 0.52f, y))
            addRoundRect(RoundRect(Rect(x - w * 0.21f, y - h * 0.44f, x - w * 0.02f, y - h * 0.11f), CornerRadius(w * 0.05f)))
            addRoundRect(RoundRect(Rect(x + w * 0.02f, y - h * 0.44f, x + w * 0.21f, y - h * 0.11f), CornerRadius(w * 0.05f)))
            moveTo(x - w * 0.27f, y - h * 0.74f)
            lineTo(x + w * 0.27f, y - h * 0.74f)
            lineTo(x + w * 0.21f, y - h * 0.42f)
            lineTo(x - w * 0.21f, y - h * 0.42f)
            close()
            for (side in listOf(-1f, 1f)) {
                moveTo(x + side * w * 0.27f, y - h * 0.73f)
                lineTo(x + side * w * 0.36f, y - h * 0.47f)
                lineTo(x + side * w * 0.25f, y - h * 0.45f)
                lineTo(x + side * w * 0.19f, y - h * 0.66f)
                close()
            }
            if (square) {
                addRoundRect(RoundRect(Rect(x - w * 0.17f, y - h * 0.97f, x + w * 0.17f, y - h * 0.76f), CornerRadius(w * 0.06f)))
                addRoundRect(RoundRect(Rect(x - w * 0.08f, y - h * 1.01f, x + w * 0.08f, y - h * 0.96f), CornerRadius(w * 0.02f)))
            } else {
                addOval(Rect(Offset(x, y - h * 0.87f), h * 0.12f))
            }
        }
        drawPath(body, Brush.verticalGradient(listOf(top, bottom), startY = y - h, endY = y))
        drawPath(body, outline, style = Stroke(1.2.dp.toPx()))
    }
}

/** A play set's piece on the Infinity hexagon: a glowing hexagonal crystal, the shape of any and the likeness of none. */
private fun DrawScope.playSetPiece(centre: Offset, radius: Float, top: Color, bottom: Color, glow: Color, grow: Float) {
    if (grow <= 0.01f) return
    scale(grow, centre) {
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.6f), Color.Transparent), centre, radius * 1.8f), radius * 1.8f, centre)
        val depth = radius * 0.7f
        fun face(dy: Float) = Path().apply {
            for (i in 0 until 6) {
                val a = Math.toRadians(60.0 * i).toFloat()
                val px = centre.x + radius * cos(a)
                val py = centre.y + dy + radius * 0.55f * sin(a)
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
        val sides = Path().apply {
            moveTo(centre.x - radius, centre.y - depth / 2)
            lineTo(centre.x + radius, centre.y - depth / 2)
            lineTo(centre.x + radius, centre.y + depth / 2)
            lineTo(centre.x - radius, centre.y + depth / 2)
            close()
        }
        drawPath(face(depth / 2), bottom)
        drawPath(sides, Brush.verticalGradient(listOf(bottom, lerp(bottom, Color.Black, 0.2f)), startY = centre.y - depth / 2, endY = centre.y + depth / 2))
        drawPath(face(-depth / 2), Brush.verticalGradient(listOf(top, lerp(top, bottom, 0.4f)), startY = centre.y - depth, endY = centre.y))
        drawPath(face(-depth / 2), Color.White.copy(alpha = 0.9f), style = Stroke(1.2.dp.toPx()))
    }
}

/** A closed polygon through [points], its corners rounded by [radius]. */
private fun roundedPolygon(points: List<Offset>, radius: Float) = Path().apply {
    val n = points.size
    for (i in 0 until n) {
        val before = points[(i - 1 + n) % n]
        val corner = points[i]
        val after = points[(i + 1) % n]
        val inLength = (corner - before).getDistance()
        val outLength = (after - corner).getDistance()
        val r = minOf(radius, inLength / 2, outLength / 2)
        val a = corner + (before - corner) * (r / inLength)
        val b = corner + (after - corner) * (r / outLength)
        if (i == 0) moveTo(a.x, a.y) else lineTo(a.x, a.y)
        quadraticTo(corner.x, corner.y, b.x, b.y)
    }
    close()
}

/** Where a spot sits on a drawn portal: centred at ([fx], [fy]) of the drawing's [w] by [h], [cw] wide. */
private fun Modifier.onArt(w: Dp, h: Dp, fx: Float, fy: Float, cw: Dp, ch: Dp) =
    offset(x = w * fx - cw / 2, y = h * fy - ch / 2).width(cw)

/** A pad's name printed on the drawing, centred at ([fx], [fy]), dark on the light plastic. */
@Composable
private fun ArtCaption(text: String, w: Dp, h: Dp, fx: Float, fy: Float) {
    val cw = w * 0.34f
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF454B54),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.offset(x = w * fx - cw / 2, y = h * fy - 9.dp).width(cw),
    )
}

/**
 * Disney Infinity, drawn as the base looks: the hexagon at the back for a play set or power disc, and
 * each player's round pad at the front, named on the drawing. What stands on a pad stands on it in the
 * drawing too, its spot moved down under it. The power discs that go under the figures and the play
 * set have their spots beneath, under the name of what they go with.
 */
@Composable
private fun InfinityStage(placed: Map<Int, String>, onOpen: (Int) -> Unit) {
    val player = str("portal.infinity.player")
    val playset = str("portal.spot.playset")
    val figure = "+ " + str("portal.spot.figure")
    val disc = "+ " + str("portal.spot.disc")
    // Positions are the core's (InfinityBase): 0 to 2 the hexagon, 3 to 5 Player 1, 6 to 8 Player 2
    val pad = when (padSpot()) {
        0, 1, 2 -> 0
        3, 4, 5 -> 3
        6, 7, 8 -> 6
        else -> null
    }
    val grows = listOf(0, 3, 6).associateWith { animateFloatAsState(if (placed[it] != null) 1f else 0f, SetDown, label = "on$it").value }
    val playsetY by animateFloatAsState(if (placed[0] != null) 0.31f else 0.25f, tween(250), label = "playsetY")
    val player1Y by animateFloatAsState(if (placed[3] != null) 0.8f else 0.7f, tween(250), label = "player1Y")
    val player2Y by animateFloatAsState(if (placed[6] != null) 0.8f else 0.7f, tween(250), label = "player2Y")

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val w = minOf(maxWidth, 440.dp)
            val h = w * 0.74f
            val ch = 36.dp

            Box(Modifier.size(w, h)) {
                InfinityBaseArt(placed[0] != null, placed[3] != null, placed[6] != null, pad, grows, Modifier.fillMaxSize())
                ArtCaption(player.format(1), w, h, 0.29f, 0.455f)
                ArtCaption(player.format(2), w, h, 0.71f, 0.455f)
                Spot(0, placed[0], "+ $playset", { onOpen(0) }, Modifier.onArt(w, h, 0.5f, playsetY, w * 0.26f, ch), ch, big = true)
                Spot(3, placed[3], figure, { onOpen(3) }, Modifier.onArt(w, h, 0.29f, player1Y, w * 0.28f, ch), ch, big = true)
                Spot(6, placed[6], figure, { onOpen(6) }, Modifier.onArt(w, h, 0.71f, player2Y, w * 0.28f, ch), ch, big = true)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, column) in listOf(player.format(1) to listOf(4, 5), playset to listOf(1, 2), player.format(2) to listOf(7, 8))) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    for (position in column) {
                        Spot(position, placed[position], disc, { onOpen(position) }, height = 32.dp)
                    }
                }
            }
        }
    }
}

/**
 * The Infinity base: frosted white plastic under a grey band, the hexagon set into the band, the two
 * round pads in front. A pad glows while something stands on it, as the real one lights up, and shows
 * it standing there ([grows] by position, 0, 3 and 6, for how far each has been set down). The pad the
 * pad's selection is on ([focus]: 0 the hexagon, 3 Player 1's, 6 Player 2's) is ringed.
 */
@Composable
private fun InfinityBaseArt(
    hexagon: Boolean,
    player1: Boolean,
    player2: Boolean,
    focus: Int?,
    grows: Map<Int, Float>,
    modifier: Modifier,
) {
    val glow = Color(0xFF7FD0FF)
    val accent = MaterialTheme.colorScheme.primary
    val toyTop = lerp(accent, Color.White, 0.6f)
    val toyBottom = lerp(accent, Color.Black, 0.1f)
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
        fun hexPath(radius: Float) = Path().apply {
            for (i in 0 until 6) {
                val a = Math.toRadians(60.0 * i).toFloat()
                val point = Offset(hexCentre.x + radius * cos(a), hexCentre.y + radius * sin(a))
                if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            close()
        }
        val hex = hexPath(r)
        drawPath(hex, Color(0xFFF8F9FB))
        drawPath(hex, Brush.radialGradient(listOf(glow.copy(alpha = 0.9f * pulse * hexLit), Color.Transparent), hexCentre, r * 1.1f))
        drawPath(hex, lerp(Color(0xFF7D838B), glow, hexLit), style = Stroke(2.5.dp.toPx()))
        if (focus == 0) drawPath(hexPath(r + 5.dp.toPx()), accent.copy(alpha = pulse), style = Stroke(4.dp.toPx()))

        // The round pads
        for ((spot, x, lit) in listOf(Triple(3, 0.29f, leftLit), Triple(6, 0.71f, rightLit))) {
            val centre = Offset(w * x, h * 0.7f)
            val radius = w * 0.15f
            drawCircle(Color(0xFFF4F6F9), radius, centre)
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.9f * pulse * lit), Color.Transparent), centre, radius), radius, centre)
            drawCircle(lerp(Color(0xFFB4BBC4), glow, lit), radius, centre, style = Stroke(2.5.dp.toPx()))
            if (focus == spot) drawCircle(accent.copy(alpha = pulse), radius + 5.dp.toPx(), centre, style = Stroke(4.dp.toPx()))
        }

        // What stands on them
        playSetPiece(Offset(w * 0.5f, h * 0.17f), w * 0.055f, toyTop, toyBottom, glow, grows[0] ?: 0f)
        toyFigure(Offset(w * 0.29f, h * 0.75f), h * 0.2f, toyTop, toyBottom, Color.White.copy(alpha = 0.9f), glow, square = false, grow = grows[3] ?: 0f)
        toyFigure(Offset(w * 0.71f, h * 0.75f), h * 0.2f, toyTop, toyBottom, Color.White.copy(alpha = 0.9f), glow, square = false, grow = grows[6] ?: 0f)
    }
}

/**
 * LEGO Dimensions, drawn as the toy pad looks: the round centre pad at the top, the left and right pads
 * below it either side, named on the drawing and each lit in a colour that slowly changes as the real
 * one is. Toys stand on the drawing where they stand on the pad, the centre's spot moved down under it.
 */
@Composable
private fun DimensionsStage(placed: Map<Int, String>, onOpen: (Int) -> Unit) {
    val toy = "+ " + str("portal.spot.toy")
    // Positions are the core's (DimensionsToyPad): 1 the centre, 0, 3 and 4 the left, 2, 5 and 6 the right
    val left = listOf(0, 3, 4)
    val right = listOf(2, 5, 6)
    val pad = when (padSpot()) {
        1 -> 1
        in left -> 0
        in right -> 2
        else -> null
    }
    val grows = (0..6).map { animateFloatAsState(if (placed[it] != null) 1f else 0f, SetDown, label = "toy$it").value }
    val centreY by animateFloatAsState(if (placed[1] != null) 0.43f else 0.36f, tween(250), label = "centreY")

    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.TopCenter) {
        val w = minOf(maxWidth, 460.dp)
        val h = w * 0.74f
        val ch = minOf(30.dp, h * 0.1f)

        Box(Modifier.size(w, h)) {
            ToyPadArt(
                centre = placed[1] != null,
                left = left.count { placed[it] != null } / 3f,
                right = right.count { placed[it] != null } / 3f,
                focus = pad,
                grows = grows,
                modifier = Modifier.fillMaxSize(),
            )
            ArtCaption(str("portal.dimensions.leftPad"), w, h, 0.2f, 0.335f)
            ArtCaption(str("portal.dimensions.rightPad"), w, h, 0.8f, 0.335f)
            Spot(1, placed[1], toy, { onOpen(1) }, Modifier.onArt(w, h, 0.5f, centreY, w * 0.24f, 36.dp), 36.dp, big = true)
            for ((row, fy) in listOf(0.64f, 0.755f, 0.87f).withIndex()) {
                Spot(left[row], placed[left[row]], toy, { onOpen(left[row]) }, Modifier.onArt(w, h, 0.265f, fy, w * 0.36f, ch), ch)
                Spot(right[row], placed[right[row]], toy, { onOpen(right[row]) }, Modifier.onArt(w, h, 0.735f, fy, w * 0.36f, ch), ch)
            }
        }
    }
}

/**
 * The LEGO toy pad: a dark slab set with a 2x2 brick's studs in each top corner, and in its well the
 * round centre pad and the two side pads, each an L that runs up beside the centre one. The pads are
 * frosted panels lit from below, glowing more the more toys stand on them, and the toys stand on them
 * here too ([grows] by position, for how far each has been set down). The pad the pad's selection is
 * on ([focus]: 0 left, 1 centre, 2 right) is ringed.
 */
@Composable
private fun ToyPadArt(centre: Boolean, left: Float, right: Float, focus: Int?, grows: List<Float>, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val toyTop = lerp(accent, Color.White, 0.6f)
    val toyBottom = lerp(accent, Color.Black, 0.1f)
    val cycling = rememberInfiniteTransition(label = "toypad")
    val hue by cycling.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "hue")
    val pulse by cycling.animateFloat(0.6f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "pulse")
    val centreLit by animateFloatAsState(if (centre) 1f else 0.25f, tween(400), label = "centre")
    val leftLit by animateFloatAsState(0.25f + 0.75f * left, tween(400), label = "left")
    val rightLit by animateFloatAsState(0.25f + 0.75f * right, tween(400), label = "right")
    val frosted = Color(0xFFE6E9EE)
    val frostedEdge = Color(0xFFC9CED6)

    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // The slab, lit a little from the top left, with a rim and a bevel
        val corner = CornerRadius(w * 0.05f)
        translate(top = 6.dp.toPx()) { drawRoundRect(Color.Black.copy(alpha = 0.35f), cornerRadius = corner) }
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF4B4E55), Color(0xFF2B2D32))), cornerRadius = corner)
        drawRoundRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), Offset(w * 0.2f, h * 0.05f), w * 0.6f), cornerRadius = corner)
        drawRoundRect(Color(0xFF1A1B1E), cornerRadius = corner, style = Stroke(2.dp.toPx()))
        val inset = 3.dp.toPx()
        drawRoundRect(
            Color.White.copy(alpha = 0.07f),
            topLeft = Offset(inset, inset),
            size = Size(w - inset * 2, h - inset * 2),
            cornerRadius = CornerRadius(w * 0.045f),
            style = Stroke(1.dp.toPx()),
        )
        // The well the pads sit in
        drawRoundRect(Color(0xFF202125), topLeft = Offset(w * 0.035f, h * 0.265f), size = Size(w * 0.93f, h * 0.705f), cornerRadius = CornerRadius(w * 0.03f))

        // A 2x2 brick's studs in each top corner
        for (x in listOf(0.12f, 0.88f)) {
            val plate = w * 0.09f
            val cx = w * x
            val cy = h * 0.13f
            val plateTopLeft = Offset(cx - plate / 2, cy - plate / 2)
            drawRoundRect(Color(0xFF2E3035), topLeft = plateTopLeft, size = Size(plate, plate), cornerRadius = CornerRadius(plate * 0.12f))
            drawRoundRect(Color(0xFF1C1D20), topLeft = plateTopLeft, size = Size(plate, plate), cornerRadius = CornerRadius(plate * 0.12f), style = Stroke(1.dp.toPx()))
            for (dx in listOf(-1, 1)) for (dy in listOf(-1, 1)) {
                val stud = Offset(cx + dx * plate * 0.24f, cy + dy * plate * 0.24f)
                val sr = plate * 0.17f
                drawCircle(Brush.radialGradient(listOf(Color(0xFF7A7E86), Color(0xFF3A3D43)), Offset(stud.x - sr * 0.35f, stud.y - sr * 0.35f), sr * 1.3f), sr, stud)
                drawCircle(Color(0xFF25272B), sr, stud, style = Stroke(1.dp.toPx()))
            }
        }

        // The side pads: up beside the centre pad, then the full half below it, light bleeding round them
        for ((side, lit, offset) in listOf(Triple(0, leftLit, 0f), Triple(2, rightLit, 240f))) {
            val m = if (side == 0) { x: Float -> x } else { x: Float -> 1f - x }
            val zone = roundedPolygon(
                listOf(
                    Offset(w * m(0.06f), h * 0.3f), Offset(w * m(0.34f), h * 0.3f), Offset(w * m(0.34f), h * 0.56f),
                    Offset(w * m(0.47f), h * 0.56f), Offset(w * m(0.47f), h * 0.945f), Offset(w * m(0.06f), h * 0.945f),
                ),
                w * 0.02f,
            )
            val light = Color.hsv((hue + offset) % 360f, 0.55f, 1f)
            drawPath(zone, light.copy(alpha = 0.12f * lit), style = Stroke(10.dp.toPx()))
            drawPath(zone, light.copy(alpha = 0.2f * lit), style = Stroke(6.dp.toPx()))
            drawPath(
                zone,
                Brush.radialGradient(
                    listOf(lerp(frosted, light, 0.75f * lit), lerp(frostedEdge, light, 0.45f * lit)),
                    Offset(w * m(0.265f), h * 0.72f),
                    w * 0.32f,
                ),
            )
            drawPath(zone, Color.White.copy(alpha = 0.45f), style = Stroke(1.5.dp.toPx()))
            if (focus == side) drawPath(zone, accent.copy(alpha = pulse), style = Stroke(4.dp.toPx()))
        }

        // The centre pad, sunk in a dark ring, with its inner ring
        val c = Offset(w * 0.5f, h * 0.36f)
        val radius = w * 0.13f
        val light = Color.hsv((hue + 120f) % 360f, 0.55f, 1f)
        drawCircle(light.copy(alpha = 0.18f * centreLit), radius + 10.dp.toPx(), c)
        drawCircle(Color(0xFF18191C), radius + 5.dp.toPx(), c)
        drawCircle(Brush.radialGradient(listOf(lerp(frosted, light, 0.75f * centreLit), lerp(frostedEdge, light, 0.45f * centreLit)), c, radius), radius, c)
        drawCircle(Color.White.copy(alpha = 0.5f), radius, c, style = Stroke(1.5.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.45f), radius * 0.72f, c, style = Stroke(2.dp.toPx()))
        if (focus == 1) drawCircle(accent.copy(alpha = pulse), radius + 7.dp.toPx(), c, style = Stroke(4.dp.toPx()))

        // The red light at the front
        val front = Offset(w * 0.5f, h * 0.968f)
        drawCircle(Brush.radialGradient(listOf(Color(0x80E5483D), Color.Transparent), front, w * 0.06f), w * 0.06f, front)
        drawRoundRect(
            Color(0xFFE5483D),
            topLeft = Offset(w * 0.47f, h * 0.953f),
            size = Size(w * 0.06f, h * 0.03f),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )

        // The toys: on the centre pad, and side by side up each side pad
        val outline = Color.White.copy(alpha = 0.9f)
        toyFigure(Offset(w * 0.5f, h * 0.33f), h * 0.17f, toyTop, toyBottom, outline, light, square = true, grow = grows[1])
        for ((position, x) in listOf(0 to 0.1f, 3 to 0.2f, 4 to 0.3f, 2 to 0.9f, 5 to 0.8f, 6 to 0.7f)) {
            val zoneLight = Color.hsv((hue + if (x < 0.5f) 0f else 240f) % 360f, 0.55f, 1f)
            toyFigure(Offset(w * x, h * 0.535f), h * 0.15f, toyTop, toyBottom, outline, zoneLight, square = true, grow = grows[position])
        }
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
private fun FigureRow(
    name: String,
    action: String,
    controllerId: String,
    enabled: Boolean,
    caption: String? = null,
    onClick: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            caption?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = if (enabled) Modifier.controllerFocusable(controllerId, onConfirm = onClick) else Modifier,
        ) { Text(action) }
    }
}
