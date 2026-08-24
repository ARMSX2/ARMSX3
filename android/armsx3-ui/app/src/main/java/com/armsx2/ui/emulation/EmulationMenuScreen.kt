package com.armsx2.ui.emulation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.InGameOverlay
import com.armsx2.ui.achievements.AchievementItem
import com.armsx2.ui.common.GameCoverArt
import com.armsx2.ui.settings.controllerFocusable
import com.armsx2.ui.touch.TouchControls
import com.armsx2.ui.theme.Danger
import com.armsx2.ui.common.StatusChip
import com.armsx2.ui.theme.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Screen-aspect presets in permille, index-aligned with the in-game picker. Mirrors
 *  SCREEN_ASPECTS in RendererTab minus the Custom entry (no slider in the quick menu). */
private val IN_GAME_SCREEN_ASPECTS = listOf(0, 1333, 1600, 1778, 2000, 2167, 2222, 2333)

@Composable
fun EmulationMenuScreen(viewModel: EmulationMenuViewModel = viewModel()) {
    val state = viewModel.state.value
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    var friendsOpen by remember { mutableStateOf(false) }
    val closeMenu: () -> Unit = remember(viewModel, scope) {
        {
            if (!dismissing) {
                dismissing = true
                shown = false
                // ★ Dispatchers.Main, NOT the composition's own dispatcher. rememberCoroutineScope
                // inherits the composition context, which on Android is AndroidUiDispatcher — it
                // dispatches continuations on CHOREOGRAPHER FRAME CALLBACKS. We have just set
                // shown = false, so once the exit animation settles Compose has nothing left to
                // invalidate, no frame is scheduled, and the continuation after this delay is
                // never dispatched: the VM is simply never told to resume. The game sits paused
                // with the OSD reading "FPS: N/A" until something incidentally causes a frame —
                // which is exactly why tapping the on-screen controls "speeds up" the recovery
                // (touch input schedules a frame) and why waiting also eventually works.
                // Dispatchers.Main is a plain main-looper Handler dispatcher with no frame
                // dependency, so the resume fires on time whether or not anything is drawing.
                scope.launch(Dispatchers.Main) {
                    delay(220)
                    viewModel.dismissHandler = null
                    viewModel.resumeImmediately()
                }
            }
        }
    }

    DisposableEffect(viewModel, closeMenu) {
        viewModel.dismissHandler = closeMenu
        EmulationMenuInputController.bind(viewModel)
        onDispose {
            viewModel.dismissHandler = null
            EmulationMenuInputController.unbind(viewModel)
        }
    }
    LaunchedEffect(Unit) { shown = true }

    // Hand pad input to the Friends panel while it is open, and give it back on close.
    //
    // The nav registry is shared between the menu and the panel, so ownership has to be explicit:
    // the selection is cleared on both edges, because a selection left pointing at a control on
    // the other side of the transition highlights something the user cannot see.
    DisposableEffect(friendsOpen) {
        if (friendsOpen) {
            EmulationMenuInputController.overlayDismiss = { friendsOpen = false }
            com.armsx2.ui.settings.SettingsControllerNav.clearSelection()
        }
        onDispose {
            EmulationMenuInputController.overlayDismiss = null
            com.armsx2.ui.settings.SettingsControllerNav.clearSelection()
        }
    }
    // Highlight the panel's first control once it has actually composed. Selecting in the same
    // frame the panel opens would find an empty registry — controllerFocusable only registers
    // items that exist, and the panel's do not until AnimatedVisibility has run.
    LaunchedEffect(friendsOpen) {
        if (friendsOpen) {
            delay(260)
            if (friendsOpen) com.armsx2.ui.settings.SettingsControllerNav.move(1)
        }
    }
    // Back closes the friends overlay first when it is up. Without this, opening Friends and
    // pressing Back would dismiss the entire pause menu and resume the game, which is not what
    // anyone means by "go back" from a panel sitting on top of another panel.
    BackHandler(onBack = { if (friendsOpen) friendsOpen = false else closeMenu() })

    state.pendingHardcore?.let { enabling ->
        androidx.compose.runtime.DisposableEffect(Unit) {
            com.armsx2.MenuSfx.play(com.armsx2.MenuSfx.Event.POPUP_OPEN)
            onDispose { com.armsx2.MenuSfx.play(com.armsx2.MenuSfx.Event.POPUP_CLOSE) }
        }
        AlertDialog(
            onDismissRequest = viewModel::cancelToggleHardcore,
            title = { Text(str(if (enabling) "ra.hardcore.enable.title" else "ra.hardcore.disable.title")) },
            text = { Text(str(if (enabling) "ra.hardcore.enable.body" else "ra.hardcore.disable.body")) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmToggleHardcore) {
                    Text(str(if (enabling) "ra.hardcore.enable.confirm" else "ra.hardcore.disable.confirm"))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelToggleHardcore) { Text(str("action.cancel")) } },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 700.dp
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(190, easing = EaseOut)),
            exit = fadeOut(tween(190, easing = EaseIn)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .clickable(onClick = closeMenu),
            )
        }
        AnimatedVisibility(
            visible = shown,
            enter = slideInHorizontally(tween(320, easing = EaseOut)) { it },
            exit = slideOutHorizontally(tween(220, easing = EaseIn)) { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            if (compact) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.96f)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
                    shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    shadowElevation = 22.dp,
                ) {
                    MenuPage(
                        state = state,
                        viewModel = viewModel,
                        compact = true,
                        modifier = Modifier.fillMaxSize(),
                        onOpenFriends = { friendsOpen = true },
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.64f)
                        .widthIn(max = 900.dp)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                        .padding(top = 14.dp, end = 12.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        shadowElevation = 22.dp,
                    ) {
                        MenuPage(
                            state = state,
                            viewModel = viewModel,
                            compact = false,
                            modifier = Modifier.fillMaxSize(),
                            onOpenFriends = { friendsOpen = true },
                        )
                    }
                    Surface(
                        modifier = Modifier.width(76.dp).fillMaxHeight(),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        shadowElevation = 18.dp,
                    ) {
                        MenuRail(state.tab, viewModel::selectTab)
                    }
                }
            }
        }

        // Friends, as its own panel over the menu.
        //
        // Composed here rather than as an AlertDialog on purpose: a Dialog gets its own focused
        // window, and a focused window swallows gamepad keys before our input plumbing ever sees
        // them — the pause menu would stop responding to the pad the moment this opened.
        AnimatedVisibility(
            visible = friendsOpen,
            enter = fadeIn(tween(160, easing = EaseOut)),
            exit = fadeOut(tween(140, easing = EaseIn)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .clickable { friendsOpen = false },
            )
        }
        AnimatedVisibility(
            visible = friendsOpen,
            enter = fadeIn(tween(190, easing = EaseOut)),
            exit = fadeOut(tween(150, easing = EaseIn)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(if (compact) 0.94f else 0.6f)
                    .widthIn(max = 620.dp)
                    .fillMaxHeight(0.9f)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                shadowElevation = 24.dp,
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            str("friends.title"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        // Between the title and Close: whose Discord this is.
                        com.armsx2.ui.friends.SelfChip(
                            Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        TextButton(
                            onClick = { friendsOpen = false },
                            modifier = Modifier.controllerFocusable(
                                "menu.friends.close",
                                onConfirm = { friendsOpen = false },
                            ),
                        ) { Text(str("action.close")) }
                    }
                    com.armsx2.ui.friends.FriendsPanel(Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun MenuPage(
    state: EmulationMenuUiState,
    viewModel: EmulationMenuViewModel,
    compact: Boolean,
    modifier: Modifier,
    onOpenFriends: () -> Unit,
) {
    val tabScrollStates = remember {
        EmulationMenuTab.visible.associateWith {
            ScrollState(initial = InGameOverlay.menuTabScroll[it.name] ?: 0)
        }
    }
    // Remember each tab's scroll offset when the menu closes so reopening a tab (especially
    // the long Fixes list) returns to where you were instead of snapping back to the top.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            tabScrollStates.forEach { (tab, ss) -> InGameOverlay.menuTabScroll[tab.name] = ss.value }
        }
    }
    val scrollState = tabScrollStates.getValue(state.tab)
    // Provide the pane's scroll state to the settings widgets so the Fixes pane's
    // right-stick free-scroll (settingsScrollState / ControllerAutoScroll) drives the
    // pane the user is actually looking at. Per-control bring-into-view handles the
    // primary "keep selection on screen" via the nearest scrollable ancestor already.
    androidx.compose.runtime.CompositionLocalProvider(
        com.armsx2.ui.settings.LocalSettingsScrollState provides scrollState,
    ) {
        Column(
            modifier
                .verticalScroll(scrollState)
                .padding(bottom = 18.dp),
        ) {
            if (compact) CompactMenuTabs(state.tab, viewModel::selectTab)
            MenuHeader(compact, state.hardcore, state.richPresence, state.gameCRC, onOpenFriends)
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.34f),
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (state.tab) {
                    EmulationMenuTab.Session -> SessionPane(state, viewModel)
                    EmulationMenuTab.Graphics -> GraphicsPane(state, viewModel)
                    // The full Fixes settings tab, live-applying via InGameOverlay's
                    // shared Settings state (same as the rest of the overlay); its
                    // controls are SettingsControllerNav items, so the pause menu's
                    // content-pane nav drives them for free.
                    EmulationMenuTab.Fixes -> com.armsx2.ui.settings.FixesTab(InGameOverlay.settingsState)
                    EmulationMenuTab.Performance -> PerformancePane(state, viewModel)
                    EmulationMenuTab.Controls -> ControlsPane(state, viewModel)
                    EmulationMenuTab.Options -> OptionsPane(state, viewModel)
                    EmulationMenuTab.Achievements -> AchievementsPane(state, viewModel)
                    EmulationMenuTab.Trophies -> TrophiesPane(viewModel)
                }
            }
        }
    }
}

