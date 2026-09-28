package com.armsx2.ui.saves

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.armsx2.i18n.str
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.ArmsLogo
import com.armsx2.ui.common.ArmsTopBar
import com.armsx2.ui.common.RoundAction
import com.armsx2.ui.common.SectionTitle
import com.armsx2.ui.settings.ControllerAutoScroll
import com.armsx2.ui.settings.controllerFocusable

/** [game] narrows the list to that game's slots, for the library's long-press; null lists every game's. */
@Composable
fun SaveManagerScreen(
    onBack: () -> Unit,
    game: com.armsx2.GameInfo? = null,
    viewModel: SaveManagerViewModel = viewModel(),
) {
    val state = viewModel.state.value
    var confirmWipe by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(game) { viewModel.open(game) }
    // Import an external save-state file (AetherSX2 / NetherSX2 / another install's .p2s) into the
    // active game's next free slot. OpenDocument with "*/*" because save states have no single MIME
    // and formats vary; the native loader detects the format by content.
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importState) }

    ArmsBackdrop {
        // A scrolling Column, not a LazyColumn: each card's buttons are in the controller nav
        // registry only while the card is composed, and a LazyColumn composes only what is on
        // screen, so the D-pad stopped at the last visible save (the same dead end as All Core
        // Settings). The thumbnails are decoded up front by the ViewModel either way.
        val scroll = rememberScrollState()
        ControllerAutoScroll(scroll)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ArmsTopBar(
                title = str("savestate.title.loadManage"),
                leading = { RoundAction("←", str("action.back"), onBack) },
                actions = {
                    RoundAction("⤓", str("savestate.import"), onClick = { importLauncher.launch(arrayOf("*/*")) })
                    if (state.saves.isNotEmpty()) {
                        RoundAction("▣", str("savestate.backup"), viewModel::backupAll)
                    }
                    // Deliberately NOT gated on the list being non-empty. The whole reason this
                    // exists is a folder full of files the manager cannot list, where the list
                    // reads as empty and every per-entry delete is therefore unreachable.
                    RoundAction("\uD83D\uDDD1", str("savestate.wipe"), onClick = { confirmWipe = true })
                    RoundAction("↻", str("games.card.refresh"), viewModel::refresh)
                },
                horizontalPadding = 0.dp,
            )

            state.gameTitle?.let { title ->
                SectionTitle(title, state.saves.size.toString())
            }

            if (state.loading && state.saves.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.saves.isEmpty()) {
                SaveManagerEmptyState()
            } else {
                state.saves.forEach { save ->
                    key(save.file.absolutePath) {
                        SaveStateCard(
                            save = save,
                            onSave = { save.slot?.let(viewModel::save) },
                            onLoad = { viewModel.load(save) },
                            onDelete = { viewModel.delete(save) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    // Top level, NOT inside the message block below. Nested in it, this dialog could only be
    // composed while a message was already on screen: the trash can set confirmWipe and nothing
    // appeared, and the flag then stayed set until the next delete produced a message, at which
    // point both dialogs rendered on top of each other.
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(str("savestate.wipe.confirm.title")) },
            text = { Text(str("savestate.wipe.confirm.body")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    viewModel.wipeAll()
                }) { Text(str("savestate.wipe")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text(str("action.cancel")) }
            },
        )
    }

    (state.error ?: state.message)?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text(str("savestate.title.loadManage")) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) { Text(str("action.ok")) }
            },
        )
    }
}

@Composable
private fun SaveManagerEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(
                    modifier = Modifier.width(48.dp).height(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "▣",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = str("savestate.empty.title"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = str("savestate.empty.description"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SaveStateCard(
    save: SaveStateItem,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.44f)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SavePreview(save)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = save.gameTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    // Slot 10 is the autosave (Rpcs3Bridge.AUTOSAVE_SLOT), which read as "Slot 11".
                    val slotLabel = save.slot?.let {
                        if (it == com.armsx3.Rpcs3Bridge.AUTOSAVE_SLOT) str("savestate.autosave.title")
                        else "${str("memcard.slot1").substringBefore(' ')} ${it + 1}"
                    } ?: save.serial
                    Text(
                        text = slotLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = formatTimestamp(androidx.compose.ui.platform.LocalContext.current, save.file.lastModified()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // Saving needs the game running to take a snapshot of. Loading does not: with the
                // game stopped it boots straight into the state (MainActivityRuntime.launchGameFromState).
                if (save.canUseWithActiveGame && save.slot != null) {
                    OutlinedButton(
                        onClick = onSave,
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.controllerFocusable("saveMgr.save.${save.file.absolutePath}", onConfirm = onSave),
                    ) {
                        Text(str("action.save"))
                    }
                    Spacer(Modifier.width(7.dp))
                }
                if (save.slot != null) {
                    OutlinedButton(
                        onClick = onLoad,
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.controllerFocusable("saveMgr.load.${save.file.absolutePath}", onConfirm = onLoad),
                    ) {
                        Text(str("touch.stateAction.load"))
                    }
                    Spacer(Modifier.width(3.dp))
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.controllerFocusable("saveMgr.delete.${save.file.absolutePath}", onConfirm = onDelete),
                ) { Text(str("action.delete")) }
            }
        }
    }
}

@Composable
private fun SavePreview(save: SaveStateItem) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .width(132.dp)
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val preview = save.preview
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = save.gameTitle,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            ArmsLogo(showWordmark = false)
        }
    }
}

// The same form as the in-game picker's tiles, so a save reads alike in both places.
private fun formatTimestamp(context: android.content.Context, value: Long): String =
    android.text.format.DateUtils.formatDateTime(
        context,
        value,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or
            android.text.format.DateUtils.FORMAT_SHOW_TIME or
            android.text.format.DateUtils.FORMAT_ABBREV_ALL,
    )
