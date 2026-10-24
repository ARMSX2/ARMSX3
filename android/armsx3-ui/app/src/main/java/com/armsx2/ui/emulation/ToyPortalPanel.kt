package com.armsx2.ui.emulation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.armsx2.ui.settings.controllerFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The emulated toy portals, in the in-game menu: Skylanders, Disney Infinity and LEGO Dimensions, what
 * is on each, and the figure files to put there.
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

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Skylanders", "Disney Infinity", "LEGO Dimensions").forEachIndexed { index, name ->
                val pick = { tab = index }
                val label: @Composable () -> Unit = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val focus = Modifier.weight(1f).controllerFocusable("portal.tab.$index", onConfirm = pick)

                if (index == tab) {
                    Button(onClick = pick, modifier = focus) { label() }
                } else {
                    OutlinedButton(onClick = pick, modifier = focus) { label() }
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

/** Skylanders: the portal finds a place for each figure itself. */
@Composable
private fun SkylanderContent() {
    // Read when the tab opens, so files copied in meanwhile show up, and again after adding some
    var figures by remember { mutableStateOf(SkylanderPortal.figures()) }
    val folder = remember { SkylanderPortal.folder().absolutePath }
    var notes by remember { mutableStateOf(emptyList<Note>()) }
    var creating by remember { mutableStateOf(false) }
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
        CreatorContent(remember { SkylanderPortal.characters() }, { it.name }, onBack = { creating = false }) { character ->
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

    Header(str("portal.onPortal"))

    if (SkylanderPortal.placed.isEmpty()) {
        Text(str("portal.empty"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    for ((slot, name) in SkylanderPortal.placed.toSortedMap()) {
        FigureRow(name.substringBeforeLast('.'), str("portal.lift"), "portal.lift.$slot", enabled = true) {
            SkylanderPortal.lift(slot)
            notes = emptyList()
        }
    }

    FiguresHeader(str("portal.figures"), onCreate = { creating = true }, onAdd = addFigures)

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

/** Disney Infinity and LEGO Dimensions: pick a position, then the figure to put there. */
@Composable
private fun PositionContent(portal: PositionPortal) {
    var figures by remember(portal) { mutableStateOf(portal.figures()) }
    val folder = remember(portal) { portal.folder().absolutePath }
    var selected by remember(portal) { mutableIntStateOf(portal.positions.first().first) }
    var notes by remember(portal) { mutableStateOf(emptyList<Note>()) }
    var creating by remember(portal) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notFigureText = str("portal.notFigure")
    val emptyText = str("portal.emptyPosition")
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

    Header(str("portal.positions"))

    for ((position, _) in portal.positions) {
        val figure = portal.placed[position]
        val choose = { selected = position }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = choose,
                modifier = Modifier.weight(1f).controllerFocusable("portal.position.$position", onConfirm = choose),
            ) {
                Text(
                    "${if (position == selected) "▶ " else ""}${names[position]}: ${figure?.substringBeforeLast('.') ?: emptyText}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (position == selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (figure != null) {
                val lift = {
                    portal.lift(position)
                    notes = emptyList()
                }
                TextButton(onClick = lift, modifier = Modifier.controllerFocusable("portal.lift.$position", onConfirm = lift)) {
                    Text(str("portal.lift"))
                }
            }
        }
    }

    FiguresHeader(str("portal.figuresFor").format(names[selected].orEmpty()), onCreate = { creating = true }, onAdd = addFigures)

    for (file in figures) {
        val onPortal = file.name in portal.placed.values
        FigureRow(file.nameWithoutExtension, str(if (onPortal) "portal.onIt" else "portal.place"), "portal.place.${file.name}", enabled = !onPortal) {
            notes = if (portal.place(selected, file)) emptyList() else listOf(Note(notFigureText.format(file.name)))
        }
    }

    Footer(figures.isEmpty(), folder, notes)
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
private fun Header(text: String, top: Int = 0) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = top.dp),
    )
}

/** The figure list's header, with the two ways to get more: make one, or add files */
@Composable
private fun FiguresHeader(text: String, onCreate: () -> Unit, onAdd: () -> Unit) {
    Header(text, top = 8)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onCreate, modifier = Modifier.weight(1f).controllerFocusable("portal.create", onConfirm = onCreate)) {
            Text(str("portal.create"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.weight(1f).controllerFocusable("portal.add", onConfirm = onAdd)) {
            Text(str("portal.add"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** How many of RPCS3's figures the Create list shows at once; a search finds the rest */
private const val MAX_LISTED = 120

/**
 * RPCS3's list of what a figure can be made of, as its portal managers' Create buttons offer it, with
 * a search by name. Picking one makes a new figure file, which the list below then shows.
 */
@Composable
private fun CreatorContent(
    characters: List<ToyPortals.Character>,
    label: (ToyPortals.Character) -> String,
    onBack: () -> Unit,
    onPick: (ToyPortals.Character) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query, characters) {
        val q = query.trim()
        if (q.isEmpty()) characters else characters.filter { label(it).contains(q, ignoreCase = true) }
    }
    val shown = matches.take(MAX_LISTED)
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

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        label = { Text(str("portal.create.search")) },
        modifier = Modifier.fillMaxWidth(),
    )

    for (character in shown) {
        FigureRow(label(character), makeText, "portal.create.${character.first}.${character.second}", enabled = true) {
            onPick(character)
        }
    }

    when {
        matches.isEmpty() -> Text(
            str("portal.create.none"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        matches.size > shown.size -> Text(
            str("portal.create.more").format(shown.size, matches.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