@Composable
private fun CompactMenuTabs(selected: EmulationMenuTab, onSelect: (EmulationMenuTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EmulationMenuTab.visible.forEach { tab ->
            MenuTab(tab, tab == selected, onSelect)
        }
    }
}

@Composable
private fun MenuRail(
    selected: EmulationMenuTab,
    onSelect: (EmulationMenuTab) -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Centred, not top-aligned: the rail fills the full height, so with the tabs pinned
        // to the top the column left a block of dead space at the bottom once the duplicate
        // All Settings shortcut was removed from under them. Centring keeps the group
        // balanced regardless of how many tabs there are, and still scrolls if it ever
        // outgrows the rail.
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        EmulationMenuTab.visible.forEach { tab ->
            MenuRailTab(tab, tab == selected, onSelect)
        }
    }
}

@Composable
private fun MenuRailTab(tab: EmulationMenuTab, active: Boolean, onSelect: (EmulationMenuTab) -> Unit) {
    val bring = remember { BringIntoViewRequester() }
    val label = str(tab.titleKey)
    LaunchedEffect(active) { if (active) runCatching { bring.bringIntoView() } }
    Surface(
        onClick = { onSelect(tab) },
        modifier = Modifier.size(56.dp).bringIntoViewRequester(bring).semantics { contentDescription = label },
        shape = RoundedCornerShape(18.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        border = BorderStroke(
            if (active) 2.dp else 1.dp,
            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.34f),
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = tabGlyph(tab),
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MenuTab(tab: EmulationMenuTab, active: Boolean, onSelect: (EmulationMenuTab) -> Unit) {
    // Keep the active tab scrolled into view so controller nav reaches tabs that fall off
    // the rail on short screens — e.g. the 7th "Achievements" (RA) tab on a Retroid Pocket
    // in landscape. Mirrors the settings-hub / library camera-follow. Resolves against the
    // nearest scrollable ancestor, so it works for both the vertical rail and the compact
    // horizontal strip.
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(active) { if (active) runCatching { bring.bringIntoView() } }
    Surface(
        onClick = { onSelect(tab) },
        modifier = Modifier
            .widthIn(min = 132.dp, max = 210.dp)
            .padding(vertical = 3.dp)
            .bringIntoViewRequester(bring),
        shape = RoundedCornerShape(14.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
        border = BorderStroke(
            if (active) 1.5.dp else 1.dp,
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.32f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = RoundedCornerShape(9.dp),
                color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = tabGlyph(tab),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = str(tab.titleKey),
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Rail tab icons. No monochrome Unicode exists for gamepad/wrench/trophy/display, so those
// use color emoji (the bundled NotoColorEmoji renders them); Session keeps its clean text
// glyph. Performance uses the high-voltage emoji so it reads as a yellow lightning bolt.
// Options carries the settings gear; the full-settings shortcut below the rail divider uses
// a distinct "open" glyph so there aren't two gears.
private fun tabGlyph(tab: EmulationMenuTab): String = when (tab) {
    EmulationMenuTab.Session -> "☰"
    EmulationMenuTab.Graphics -> "🖥️"
    EmulationMenuTab.Fixes -> "🔧"
    EmulationMenuTab.Performance -> "⚡"
    EmulationMenuTab.Controls -> "🎮"
    EmulationMenuTab.Options -> "⚙"
    EmulationMenuTab.Achievements -> "🏆"
    // The trophy cup, same glyph the library drawer's Trophies row uses. It does not collide
    // with the RA tab above because that one is filtered out of the rail on ARMSX3.
    EmulationMenuTab.Trophies -> "🏆"
}

@Composable
private fun MenuHeader(
    compact: Boolean,
    hardcore: Boolean,
    richPresence: String,
    gameCRC: String,
    onOpenFriends: () -> Unit,
) {
    val game = MainActivityRuntime.currentGame.value
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (game != null) {
            GameCoverArt(game, Modifier.width(if (compact) 38.dp else 44.dp).height(if (compact) 52.dp else 60.dp))
            Spacer(Modifier.width(11.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    game?.title ?: "PlayStation 3",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hardcore) {
                    Spacer(Modifier.width(8.dp))
                    HardcoreBadge()
                }
                // File-type chip after the HC badge (ISO / CHD / …), mirroring the library
                // list view so the pause/RA header shows the same at-a-glance file info.
                game?.let { g ->
                    Spacer(Modifier.width(6.dp))
                    com.armsx2.ui.common.StatusChip(g.extension.ifBlank { g.platform.key.uppercase() })
                }
            }
            // Serial and CRC together: a PNACH is named <SERIAL>_<CRC>.pnach, so the two values
            // needed to name one should not live on separate screens.
            //
            // The live VM CRC is preferred but cannot be relied on: for ISO boots the core hands
            // ELFLoadingOnCPUThread an empty path, so UpdateELFInfo takes its failure branch and
            // leaves s_current_crc at 0 — the emulog shows the loader computing the real CRC and
            // the VM then reporting 00000000. When that happens, identify the image instead, which
            // is the same path the Info tab and the library's long-press sheet already take.
            val resolvedCRC by androidx.compose.runtime.produceState(gameCRC, gameCRC, game?.uri) {
                value = gameCRC.ifBlank {
                    game?.uri?.let { com.armsx2.DiscIdentity.resolve(it, game.serial) }.orEmpty()
                }
            }
            val identity = buildList {
                game?.serial?.takeIf { it.isNotBlank() }?.let(::add)
                resolvedCRC.takeIf { it.isNotBlank() }?.let { add("CRC $it") }
            }.joinToString("  ·  ")
            if (identity.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    identity,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // RetroAchievements rich presence — the live "what you're doing" line
            // (e.g. "Pooh & Piglet are in a Scaring Contest"). Restored from the old UI.
            if (richPresence.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    richPresence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Clock + battery, same cluster as the library toolbar. Worth having here specifically:
        // this menu is what you open mid-session on a handheld, so it's exactly when you want to
        // know the time and how much charge is left. Not controllerFocusable — it's a readout.
        Spacer(Modifier.width(8.dp))
        com.armsx2.ui.common.LibraryStatusCluster(
            Modifier.align(Alignment.CenterVertically),
        )

        // Friends, in the header where it is always visible, with the online count on it. A build
        // without the SDK has nothing to show, so it does not take up header space there.
        if (com.armsx2.DiscordPresence.available()) {
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = onOpenFriends,
                modifier = Modifier.controllerFocusable(
                    "menu.friends",
                    RoundedCornerShape(14.dp),
                    onConfirm = onOpenFriends,
                ),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            ) {
                Box(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
                    com.armsx2.ui.friends.FriendsGlyphWithBadge(
                        color = MaterialTheme.colorScheme.onSurface,
                        glyphSize = 19.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionPane(state: EmulationMenuUiState, viewModel: EmulationMenuViewModel) {
    ActionGrid(
        actions = listOf(
            MenuAction(str("action.resume"), str("action.play"), "▶", Success, viewModel::resume),
            MenuAction(str("memcard.restart"), str("action.reset"), "↻", null, MainActivityRuntime::restart),
            MenuAction(str("action.swapDisc"), str("action.swapDisc.detail"), "⏏", null, MainActivityRuntime::promptSwapDisc),
            MenuAction(str("action.close"), MainActivityRuntime.currentGame.value?.title.orEmpty(), "■", Danger) {
                MainActivityRuntime.closeGame()
            },
        ),
        selected = state.selectedAction,
        onSelect = viewModel::selectAction,
    )
    // On-screen display — a single universal on/off (old-UI style); the per-stat
    // toggles live in All Settings. Plus a frame-limit switch so fast-forward is one
    // tap away.
    SectionCard(str("tab.overlay")) {
        // #357: the pause button replaced the settings cog, so it's front-and-centre here. This is
        // "tap to reveal", NOT show/hide: on = the glyph stays hidden until you tap its top-right
        // corner, which surfaces it. Either way that corner always opens this menu, so unlike the
        // old on/off toggle there's no setting here that can lock you out of it.
        //
        // The comment above outlived its control: the row was lost somewhere in the port and the
        // setting was left with no writer at all, so the glyph could not be hidden or brought back
        // by anyone. Reported as the option missing from this menu, which is exactly what it was.
        MenuSwitchRow(
            str("overlay.pauseTapReveal.label"),
            com.armsx2.ui.touch.TouchControls.pauseTapToReveal.value,
            description = str("overlay.pauseTapReveal.desc"),
        ) { v ->
            com.armsx2.ui.touch.TouchControls.setPauseTapToReveal(v)
        }
    // Removed: PS2 per-primitive filtering; use Anisotropic Filtering.
        Spacer(Modifier.height(6.dp))
        // OSD mode selector — one control (Full / Minimal / Custom / Off) in place of the old
        // master + simple toggles, cycled here and by the "Cycle Perf Stats (OSD)" hotkey. Custom
        // = the detailed per-stat selection from All Settings > On-Screen.
        val osdModes = com.armsx2.ui.InGameOverlay.OsdMode.entries
        val osdModeIndex = osdModes.indexOf(com.armsx2.ui.InGameOverlay.osdMode.value).coerceAtLeast(0)
        MenuCycleRow(
            title = str("overlay.master.label"),
            valueLabel = com.armsx2.ui.InGameOverlay.osdModeLabel(osdModes[osdModeIndex]),
        ) { step ->
            val size = osdModes.size
            val next = ((osdModeIndex + step) % size + size) % size
            com.armsx2.ui.InGameOverlay.setOsdMode(osdModes[next])
        }
        Spacer(Modifier.height(6.dp))
    // Removed: PS2 GS download mode.
        Spacer(Modifier.height(6.dp))
        // Fast-forward SPEED — how fast the FF hotkey/button runs: 2..10x, or Unlimited (the
        // default, uncapped) at the top. Global pref; re-applied live if FF is currently engaged.
        var ffSpeed by remember { mutableStateOf(MainActivityRuntime.fastForwardSpeed()) }
        val ffUnlimitedLabel = str("common.unlimited") // hoisted: str() is @Composable, can't run in the formatter lambda
        com.armsx2.ui.settings.IntSliderRow(
            label = str("perf.ffSpeed.label"),
            value = ffSpeed,
            min = 2,
            max = MainActivityRuntime.FF_SPEED_UNLIMITED,
            valueFormatter = { if (it >= MainActivityRuntime.FF_SPEED_UNLIMITED) ffUnlimitedLabel else "${it}×" },
            onChange = { v ->
                ffSpeed = v
                MainActivityRuntime.setFastForwardSpeed(v)
                if (MainActivityRuntime.fastForwardToggleActive)
                    runCatching { com.armsx3.NativeApp.speedhackLimitermode(MainActivityRuntime.ffLimiterMode()) }
            },
        )
        Spacer(Modifier.height(6.dp))
        // OSD colour, cycled in place. Shares the palette with the All Settings picker rather
        // than carrying its own copy. Safe to add here: this card's rows are plain switches with
        // their own callbacks — SessionPane's selectedAction indexes the action GRID above, not
        // these, so inserting a row can't shift the controller dispatch.
        //
        // Writes RPCS3's overlay body colour. It used to write `osdColor`, i.e. PCSX2's
        // EmuCore/GS/OsdColor plus a stubbed NativeApp.osdSetColor(), so cycling this row in a
        // game changed nothing whatsoever — the most visible place for a control that did not work.
        val osdColorIndex = com.armsx2.ui.settings.osdPresetIndex(state.settings.ps3.overlayBodyColor)
        MenuCycleRow(
            title = str("overlay.osdColor.label"),
            // A colour set with the RGBA sliders is on no preset; say so rather than naming
            // whichever preset happens to sit at index 0.
            valueLabel = if (osdColorIndex >= 0)
                str(com.armsx2.ui.settings.OSD_COLOR_LABEL_KEYS[osdColorIndex])
            else str("overlay.osdColor.custom"),
        ) { step ->
            val size = com.armsx2.ui.settings.OSD_COLORS.size
            val next = ((osdColorIndex + step) % size + size) % size
            viewModel.updateSettings {
                it.copy(ps3 = it.ps3.copy(overlayBodyColor = com.armsx2.ui.settings.OSD_COLORS[next]))
            }
        }
        Spacer(Modifier.height(6.dp))
        // Where the overlay sits. Only the All Settings tab had this, which made it unreachable
        // at the one moment it matters -- when the stats are sitting on top of something in the
        // game you are trying to look at.
        val osdPositionLabels = listOf(
            "overlay.position.topLeft", "overlay.position.topRight",
            "overlay.position.bottomLeft", "overlay.position.bottomRight",
        )
        val osdPositionIndex = state.settings.ps3.overlayPosition.coerceIn(0, 3)
        MenuCycleRow(
            title = str("overlay.position.label"),
            valueLabel = str(osdPositionLabels[osdPositionIndex]),
        ) { step ->
            val size = osdPositionLabels.size
            val next = ((osdPositionIndex + step) % size + size) % size
            viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(overlayPosition = next)) }
        }
    }
    SectionCard(str("savestate.title.loadManage")) {
        Text(
            "${str("memcard.slot1").substringBefore(' ')} ${state.saveSlot + 1}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .bleedHorizontal(13.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(10) { slot ->
                OptionChip(
                    label = "${slot + 1}",
                    selected = slot == state.saveSlot,
                    controllerId = "pause.saveslot.$slot",
                    onClick = { viewModel.setSaveSlot(slot) },
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        // Save / Load open the rich slot picker (thumbnails + autosave + the
        // auto-save/-load toggles), matching the old UI. The slot chips above stay
        // the quick-slot selector used by the on-screen / hotkey quick-save.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactAction(str("savestate.title.save"), "↥", Modifier.weight(1f)) {
                com.armsx2.ui.WindowImpl.openInGameScreen(com.armsx2.ui.InGameScreen.SaveState)
            }
            CompactAction(str("touch.stateAction.load"), "↧", Modifier.weight(1f)) {
                com.armsx2.ui.WindowImpl.openInGameScreen(com.armsx2.ui.InGameScreen.LoadState)
            }
        }
    }
}

@Composable
private fun GraphicsPane(state: EmulationMenuUiState, viewModel: EmulationMenuViewModel) {
    val settings = state.settings
    HorizontalOptions(
        title = str("tab.renderer"),
        // No "auto" or "software": RPCS3 has no PS3 software rasteriser, and
        // auto resolved to Vulkan anyway.
        options = listOf(
            "vulkan" to "Vulkan",
            "opengl" to "OpenGL",
        ),
        selected = settings.renderer,
        onSelect = viewModel::setRenderer,
    )
    // GPU driver manager (download/import/select) — Vulkan only — plus Apply &
    // Restart, since renderer + driver changes only take effect on renderer init.
    // For OpenGL the "custom driver" is ANGLE (GLES-on-Vulkan), same picker shape.
    if (settings.renderer == "vulkan") {
        com.armsx2.ui.common.DriverManagerSection()
    } else if (settings.renderer == "opengl") {
        com.armsx2.ui.common.AngleDriverSection(settings.useAngleOpenGL) { on ->
            viewModel.updateSettings { it.copy(useAngleOpenGL = on) }
        }
    }
    // GS Multi-threading (GV7 front/back split). Restart-required like the renderer /
    // driver above, so it lives in the same group — hit Apply & Restart below to apply.
    // Off = single-threaded; On = GS on a dedicated back thread (Pipelined, enum 3).
    // The Inline/Lockstep dev rungs are not exposed. Description shown inline so users
    // who never open full settings still understand what it does.
    // Removed: GS back thread is PS2. RPCS3's analogue is Multithreaded RSX.
    // Every phone GPU is a tiler, so this belongs in the in-game menu next to the other
    // renderer levers, not just in full settings — it is the kind of thing you toggle while
    // looking at the framerate.
    // Removed: PS2 GS dithering.
    CompactAction(str("backend.applyRestart"), "↻", Modifier.fillMaxWidth(), MainActivityRuntime::restart)
    HorizontalOptions(
        title = str("renderer.upscale.label"),
        // Share the full settings-tab list so the sub-native 0.25/0.5/0.75/Native
        // options aren't dropped in the in-game quick menu.
        options = com.armsx2.ui.settings.UPSCALE_OPTIONS.map { it.value to it.label },
        selected = settings.upscaleFloat,
        onSelect = viewModel::setUpscale,
    )
    // Custom internal resolution, same control as the settings tab — the quick menu only offered
    // the preset steps, so a value between them (or set per-game) could be neither seen nor
    // changed from in-game. Percentage of native: 107% is roughly true 480p height.
    com.armsx2.ui.settings.IntSliderRow(
        label = str("renderer.upscale.customScale"),
        value = (settings.upscaleFloat * 100f).roundToInt().coerceIn(25, 800),
        min = 25,
        max = 800,
        description = str("renderer.upscale.customScale.description"),
        valueFormatter = { "$it%" },
        onReset = { viewModel.setUpscale(1.0f) },
        onChange = { pct -> viewModel.setUpscale(pct / 100f) },
    )
        // Screen fit, matching Renderer settings. The old list was PCSX2 aspect
        // ratios (Stretch/Auto/4:3/16:9/10:7/21:9/...), most of which RPCS3's
        // video_aspect rejects outright.
        HorizontalOptions(
            title = str("renderer.displayMode.label"),
            // Fit / Stretch only -- see RendererTab: Integer and Fill had no
            // RPCS3 setting behind them and behaved identically to Fit.
            options = listOf(
                str("renderer.fit.auto"), str("renderer.fit.stretch"),
            ).mapIndexed { index, label -> index to label },
            selected = if (settings.displayFitMode == 1) 1 else 0,
            onSelect = { v -> viewModel.updateSettings { it.copy(displayFitMode = v) } },
        )
        Spacer(Modifier.height(6.dp))
        // Screen aspect, same list as the Renderer tab. In-game is where you actually
        // want this -- you are looking at the picture while you change it. Custom is
        // omitted here on purpose: a slider belongs on the settings screen, and the
        // presets are what a handheld user needs. A custom value set in Settings shows
        // as no selection here and is left alone unless a preset is picked.
        HorizontalOptions(
            title = str("renderer.screenAspect.label"),
            options = listOf(
                str("common.auto"), "4:3", "16:10", "16:9", "18:9", "19.5:9", "20:9", "21:9",
            ).mapIndexed { index, label -> index to label },
            selected = IN_GAME_SCREEN_ASPECTS.indexOf(settings.ps3.displayAspect),
            onSelect = { v ->
                viewModel.updateSettings {
                    it.copy(ps3 = it.ps3.copy(displayAspect = IN_GAME_SCREEN_ASPECTS[v]))
                }
            },
        )
        Spacer(Modifier.height(6.dp))
        // RSX accuracy -- the levers that actually matter on this core, and the
        // reason the PS2 GS rows above had to go rather than just be hidden.
        HorizontalOptions(
            title = str("renderer.shaderMode.label"),
            options = listOf(
                str("renderer.shaderMode.legacy"), str("renderer.shaderMode.async"),
                str("renderer.shaderMode.asyncInterp"), str("renderer.shaderMode.interpOnly"),
            ).mapIndexed { index, label -> index to label },
            selected = settings.ps3.shaderMode,
            onSelect = { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(shaderMode = v)) } },
        )
        Spacer(Modifier.height(6.dp))
        HorizontalOptions(
            title = str("renderer.outputScaling.label"),
            options = listOf(
                str("renderer.outputScaling.nearest"), str("renderer.outputScaling.bilinear"),
                str("renderer.outputScaling.fsr"), str("renderer.outputScaling.sgsr"),
                str("renderer.outputScaling.sgsrEdge"),
            ).mapIndexed { index, label -> index to label },
            selected = settings.casMode,
            onSelect = { v -> viewModel.updateSettings { it.copy(casMode = v) } },
        )
        // Sharpening, shown only for the two upscalers that have any. It was missing from this
        // menu entirely, which is the one people reach mid-game -- changing upscaler here and
        // then having to leave the game to tune it defeats the point of the quick menu.
        //
        // The label follows the selection because the number does not mean the same thing to
        // both: it is an RCAS stop to FSR and an edge factor to SGSR, and a slider named for the
        // upscaler that is not running is simply wrong.
        if (settings.casMode >= 2) {
            Spacer(Modifier.height(6.dp))
            com.armsx2.ui.settings.IntSliderRow(
                label = str(if (settings.casMode >= 3) "renderer.cas.sharpness.sgsr" else "renderer.cas.sharpness.fsr"),
                value = settings.casSharpness.coerceIn(0, 100),
                min = 0,
                max = 100,
                valueFormatter = { "$it%" },
                onChange = { v -> viewModel.updateSettings { it.copy(casSharpness = v) } },
            )
        }
        Spacer(Modifier.height(6.dp))
        MenuSwitchRow(str("renderer.relaxedZcull.label"), settings.ps3.relaxedZcull) { v ->
            viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(relaxedZcull = v)) }
        }
        Spacer(Modifier.height(6.dp))
        MenuSwitchRow(str("renderer.readColorBuffers.label"), settings.ps3.readColorBuffers) { v ->
            viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(readColorBuffers = v)) }
        }
        Spacer(Modifier.height(6.dp))
        // GPU Turbo in-game: the whole point is A/B-ing it against a scene that is actually
        // stuttering, which is impossible if you have to quit to Settings to flip it. Applied
        // live, not just at the next renderer start.
        MenuSwitchRow(str("renderer.gpuTurbo.label"), settings.ps3.gpuTurbo) { v ->
            viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(gpuTurbo = v)) }
            runCatching { net.rpcsx.RPCSX.instance.setGpuTurbo(v) }
        }
    // Overlay artwork, switchable from in-game — trying bezels means seeing them ON the game, and
    // having to leave for All Settings each time made that unusable. Import still lives in the
    // settings tab (it opens a file picker); this is the picker for what is already imported.
    run {
        val overlayCtx = androidx.compose.ui.platform.LocalContext.current
        val entries = remember { com.armsx2.OverlayRepo.list(overlayCtx) }
        // Shown even with nothing imported. Hiding it when the list was empty is why this looked
        // absent from the in-game menu entirely — with no overlays there was no row to find, and
        // no hint that the feature existed or where to add one.
        HorizontalOptions(
            title = str("renderer.overlayArt.label"),
            options = listOf("" to str("renderer.overlayArt.none")) +
                entries.map { it.imagePath to it.name },
            selected = com.armsx2.OverlayRepo.activePath.value,
            onSelect = { com.armsx2.OverlayRepo.setActive(it) },
        )
        if (entries.isEmpty()) {
            Text(
                str("renderer.overlayArt.emptyHint"),
                color = Color(0xFF9AA0A6),
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
    // Removed: PS2 GS blending accuracy. The RSX has no such setting.
        // Removed: PS2 per-primitive texture filtering. RPCS3's knob is Anisotropic Filtering.
    // Removed: PS2 GS texture preloading.
        // Removed: GSHardwareDownloadMode is PS2 GS readback policy.
    // Removed: the PS3 outputs progressive; nothing to deinterlace.
    // Removed: PCSX2 display filter. RPCS3's equivalent is Output Scaling Mode.
    // Removed: PCSX2 CRT/TV shaders. Use the RetroArch shader chain instead.
        // Removed: PS2 GS dithering.
    // Removed: PS2 GS mipmapping.
    // Removed: superseded by Screen Fit in Renderer settings.
    MenuSwitchRow("VSync", settings.vsyncEnable) {
        viewModel.updateSettings { current -> current.copy(vsyncEnable = it) }
    }
    // Removed: shade boost is PCSX2 post-processing; RPCS3 has no colour controls.
    // Removed: PCRTC anti-blur is a PS2 display filter.
    // Removed: PCRTC screen offsets are PS2.
    // Removed: PS2 PCRTC overscan.
    // Removed: maps to Frame limit = Display, set in Performance.
    // Removed: texture replacement is PCSX2-only; RPCS3 has no such system.
    // Removed: PCSX2 texture-pack streaming; no RPCS3 equivalent.
    // Removed: same, precaching replacement textures.
    // RetroArch shaders, end-to-end in-game: toggle → pick a preset → download more.
    // Same composables the Settings renderer tab renders (single definition in ui/common);
    // only the save lambda differs. updateSettings routes through InGameOverlay.saveSettings,
    // which persists via ConfigStore.save(scope, serial) — honouring the overlay's
    // Global/Game scope — and live-applies with Settings.applyTo(). Identical to how every
    // other row in this pane (shadeboost, tvShader, dithering…) saves; both GS keys ride
    // writeGsToNative(), and the device rebuilds the chain on the next frame, so a preset
    // change is live with no restart.
    com.armsx2.ui.common.ShaderChainSection(
        enabled = settings.shaderChainEnabled,
        preset = settings.shaderChainPreset,
        params = settings.shaderChainParams,
        onEnabledChange = { on -> viewModel.updateSettings { it.copy(shaderChainEnabled = on) } },
        onPresetChange = { path -> viewModel.updateSettings { it.copy(shaderChainPreset = path) } },
        onParamsChange = { next -> viewModel.updateSettings { it.copy(shaderChainParams = next) } },
    )
    com.armsx2.ui.common.ShaderManagerSection()
}

@Composable
private fun PerformancePane(state: EmulationMenuUiState, viewModel: EmulationMenuViewModel) {
    val settings = state.settings
    SectionCard(str("perf.speedLimit.label")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (settings.frameLimitEnable) "${settings.nominalSpeedPercent}%" else str("setup.toggle.off"),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Switch(
                checked = settings.frameLimitEnable,
                onCheckedChange = { enabled ->
                    viewModel.updateSettings { it.copy(frameLimitEnable = enabled) }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalOptionRow(
            options = listOf(50, 75, 90, 100, 110, 125, 150, 200).map { it to "$it%" },
            selected = settings.nominalSpeedPercent,
            keyPrefix = str("perf.speedLimit.label"),
            onSelect = viewModel::setSpeed,
        )
    }
    HorizontalOptions(
        title = str("perf.displayFpsCap.label"),
        // 90 and 120 removed: neither can take effect. RPCS3 caps the presented rate at the
        // Frame limit enum, which tops out at 60 for anything the PS3 outputs, so a Second Frame
        // Limit above that loses the min() at RSXThread.cpp:3676 and the rate stays 60. Offering
        // them just invited "the cap does nothing" reports for the two values where that is true
        // by construction. (Measured: second=90.00 -> limit=60.00.)
        // -1 is not a rate: it selects Frame limit "PS3 Native", the only mode that honours the
        // game's own cellGcmSetFlipMode(VSYNC) request. A title that paces itself to 30fps needs
        // it; under any other mode it flips every vblank and runs at 60 (issue #77).
        options = listOf(0, -1, 20, 30, 45, 60).map {
            it to when (it) {
                0 -> str("setup.toggle.off")
                -1 -> str("perf.displayFpsCap.ps3")
                else -> "$it FPS"
            }
        },
        selected = settings.fpsLimit,
        onSelect = viewModel::setFpsLimit,
    )
    HorizontalOptions(
        title = str("perf.frameSkip.label"),
        options = (0..5).map { it to if (it == 0) str("setup.toggle.off") else "$it" },
        selected = settings.frameSkip,
        onSelect = viewModel::setFrameSkip,
    )
    // Removed: PS2 NTSC refresh rate. The PS3 has no per-region vsync rate to set.
    // Removed: PS2 PAL refresh rate.
    // Removed: EE cycle rate is PS2 silicon.
    // Removed: EE cycle skip is PS2 silicon.
    // Removed: EE/FPU clamping is PS2. See SPU Float Accuracy in Advanced.
    // Removed: VU clamping is PS2. See SPU Float Accuracy in Advanced.
    // Removed: EE/FPU round mode is PS2.
    // Removed: MTVU is a PS2 VU1 thread hack.
    // Removed: Instant VU1 is a PS2 hack.
    // Removed: PS2 CDVD timing hack.
    // Removed: PCSX2 duplicate-frame skip; RPCS3 uses Enable Frame Skip.
    // Removed: PS2 VU flag hack.
    // Removed: PS2 INTC wait-loop hack.
    // Removed: PS2 wait-loop detection.
    // The PS3's processors, not the PS2's. EE/IOP/VU0/VU1/Fastmem are PCSX2
    // recompiler toggles for silicon that does not exist here.
    // Frame generation first: it is the one setting here that changes the framerate rather than
    // how fast the emulator runs, so it is what someone opening this menu mid-game is looking for.
    // Not in the play build: libarmsx3_lsfg.so is not bundled there, so this would be inert.
    if (com.armsx2.BuildConfig.FRAME_GENERATION) {
        SectionCard(str("perf.framegen.title")) {
            HorizontalOptions(
                title = str("perf.framegen.label"),
                options = listOf(
                    str("perf.framegen.off"), str("perf.framegen.x2"),
                    str("perf.framegen.x3"), str("perf.framegen.x4"),
                ).mapIndexed { index, label -> index to label },
                selected = settings.ps3.frameGeneration,
                onSelect = { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(frameGeneration = v)) } },
            )

            // The rest of frame generation, which until now only existed in the main settings
            // screen. Someone who opens this menu mid-game is here to change exactly these:
            // the multiplier alone cannot answer "it is generating, but the picture is unsteady"
            // (target rate) or "it is generating, but too expensive" (flow scale, performance).
            HorizontalOptions(
                title = str("perf.framegen.targetRate.label"),
                options = listOf(0 to str("perf.framegen.off"), 60 to "60 Hz", 90 to "90 Hz", 120 to "120 Hz"),
                selected = settings.ps3.frameGenTargetRate,
                onSelect = { v ->
                    android.util.Log.i("FRAMEGEN", "pause menu: target rate chip -> $v")
                    viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(frameGenTargetRate = v)) }
                },
            )
            // Motion detail is continuous, so it gets a slider rather than three stops -- the
            // useful values are wherever the picture stops improving on a given game, not a set
            // someone picked in advance. 25 is the floor the core clamps to.
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        str("perf.framegen.flowScale.label"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${settings.ps3.frameGenFlowScale}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = settings.ps3.frameGenFlowScale.coerceIn(25, 100).toFloat(),
                    onValueChange = { v ->
                        viewModel.updateSettings {
                            it.copy(ps3 = it.ps3.copy(frameGenFlowScale = Math.round(v).coerceIn(25, 100)))
                        }
                    },
                    valueRange = 25f..100f,
                )
            }
            MenuSwitchRow(
                str("perf.framegen.performance.label"),
                settings.ps3.frameGenPerformance,
            ) { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(frameGenPerformance = v)) } }
        }
        Spacer(Modifier.height(10.dp))
    }
    SectionCard(str("perf.ps3cpu.title")) {
        HorizontalOptions(
            title = str("perf.ppuDecoder.label"),
            options = listOf(str("perf.decoder.interpreter"), str("perf.decoder.llvm"))
                .mapIndexed { index, label -> index to label },
            selected = settings.ps3.ppuDecoder,
            onSelect = { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(ppuDecoder = v)) } },
        )
        Spacer(Modifier.height(6.dp))
        HorizontalOptions(
            title = str("perf.spuDecoder.label"),
            options = listOf(
                str("perf.decoder.interpreter"), str("perf.decoder.interpreterDyn"),
                str("perf.decoder.asmjit"), str("perf.decoder.llvm"),
            ).mapIndexed { index, label -> index to label },
            selected = settings.ps3.spuDecoder,
            onSelect = { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(spuDecoder = v)) } },
        )
        Spacer(Modifier.height(6.dp))
        MenuSwitchRow(str("perf.spuCache.label"), settings.ps3.spuCache) { v ->
            viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(spuCache = v)) }
        }
        Spacer(Modifier.height(6.dp))
        MenuSwitchRow(str("perf.accurateSpuDma.label"), settings.ps3.accurateSpuDma) { v ->
            viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(accurateSpuDma = v)) }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalOptions(
            title = str("perf.spuBlockSize.label"),
            options = listOf("Safe", "Mega", "Giga").mapIndexed { i, l -> i to l },
            selected = settings.ps3.spuBlockSize,
            onSelect = { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(spuBlockSize = v)) } },
        )
        Spacer(Modifier.height(6.dp))
        HorizontalOptions(
            title = str("adv.xfloat.label"),
            options = listOf(
                str("adv.xfloat.accurate"), str("adv.xfloat.approximate"),
                str("adv.xfloat.relaxed"), str("adv.xfloat.inaccurate"),
            ).mapIndexed { i, l -> i to l },
            selected = settings.ps3.spuXFloat,
            onSelect = { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(spuXFloat = v)) } },
        )
        Spacer(Modifier.height(6.dp))
        MenuSwitchRow(str("perf.spuLoopDetection.label"), settings.ps3.spuLoopDetection) { v ->
            viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(spuLoopDetection = v)) }
        }
        Spacer(Modifier.height(6.dp))
        // 0 = auto. Matches All Settings -> Performance.
        HorizontalOptions(
            title = str("perf.preferredSpuThreads.label"),
            options = (0..6).map { it to if (it == 0) str("common.auto") else "$it" },
            selected = settings.ps3.preferredSpuThreads,
            onSelect = { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(preferredSpuThreads = v)) } },
        )
        Spacer(Modifier.height(6.dp))
        HorizontalOptions(
            title = str("perf.clocksScale.label"),
            options = listOf(50, 75, 100, 125, 150, 200).map { it to "$it%" },
            selected = settings.ps3.clocksScale,
            onSelect = { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(clocksScale = v)) } },
        )
    }
    SectionCard(str("tab.overlay")) {
        // RPCS3's overlay has no per-element switches: one Enabled flag plus a
        // Detail Level decides what appears. These five wrote to PCSX2 fields
        // with no RPCS3 node behind them, so every one was inert. The real
        // controls are in Settings -> On Screen.
        MenuSwitchRow(str("overlay.enabled.label"), settings.ps3.overlayEnabled) { value ->
            viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(overlayEnabled = value)) }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalOptions(
            title = str("overlay.detail.label"),
            options = listOf(
                str("overlay.detail.none"), str("overlay.detail.minimal"),
                str("overlay.detail.low"), str("overlay.detail.medium"),
                str("overlay.detail.high"),
            ).mapIndexed { index, label -> index to label },
            selected = settings.ps3.overlayDetail,
            onSelect = { value ->
                viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(overlayDetail = value)) }
            },
        )
    }
}

@Composable
private fun ControlsPane(state: EmulationMenuUiState, viewModel: EmulationMenuViewModel) {
    MenuSwitchRow(str("pad.onScreenControls.label"), state.touchControlsVisible) {
        viewModel.toggleTouchControls()
    }
    MenuSwitchRow(
        title = str("pad.rumble.label"),
        checked = state.rumbleEnabled,
        onCheckedChange = viewModel::setRumble,
    )
    // Vibration Strength — the same global 0-200% haptic multiplier as All Settings ›
    // Controls, reachable here in-game. Local state drives the live update since it's a
    // plain pref (not part of EmulationMenuUiState).
    var haptic by remember { mutableStateOf(com.armsx2.input.ControllerMappings.hapticIntensity()) }
    com.armsx2.ui.settings.IntSliderRow(
        label = str("pad.hapticStrength.label"),
        value = haptic,
        min = 0,
        max = 200,
        description = str("pad.hapticStrength.description"),
        valueFormatter = { if (it == 0) "Off" else "${it}%" },
        onChange = { haptic = it; com.armsx2.input.ControllerMappings.setHapticIntensity(it) },
    )
    // Multitap removed: a PS2 accessory. The PS3 supports seven pads natively.
    MenuSwitchRow(str("network.emulateUsbKeyboard"), state.settings.usbKeyboard) {
        viewModel.updateSettings { current -> current.copy(usbKeyboard = it) }
    }

    // Gesture control, in-game. Worth having here rather than only in All Settings: the swipe
    // distance and the Tap/Hold choice are things you only discover the right value for while
    // actually playing, and walking out to the settings tree to nudge them loses the moment.
    // Local state, like the haptic slider above — these are plain prefs, not part of the ui state.
    var gestureOn by remember { mutableStateOf(TouchControls.gestureEnabled.value) }
    MenuSwitchRow(str("pad.gesture.enable.label"), gestureOn) {
        gestureOn = it
        TouchControls.setGestureEnabled(it)
    }
    if (gestureOn) {
        var swipeSens by remember { mutableStateOf((TouchControls.gestureSwipeSensitivity.floatValue * 100f).toInt()) }
        com.armsx2.ui.settings.IntSliderRow(
            label = str("pad.gesture.sensitivity.label"),
            value = swipeSens,
            min = 5,
            max = 60,
            description = str("pad.gesture.sensitivity.description"),
            valueFormatter = { "${it}%" },
            onChange = { swipeSens = it; TouchControls.setGestureSensitivity(it / 100f) },
        )
        var holdMode by remember { mutableStateOf(TouchControls.gestureDoubleTapHold.value) }
        HorizontalOptions(
            title = str("pad.gesture.doubleTapMode.label"),
            options = listOf(
                0 to str("pad.gesture.doubleTapMode.tap"),
                1 to str("pad.gesture.doubleTapMode.hold"),
            ),
            selected = if (holdMode) 1 else 0,
            onSelect = { holdMode = it == 1; TouchControls.setGestureDoubleTapHold(holdMode) },
        )
        // The four swipe/double-tap ASSIGNMENTS stay in All Settings — six button pickers would
        // swamp this pane, and you set them once rather than mid-session.
    }
    CompactAction(str("pad.controllerMapping"), "⌁", Modifier.fillMaxWidth(), viewModel::openControlsManager)
    Spacer(Modifier.height(6.dp))
    CompactAction(str("pad.editTouchLayout"), "✥", Modifier.fillMaxWidth(), viewModel::editTouchControls)
    Spacer(Modifier.height(6.dp))
    // Sits with the touch layout because it's the same job: what the on-screen pad LOOKS
    // like, right after where it's laid out. Full-screen like Controller mapping.
    CompactAction(str("tab.skins"), "◈", Modifier.fillMaxWidth(), viewModel::openSkins)
    // Motion / gyroscope controls in-game (mode, sensitivity, smoothing, invert). Global scope
    // to match the rumble toggle above; the per-game scope lives in All Settings › Controls.
    com.armsx2.ui.settings.GyroSection()
    // Macros — edit each M1-M4 button set here in-game too (physical-trigger binding stays
    // in All Settings › Controls, which hosts the key-capture listener).
    com.armsx2.ui.settings.MacrosSection()
}

@Composable
private fun OptionsPane(state: EmulationMenuUiState, viewModel: EmulationMenuViewModel) {
    val settings = state.settings
    // Item 3: gateway to the full per-game settings (all categories the compact menu omits:
    // OSD, Skins, Audio, Hotkeys, Network, Recompiler, ...).
    CompactAction(str("action.allSettings"), "⚙", Modifier.fillMaxWidth(), viewModel::openFullSettings)
    Spacer(Modifier.height(6.dp))
    // ...and the whole core config beneath it, scoped to this game. The rows in this pane are
    // the handful of RPCS3 switches worth flipping mid-session; everything else lived behind
    // the library drawer, where it could only be set globally.
    // Glyph is one already proven to render in the shipped font: "▩" (U+25A9) and "⏻" (U+23FB)
    // come out as tofu boxes on device, while "▣" is used by the BIOS/onboarding screens.
    CompactAction(str("core.settings.title"), "▣", Modifier.fillMaxWidth(), viewModel::openCoreSettings)
    Spacer(Modifier.height(6.dp))
    // In-game access to the manager screens. Memory cards and PNACH are gone
    // (no PS3 equivalent); patches now have their own per-game list above.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    }
    Spacer(Modifier.height(6.dp))
    // Texture packs belong here too: the pack folder has to match the RUNNING game's serial,
    // so the screen only tells you anything useful with a game loaded — and buried in
    // All Settings -> Renderer it was effectively unreachable mid-session.
    // Glyph must be one already proven to render in the shipped font — "▩" (U+25A9) and
    // "⏻" (U+23FB) come out as tofu boxes on device. "▣" is used by the BIOS/onboarding
    // screens, so it is known good.
    // Removed: the texture-pack manager replaces GS textures by hash, which is a
    // PCSX2 feature. RPCS3 has no texture-replacement system, so the screen had
    // nothing to manage. RetroArch shader chains are the equivalent here and
    // live in Renderer settings.
    Spacer(Modifier.height(6.dp))

    // Patches for THIS game only. Desktop RPCS3 scopes the in-game patch list to
    // the running title, and the global list is ~2000 entries -- unusable mid
    // session and not what you want anyway.
    com.armsx2.ui.patches.Ps3PatchesTab(
        serial = com.armsx2.runtime.MainActivityRuntime.currentGame.value?.serial.orEmpty(),
    )
    Spacer(Modifier.height(6.dp))

    // PNACH patches/cheats/widescreen/no-interlacing and "Skip BIOS" are all
    // PCSX2 concepts. These are RPCS3 switches worth reaching mid-session.
    MenuSwitchRow(str("renderer.strictRendering.label"), settings.ps3.strictRendering) {
        viewModel.updateSettings { current -> current.copy(ps3 = current.ps3.copy(strictRendering = it)) }
    }
    MenuSwitchRow(str("renderer.multithreadedRsx.label"), settings.ps3.multithreadedRsx) {
        viewModel.updateSettings { current -> current.copy(ps3 = current.ps3.copy(multithreadedRsx = it)) }
    }
    MenuSwitchRow(str("renderer.writeColorBuffers.label"), settings.ps3.writeColorBuffers) {
        viewModel.updateSettings { current -> current.copy(ps3 = current.ps3.copy(writeColorBuffers = it)) }
    }
    MenuSwitchRow(str("renderer.disableZcull.label"), settings.ps3.disableZcull) {
        viewModel.updateSettings { current -> current.copy(ps3 = current.ps3.copy(disableZcull = it)) }
    }
    MenuSwitchRow(str("renderer.writeDepthBuffer.label"), settings.ps3.writeDepthBuffer) {
        viewModel.updateSettings { current -> current.copy(ps3 = current.ps3.copy(writeDepthBuffer = it)) }
    }
    MenuSwitchRow(str("renderer.readDepthBuffer.label"), settings.ps3.readDepthBuffer) {
        viewModel.updateSettings { current -> current.copy(ps3 = current.ps3.copy(readDepthBuffer = it)) }
    }
    MenuSwitchRow(str("renderer.forceCpuBlit.label"), settings.ps3.forceCpuBlit) {
        viewModel.updateSettings { current -> current.copy(ps3 = current.ps3.copy(forceCpuBlit = it)) }
    }
    // The block that stood here was PCSX2's GameDB gamefix set -- Skip MPEG,
    // Instant DMA, Blit Internal FPS, VU Add/Sub, VU Sync. Every one names PS2
    // silicon (the VU units, the EE's DMA controller) and wrote to
    // Settings.gamefix* fields with no RPCS3 node behind them, so toggling any
    // of them did nothing at all.
    //
    // RPCS3 has no gamefix list to replace them with: per-title workarounds live
    // in its patch database, which is the per-game patch list right above.
    MenuSwitchRow(str("adv.accurateRsxRsv.label"), settings.ps3.accurateRsxRsv) {
        viewModel.updateSettings { current -> current.copy(ps3 = current.ps3.copy(accurateRsxRsv = it)) }
    }
    MenuSwitchRow(str("adv.hleLwmutex.label"), settings.ps3.hleLwmutex) {
        viewModel.updateSettings { current -> current.copy(ps3 = current.ps3.copy(hleLwmutex = it)) }
    }
}

@Composable
private fun AchievementsPane(state: EmulationMenuUiState, viewModel: EmulationMenuViewModel) {
    // Gateway to the full RetroAchievements screen (unlock list + presentation options).
    CompactAction(str("ra.viewAchievements"), "★", Modifier.fillMaxWidth(), viewModel::openAchievements)
    Spacer(Modifier.height(4.dp))
    SectionCard("RetroAchievements") {
        // Signed-in account: avatar + name + both point totals (hardcore / softcore).
        if (state.raUserName.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.raAvatarUrl.isNotBlank()) {
                    AsyncImage(
                        state.raAvatarUrl,
                        state.raUserName,
                        Modifier.size(46.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.raUserName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${state.raScore} HC",
                            style = MaterialTheme.typography.bodySmall,
                            color = com.armsx2.ui.theme.Danger,
                        )
                        Text(
                            "  ·  ${state.raSoftcoreScore} SC",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Text(
            state.achievementSummary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        MenuSwitchRow(
            str(if (state.hardcore) "ra.mode.hardcore" else "ra.mode.casual"),
            state.hardcore,
            onCheckedChange = { viewModel.requestToggleHardcore() },
        )
    }
    // Inline unlock list, right below the hardcore toggle — no need to open the full
    // screen (it's still available via the button above).
    state.achievements.forEach { item -> InGameAchievementRow(item) }
}

/**
 * The running game's PS3 trophies. RPCS3's own data, not RetroAchievements.
 *
 * The rows are [com.armsx2.ui.trophies.TrophyRow] — the SAME composable the library's Trophies
 * screen uses, not a copy — so the two lists cannot drift apart. Scoping is
 * TrophyRepository.loadCurrentGame(), which asks the core for its `current_trophy_name`.
 *
 * Its ViewModel is keyed apart from the library screen's so the two do not fight over one
 * instance: this pane and the full in-game screen deliberately SHARE that keyed instance, so
 * opening the full list reuses what the pane already loaded instead of rescanning.
 */
@Composable
private fun TrophiesPane(viewModel: EmulationMenuViewModel) {
    val trophies: com.armsx2.ui.trophies.TrophiesViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(key = InGameTrophiesVmKey)
    val state = trophies.state.value
    // Re-read on every entry to the tab: a trophy can unlock while the game is running, and the
    // set itself only appears once the game creates its trophy context.
    LaunchedEffect(Unit) { trophies.refresh(currentGameOnly = true) }

    val game = state.games.firstOrNull()

    CompactAction(
        str("trophies.viewTrophies"),
        "🏆",
        Modifier.fillMaxWidth(),
        viewModel::openTrophies,
    )
    Spacer(Modifier.height(4.dp))

    SectionCard(str("trophies.title")) {
        when {
            state.loading && game == null -> Text(
                str("trophies.loading"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Most PS3 games have no trophy set at all, and plenty of those that do only
            // register it once you reach a menu — so this is an ordinary state, not an error.
            game == null -> Text(
                str("trophies.none.body"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                Text(
                    game.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    com.armsx2.i18n.I18n.get("trophies.progress")
                        .replace("%1", game.unlocked.toString())
                        .replace("%2", game.total.toString())
                        .replace("%3", game.percent.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Inline list, mirroring the RA pane: the gateway above is still there for the full screen
    // (with the show-hidden toggle), but the common case is a glance at what is left.
    game?.let { set ->
        trophies.visibleTrophies(set).forEach { com.armsx2.ui.trophies.TrophyRow(it) }
    }
}

/** Shared ViewModel key for the two in-game trophy surfaces (this pane and the full screen). */
internal const val InGameTrophiesVmKey = "trophies-ingame"

@Composable
private fun InGameAchievementRow(item: AchievementItem) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (item.unlocked) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(
            1.dp,
            if (item.unlocked) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.iconUrl.isNotBlank()) {
                AsyncImage(
                    item.iconUrl,
                    item.title,
                    Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) { Text(if (item.unlocked) "★" else "☆") }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.progress.isNotBlank()) {
                    Text(item.progress, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(8.dp))
            // Flag missables in-game — the actionable warning while you're actually playing.
            // Progression/Win badges are left to the full achievements screen to avoid clutter here.
            if (item.type == 1) {
                StatusChip(str("ra.typeChip.missable"), Color(0xFFF5A623))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "${item.points}",
                style = MaterialTheme.typography.labelMedium,
                color = if (item.unlocked) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HardcoreBadge() {
    // Firebrick red to match the old UI's hardcore pill (theme Danger reads pink here).
    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFB22222)) {
        Text(
            "HC",
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

private data class MenuAction(
    val title: String,
    val detail: String,
    val glyph: String,
    val accent: Color?,
    val action: () -> Unit,
)

@Composable
private fun ActionGrid(actions: List<MenuAction>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEachIndexed { index, item ->
            val active = index == selected
            Surface(
                onClick = { onSelect(index); item.action() },
                modifier = Modifier
                    .fillMaxWidth()
                    .controllerFocusable("pause.action.$index", onConfirm = { onSelect(index); item.action() }),
                shape = RoundedCornerShape(16.dp),
                color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                border = BorderStroke(
                    1.dp,
                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.34f),
                ),
            ) {
                Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.glyph,
                        color = item.accent ?: MaterialTheme.colorScheme.primary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(30.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (item.detail.isNotBlank()) {
                            Text(
                                item.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun <T> HorizontalOptions(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    SectionCard(title) {
        HorizontalOptionRow(options, selected, keyPrefix = title, onSelect = onSelect)
    }
}

// Free-choice framerate slider (20–120 Hz) instead of a couple of fixed chips.
// The default (59.94 / 50) is kept exactly, and the 60/50 stops snap back to
// those exact PS2 rates (canonicalFramerate) so the true default is always
// recoverable; every other stop is whole Hz for easy targets (72/90/120).
@Composable
private fun FramerateSlider(title: String, value: Float, onValue: (Float) -> Unit) {
    SectionCard(title) {
        Column(
            Modifier.fillMaxWidth().controllerFocusable(
                "pause.framerate.$title",
                onLeft = { onValue(canonicalFramerate((Math.round(value) - 1).coerceAtLeast(20))) },
                onRight = { onValue(canonicalFramerate((Math.round(value) + 1).coerceAtMost(120))) },
            ),
        ) {
            val label = if (value % 1f == 0f) "${value.toInt()} Hz" else "%.2f Hz".format(value)
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Slider(
                value = value.coerceIn(20f, 120f),
                onValueChange = { onValue(canonicalFramerate(Math.round(it))) },
                valueRange = 20f..120f,
            )
        }
    }
}

// The PS2's true NTSC/PAL rates are 59.94/50.00 Hz; the integer slider stops at
// 60/50 map back to those exact defaults so the canonical rate stays recoverable
// (dragging otherwise snaps to whole Hz and loses 59.94 forever).
private fun canonicalFramerate(hz: Int): Float = when (hz) {
    60 -> 59.94f
    50 -> 50.00f
    else -> hz.toFloat()
}

@Composable
private fun <T> HorizontalOptionRow(
    options: List<Pair<T, String>>,
    selected: T,
    keyPrefix: String,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .bleedHorizontal(13.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            OptionChip(label, selected == value, controllerId = "pause.$keyPrefix.$label") { onSelect(value) }
        }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, controllerId: String? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.controllerFocusable(controllerId, RoundedCornerShape(12.dp), onConfirm = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.17f)
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
        ),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2,
        )
    }
}

@Composable
private fun MenuSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = { if (enabled) onCheckedChange(!checked) },
        modifier = Modifier
            .fillMaxWidth()
            .controllerFocusable(
                "pause.switch.$title",
                onConfirm = { if (enabled) onCheckedChange(!checked) },
                onLeft = { if (enabled) onCheckedChange(false) },
                onRight = { if (enabled) onCheckedChange(true) },
            ),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.42f else 0.24f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = checked, onCheckedChange = if (enabled) onCheckedChange else null)
        }
    }
}

/** Label + current value, cycled in place: tap/confirm and Right advance, Left steps back.
 *  The compact menu has no picker of its own and a segmented control doesn't fit its width,
 *  so multi-option settings cycle rather than expand. */
@Composable
private fun MenuCycleRow(
    title: String,
    valueLabel: String,
    onStep: (Int) -> Unit,
) {
    Surface(
        onClick = { onStep(1) },
        modifier = Modifier
            .fillMaxWidth()
            .controllerFocusable(
                "pause.cycle.$title",
                onConfirm = { onStep(1) },
                onLeft = { onStep(-1) },
                onRight = { onStep(1) },
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                valueLabel,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CompactAction(title: String, glyph: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.controllerFocusable("pause.compact.$title", onConfirm = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(glyph, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2)
        }
    }
}

private fun Modifier.bleedHorizontal(edge: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val edgePx = edge.roundToPx()
    val expandedMin = (constraints.minWidth + edgePx * 2).coerceAtMost(constraints.maxWidth + edgePx * 2)
    val expandedMax = constraints.maxWidth + edgePx * 2
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = expandedMin,
            maxWidth = expandedMax,
        ),
    )
    layout(constraints.maxWidth, placeable.height) {
        placeable.placeRelative(-edgePx, 0)
    }
}
