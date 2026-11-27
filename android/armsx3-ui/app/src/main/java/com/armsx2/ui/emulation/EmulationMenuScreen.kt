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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import com.armsx2.ui.settings.swipeBetweenTabs
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
import com.armsx3.Rpcs3Bridge
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

/** The Friends panel's nav layer: only its controls answer the pad while it is open. */
private const val FRIENDS_NAV_LAYER = "menu.friends"
private const val PEEK_NAV_LAYER = "menu.peek"

/** The toy portal panel's nav layer, the same way. */
private const val PORTAL_NAV_LAYER = "menu.portal"

@Composable
fun EmulationMenuScreen(viewModel: EmulationMenuViewModel = viewModel()) {
    val state = viewModel.state.value
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    var friendsOpen by remember { mutableStateOf(false) }
    var portalOpen by remember { mutableStateOf(false) }
    // The menu set aside, the game still paused: to read what is on screen behind the panel.
    // The rail stays (wide layout) and a Show menu pill brings the panel back, as do tapping the
    // game, a rail tab, B, or A on the pill.
    var peeking by remember { mutableStateOf(false) }
    var restoreAfterPeek by remember { mutableStateOf<String?>(null) }
    val peek: () -> Unit = {
        restoreAfterPeek = com.armsx2.ui.settings.SettingsControllerNav.currentSelectedId()
        peeking = true
    }
    val unpeek: () -> Unit = { peeking = false }
    // The emulated portals are attached to every game, and only the toys-to-life series look at them;
    // Portal shows unless switched off in USB Devices settings
    val portalEntry = remember { com.armsx2.input.ToyPortals.menuEnabled() }
    // A friend waiting to be let in is answered in the hosting panel, which sits with Friends: the
    // menu opens there, whether it was opened for the question or was already open.
    val hostStatus by com.armsx2.remoteplay.RemotePlayHost.status
    val asking = hostStatus is com.armsx2.remoteplay.RemotePlayHost.Status.Asking
    LaunchedEffect(asking) { if (asking) friendsOpen = true }
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
            SessionOrder.finishArranging()
        }
    }
    LaunchedEffect(Unit) { shown = true }
    // A touch since the pad last moved: the menu reopens on the tab and that tab's scroll, not on the
    // control the pad left, which selecting again would scroll the page back to.
    val padActive = com.armsx2.ui.common.PadInput.active.value
    LaunchedEffect(padActive) {
        if (!padActive) com.armsx2.ui.MenuPlace.note(viewModel.state.value.tab, inContent = false, controlId = null)
    }
    // Peeking takes the pad the way Friends does: its pill is the one control, B or A brings the
    // panel back, and the control the pad had is picked again once the pane has composed.
    DisposableEffect(peeking) {
        if (!peeking) return@DisposableEffect onDispose {}
        EmulationMenuInputController.overlayDismiss = unpeek
        val nav = com.armsx2.ui.settings.SettingsControllerNav
        val previous = nav.activeLayer.value
        nav.activeLayer.value = PEEK_NAV_LAYER
        nav.clearSelection()
        onDispose {
            EmulationMenuInputController.overlayDismiss = null
            nav.activeLayer.value = previous
            nav.clearSelection()
        }
    }
    LaunchedEffect(peeking) {
        delay(220)
        if (peeking) {
            com.armsx2.ui.settings.SettingsControllerNav.move(1)
        } else {
            restoreAfterPeek?.let { com.armsx2.ui.settings.SettingsControllerNav.selectById(it) }
            restoreAfterPeek = null
        }
    }

    // Hand pad input to the Friends panel while it is open, and give it back on close.
    //
    // The nav registry is shared between the menu and the panel, so ownership has to be explicit:
    // the selection is cleared on both edges, because a selection left pointing at a control on
    // the other side of the transition highlights something the user cannot see.
    // The panel also claims its own nav layer while open: the menu stays composed behind it, and
    // without a layer a move could pick one of the menu's controls.
    DisposableEffect(friendsOpen) {
        if (!friendsOpen) return@DisposableEffect onDispose {}
        EmulationMenuInputController.overlayDismiss = { friendsOpen = false }
        val nav = com.armsx2.ui.settings.SettingsControllerNav
        val previous = nav.activeLayer.value
        nav.activeLayer.value = FRIENDS_NAV_LAYER
        nav.clearSelection()
        onDispose {
            EmulationMenuInputController.overlayDismiss = null
            nav.activeLayer.value = previous
            nav.clearSelection()
        }
    }
    // The portal panel takes the pad the same way.
    val portalScroll = rememberScrollState()
    DisposableEffect(portalOpen) {
        if (!portalOpen) return@DisposableEffect onDispose {}
        // A spot's chooser or the figure maker in the panel steps back first
        EmulationMenuInputController.overlayDismiss = {
            val back = ToyPortalBack.handler
            if (back != null) back() else portalOpen = false
        }
        EmulationMenuInputController.overlayEdgeScroll = { dy ->
            scope.launch { portalScroll.animateScrollTo(if (dy > 0) portalScroll.maxValue else 0) }
        }
        val nav = com.armsx2.ui.settings.SettingsControllerNav
        val previous = nav.activeLayer.value
        nav.activeLayer.value = PORTAL_NAV_LAYER
        nav.clearSelection()
        onDispose {
            EmulationMenuInputController.overlayDismiss = null
            EmulationMenuInputController.overlayEdgeScroll = null
            nav.activeLayer.value = previous
            nav.clearSelection()
        }
    }
    LaunchedEffect(portalOpen) {
        if (portalOpen) {
            delay(260)
            if (portalOpen) com.armsx2.ui.settings.SettingsControllerNav.move(1)
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
    BackHandler(onBack = {
        when {
            peeking -> unpeek()
            friendsOpen -> friendsOpen = false
            portalOpen -> portalOpen = false
            SessionOrder.arranging -> SessionOrder.finishArranging()
            else -> closeMenu()
        }
    })

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
            visible = shown && !peeking,
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
        // Peeking: the game is all there is to see, and touching it brings the menu back rather
        // than closing it.
        if (peeking) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = unpeek,
                    ),
            )
        }
        AnimatedVisibility(
            visible = shown,
            enter = slideInHorizontally(tween(320, easing = EaseOut)) { it },
            exit = slideOutHorizontally(tween(220, easing = EaseIn)) { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            if (compact) {
                if (!peeking) Surface(
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
                        onOpenPortal = if (portalEntry) { { portalOpen = true } } else null,
                        onPeek = peek,
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
                    if (peeking) {
                        Spacer(Modifier.weight(1f))
                    } else Surface(
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
                            onOpenPortal = if (portalEntry) { { portalOpen = true } } else null,
                            onPeek = peek,
                        )
                    }
                    Surface(
                        modifier = Modifier.width(76.dp).fillMaxHeight(),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        shadowElevation = 18.dp,
                    ) {
                        MenuRail(state.tab) { tab -> unpeek(); viewModel.selectTab(tab) }
                    }
                }
            }
        }

        // Show menu, while peeking: at the top right, clear of the rail in the wide layout. The one
        // controller stop while the panel is away.
        AnimatedVisibility(
            visible = shown && peeking,
            enter = fadeIn(tween(160, easing = EaseOut)),
            exit = fadeOut(tween(120, easing = EaseIn)),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.armsx2.ui.settings.LocalNavLayer provides PEEK_NAV_LAYER,
            ) {
                Surface(
                    onClick = unpeek,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(top = 14.dp, end = if (compact) 12.dp else 12.dp + 76.dp + 10.dp)
                        .controllerFocusable("menu.peek.show", RoundedCornerShape(16.dp), onConfirm = unpeek),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    shadowElevation = 12.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("☰", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(str("menu.peek.show"), style = MaterialTheme.typography.labelLarge)
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
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.armsx2.ui.settings.LocalNavLayer provides FRIENDS_NAV_LAYER,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                str("friends.title"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            // Between the title and Close: who you are, on RPCN (with the avatar games
                            // show for you) and on Discord.
                            Row(
                                Modifier.weight(1f).padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                com.armsx2.ui.friends.RpcnSelfChip(Modifier.weight(1f, fill = false), caption = "RPCN")
                                if (com.armsx2.DiscordPresence.available()) {
                                    com.armsx2.ui.friends.SelfChip(Modifier.weight(1f, fill = false), caption = "Discord")
                                }
                            }
                            TextButton(
                                onClick = { friendsOpen = false },
                                modifier = Modifier.controllerFocusable(
                                    "menu.friends.close",
                                    onConfirm = { friendsOpen = false },
                                ),
                            ) { Text(str("action.close")) }
                        }
                        // Hosting sits with Friends: there is no room for another tab in this menu.
                        com.armsx2.remoteplay.RemotePlayHostPanel(
                            Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        )
                        com.armsx2.ui.friends.RpcnFriendsPanel(
                            Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp),
                            inGame = true,
                        )
                        if (com.armsx2.DiscordPresence.available()) {
                            com.armsx2.ui.friends.FriendsPanel(Modifier.padding(horizontal = 12.dp))
                        }
                    }
                }
            }
        }

        // The toy portals, as their own panel over the menu, for the same reasons as Friends
        AnimatedVisibility(
            visible = portalOpen,
            enter = fadeIn(tween(160, easing = EaseOut)),
            exit = fadeOut(tween(140, easing = EaseIn)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .clickable { portalOpen = false },
            )
        }
        AnimatedVisibility(
            visible = portalOpen,
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
                Column(Modifier.fillMaxSize().verticalScroll(portalScroll)) {
                    // The right stick scrolls it, as it does Settings
                    com.armsx2.ui.settings.ControllerAutoScroll(portalScroll)
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.armsx2.ui.settings.LocalNavLayer provides PORTAL_NAV_LAYER,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                str("portal.button"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { portalOpen = false },
                                modifier = Modifier.controllerFocusable(
                                    "menu.portal.close",
                                    onConfirm = { portalOpen = false },
                                ),
                            ) { Text(str("action.close")) }
                        }
                        ToyPortalPanel(Modifier.padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 16.dp))
                    }
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
    onOpenPortal: (() -> Unit)?,
    onPeek: () -> Unit,
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
        // The arrange view belongs to the first tab; leaving the tab closes it, keeping the order.
        LaunchedEffect(state.tab) { if (state.tab != EmulationMenuTab.Session) SessionOrder.finishArranging() }
        Column(
            modifier
                .onGloballyPositioned { SessionOrder.viewport = it.boundsInRoot() }
                // Swipe sideways for the next or previous tab, as well as tapping the rail.
                .swipeBetweenTabs { viewModel.cycleTab(it) }
                .verticalScroll(scrollState)
                .padding(bottom = 18.dp),
        ) {
            if (compact) CompactMenuTabs(state.tab, viewModel::selectTab)
            MenuHeader(compact, state.hardcore, state.richPresence, state.gameCRC, onOpenFriends, onPeek)
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.34f),
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (state.tab) {
                    EmulationMenuTab.Session -> SessionPane(state, viewModel, onOpenPortal)
                    EmulationMenuTab.Graphics -> GraphicsPane(state, viewModel)
                    // The full Fixes settings tab, live-applying via InGameOverlay's
                    // shared Settings state (same as the rest of the overlay); its
                    // controls are SettingsControllerNav items, so the pause menu's
                    // content-pane nav drives them for free.
                    //
                    // The RPCS3 database card goes first, above CPU Accuracy: it is the
                    // reason a fix set on this tab may not be the one the game runs with,
                    // since the database's value for this title is applied over your
                    // settings until you switch its entry off (a value you set for this
                    // game yourself still wins). It renders nothing for a title without an
                    // entry, so most games open straight onto CPU Accuracy. In-game only:
                    // the settings hub's Fixes tab has no running title to read.
                    EmulationMenuTab.Fixes -> {
                        DatabaseSection()
                        com.armsx2.ui.settings.FixesTab(InGameOverlay.settingsState)
                        // Most fixes are read when the game boots, and the core keeps a change
                        // to one for the next boot (issue #228). In-game only, as here.
                        Spacer(Modifier.height(8.dp))
                        CompactAction(str("backend.applyRestart"), "↻", Modifier.fillMaxWidth(), MainActivityRuntime::restart)
                    }
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
    // The strip scrolls itself to the selected tab, so it stays reachable on a narrow screen. Not
    // bringIntoView: the strip sits at the top of the page, which would be scrolled back up with it,
    // losing the place the page reopens at.
    val strip = rememberScrollState()
    val starts = remember { androidx.compose.runtime.mutableStateMapOf<EmulationMenuTab, Int>() }
    val selectedStart = starts[selected]
    LaunchedEffect(selected, selectedStart) {
        selectedStart?.let { runCatching { strip.animateScrollTo((it - 24).coerceAtLeast(0)) } }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(strip)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EmulationMenuTab.visible.forEach { tab ->
            MenuTab(
                tab, tab == selected, onSelect,
                Modifier.onGloballyPositioned { starts[tab] = it.positionInParent().x.toInt() },
            )
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
private fun MenuTab(
    tab: EmulationMenuTab,
    active: Boolean,
    onSelect: (EmulationMenuTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { onSelect(tab) },
        modifier = modifier
            .widthIn(min = 132.dp, max = 210.dp)
            .padding(vertical = 3.dp),
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
    onPeek: () -> Unit,
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

        // Hide the menu to see the game behind it, still paused, for text that leaves the screen
        // too quickly to read any other way.
        Spacer(Modifier.width(8.dp))
        val peekLabel = str("menu.peek")
        Surface(
            onClick = onPeek,
            modifier = Modifier
                .controllerFocusable("menu.peek", RoundedCornerShape(14.dp), onConfirm = onPeek)
                .semantics { contentDescription = peekLabel },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        ) {
            Box(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
                Text("👁", fontSize = 19.sp)
            }
        }

        // Friends, in the header where it is always visible, with the online count on it. Shown
        // even where Discord is not available: remote play hosting lives behind it too, and
        // hosting does not need Discord. The Discord parts inside hide themselves there.
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

private const val CARD_SAVES = "card.saveSlots"
private const val CARD_SCREEN = "card.onScreen"

@Composable
private fun SessionPane(state: EmulationMenuUiState, viewModel: EmulationMenuViewModel, onOpenPortal: (() -> Unit)?) {
    // Which disc card is open: null, change, or swap. Swap is offered only while the game can take
    // one, which the core reports as it runs; see DiscSection.
    var discCard by remember { mutableStateOf<Boolean?>(null) }
    val canSwap = remember { com.armsx3.Rpcs3Bridge.discSwapState() != 0 }
    val actions = buildList {
        add(MenuAction(str("action.resume"), str("action.play"), "▶", Success, key = "resume", action = viewModel::resume))
        add(MenuAction(str("memcard.restart"), str("action.reset"), "↻", null, key = "restart", action = MainActivityRuntime::restart))
        add(MenuAction(str("action.changeDisc"), str("action.changeDisc.detail"), "⏏", null, key = "changeDisc") {
            discCard = if (discCard == false) null else false
        })
        if (canSwap) {
            add(MenuAction(str("action.swapDisc"), str("action.swapDisc.detail"), "⇄", null, key = "swapDisc") {
                discCard = if (discCard == true) null else true
            })
        }
        // Skylanders, Disney Infinity and LEGO Dimensions figures (ToyPortalPanel)
        if (onOpenPortal != null) {
            add(MenuAction(str("portal.button"), "Skylanders, Disney Infinity, LEGO Dimensions", "\u25CE", null, key = "portal", action = onOpenPortal))
        }
        add(MenuAction(str("action.close"), MainActivityRuntime.currentGame.value?.title.orEmpty(), "■", Danger, key = "close") {
            MainActivityRuntime.closeGame()
        })
    }
    val savesTitle = str("savestate.title.loadManage")
    val screenTitle = str("tab.overlay")
    // Load / Manage Saves right under the actions (below Close), above the long on-screen card.
    val order = SessionOrder.ordered(actions.map { it.key } + listOf(CARD_SAVES, CARD_SCREEN))
    val byKey = actions.associateBy { it.key }
    // What a pad press on the grid runs: the actions in the order shown, so the two cannot drift apart.
    val shownActions = order.mapNotNull { byKey[it] }
    androidx.compose.runtime.SideEffect { viewModel.sessionActions = shownActions.map { it.action } }
    if (SessionOrder.arranging) {
        val labels = actions.associate { it.key to (it.glyph to it.title) } +
            mapOf(CARD_SAVES to ("↥" to savesTitle), CARD_SCREEN to ("▣" to screenTitle))
        SessionArrangeView(order, labels)
    } else {
        // Runs of actions stay one tight list; the cards sit between the runs, wherever the
        // player has put them. The disc card opens under the run holding its action.
        val segments = buildList<Any> {
            var run = mutableListOf<MenuAction>()
            order.forEach { key ->
                val action = byKey[key]
                if (action != null) {
                    run.add(action)
                } else {
                    if (run.isNotEmpty()) { add(run); run = mutableListOf() }
                    add(key)
                }
            }
            if (run.isNotEmpty()) add(run)
        }
        val discKey = when (discCard) { false -> "changeDisc"; true -> "swapDisc"; null -> null }
        var offset = 0
        segments.forEach { segment ->
            if (segment is List<*>) {
                @Suppress("UNCHECKED_CAST")
                val run = segment as List<MenuAction>
                val first = offset
                offset += run.size
                androidx.compose.runtime.key("run:${run.first().key}") {
                    ActionGrid(run, selected = state.selectedAction, onSelect = viewModel::selectAction, indexOffset = first)
                    if (discKey != null && run.any { it.key == discKey }) DiscSection(viewModel, discCard == true)
                }
            } else {
                androidx.compose.runtime.key(segment) {
                    when (segment) {
                        CARD_SAVES -> SaveSlotsCard(state, viewModel)
                        CARD_SCREEN -> OnScreenCard(state, viewModel)
                    }
                }
            }
        }
        // The controller's way in: holding a row is touch only.
        CompactAction(str("menu.arrange.title"), "⇅", Modifier.fillMaxWidth()) {
            SessionOrder.startArranging()
            com.armsx2.ui.settings.SettingsControllerNav.focusWhenRegistered(null, "pause.arrange.${order.first()}")
        }
    }
}

@Composable
private fun OnScreenCard(state: EmulationMenuUiState, viewModel: EmulationMenuViewModel) {
    // On-screen display — a single universal on/off (old-UI style); the per-stat
    // toggles live in All Settings. Plus a frame-limit switch so fast-forward is one
    // tap away.
    SectionCard(str("tab.overlay"), onTitleLongPress = { SessionOrder.startArranging() }) {
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
        // Removed: fast-forward speed. Speed control is PCSX2's -- speedhackLimitermode is an
        // Unsupported shim on this core -- so the slider set a number nothing could read.
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
        // The compile notices, here because this is where someone turns off what is on screen.
        // Plain switches with their own callbacks, like the rest of this card, so they cannot shift
        // the action grid's controller dispatch above.
        MenuSwitchRow(
            str("overlay.shaderHint.label"),
            state.settings.ps3.showShaderCompileHint,
            description = str("overlay.shaderHint.desc"),
        ) { v ->
            viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(showShaderCompileHint = v)) }
        }
        MenuSwitchRow(
            str("overlay.spuHint.label"),
            state.settings.ps3.showSpuCompileHint,
            description = str("overlay.spuHint.desc"),
        ) { v ->
            viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(showSpuCompileHint = v)) }
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
}

@Composable
private fun SaveSlotsCard(state: EmulationMenuUiState, viewModel: EmulationMenuViewModel) {
    SectionCard(str("savestate.title.loadManage"), onTitleLongPress = { SessionOrder.startArranging() }) {
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
    // The in-game XMB has the API, the driver and Apply and Restart in a node of their own, which
    // asks to restart after a change; its Renderer list starts at the resolution.
    if (com.armsx2.ui.xmb.LocalXmbCollector.current == null) GraphicsBackendRows(settings, viewModel)
    HorizontalOptions(
        title = str("renderer.upscale.label"),
        // Share the full settings-tab list so the sub-native 0.25/0.5/0.75/Native
        // options aren't dropped in the in-game quick menu.
        options = com.armsx2.ui.settings.UPSCALE_OPTIONS.map { it.value to it.label },
        selected = settings.upscaleFloat,
        onSelect = viewModel::setUpscale,
    )
    GraphicsPaneRest(settings, viewModel)
}

@Composable
private fun GraphicsBackendRows(settings: com.armsx2.config.Settings, viewModel: EmulationMenuViewModel) {
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
}

@Composable
private fun GraphicsPaneRest(settings: com.armsx2.config.Settings, viewModel: EmulationMenuViewModel) {
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
        // Orientation, the Renderer tab's four choices. In-game because the moment you need it is
        // mid-game: on "Device" the system auto-rotate decides, so tilting a handheld far enough,
        // steering a SIXAXIS section for one, turns the game over to portrait.
        HorizontalOptions(
            title = str("renderer.orientation.label"),
            options = listOf(
                str("renderer.orientation.device"), str("renderer.orientation.landscape"),
                str("renderer.orientation.portrait"), str("renderer.orientation.autoRotate"),
            ).mapIndexed { index, label -> index to label },
            selected = settings.orientation.coerceIn(0, 3),
            onSelect = { v ->
                viewModel.updateSettings { it.copy(orientation = v) }
                MainActivityRuntime.instance?.applyEmulationOrientation()
            },
        )
        Spacer(Modifier.height(6.dp))
        // Where the picture sits in the window. In-game for the same reason as screen aspect:
        // you are looking at the thing you are moving, and on a handheld this is something you
        // adjust once you can see the touch controls over the game. Two rows because a handheld
        // Portrait only: landscape had the same control and it could never do anything, because
        // a 16:9 game in a 16:9 window has no letterbox bar to move into.
        HorizontalOptions(
            title = str("renderer.portraitPosition.label"),
            options = listOf(
                str("renderer.portraitPosition.top"), str("renderer.portraitPosition.center"),
            ).mapIndexed { index, label -> index to label },
            selected = if (settings.portraitRenderTop) 0 else 1,
            onSelect = { v -> viewModel.updateSettings { it.copy(portraitRenderTop = v == 0) } },
        )
        Spacer(Modifier.height(6.dp))
        // Cardboard VR, the Renderer tab's toggle and its three adjustments. In-game because the
        // lining up is done against the game: size, spacing and height are set by looking at the
        // picture through the viewer, and a controller reaches this menu without taking it off.
        MenuSwitchRow(str("renderer.cardboard.label"), settings.cardboardVr) { v ->
            viewModel.updateSettings { it.copy(cardboardVr = v) }
        }
        if (settings.cardboardVr) {
            Spacer(Modifier.height(6.dp))
            com.armsx2.ui.settings.IntSliderRow(
                label = str("renderer.cardboard.size.label"),
                value = settings.cardboardVrSize.coerceIn(20, 100),
                min = 20,
                max = 100,
                valueFormatter = { "$it%" },
                onReset = if (settings.cardboardVrSize != 80) ({ viewModel.updateSettings { it.copy(cardboardVrSize = 80) } }) else null,
                onChange = { v -> viewModel.updateSettings { it.copy(cardboardVrSize = v) } },
            )
            Spacer(Modifier.height(6.dp))
            com.armsx2.ui.settings.IntSliderRow(
                label = str("renderer.cardboard.spacing.label"),
                value = settings.cardboardVrSpacing.coerceIn(-100, 100),
                min = -100,
                max = 100,
                valueFormatter = { if (it > 0) "+$it" else "$it" },
                onReset = if (settings.cardboardVrSpacing != 0) ({ viewModel.updateSettings { it.copy(cardboardVrSpacing = 0) } }) else null,
                onChange = { v -> viewModel.updateSettings { it.copy(cardboardVrSpacing = v) } },
            )
            Spacer(Modifier.height(6.dp))
            com.armsx2.ui.settings.IntSliderRow(
                label = str("renderer.cardboard.vertical.label"),
                value = settings.cardboardVrVertical.coerceIn(-100, 100),
                min = -100,
                max = 100,
                valueFormatter = { if (it > 0) "+$it" else "$it" },
                onReset = if (settings.cardboardVrVertical != 0) ({ viewModel.updateSettings { it.copy(cardboardVrVertical = 0) } }) else null,
                onChange = { v -> viewModel.updateSettings { it.copy(cardboardVrVertical = v) } },
            )
        }
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
        // Mid-session because it is the one RSX lever whose cost is game-specific: the shader
        // path only engages on blend states the fixed-function unit cannot express, so whether
        // it costs anything at all depends on what is on screen right now.
        HorizontalOptions(
            title = str("renderer.blendingMode.label"),
            options = listOf(
                str("renderer.blendingMode.auto"), str("renderer.blendingMode.shader"),
                str("renderer.blendingMode.hardware"),
            ).mapIndexed { index, label -> index to label },
            selected = settings.ps3.blendingMode,
            onSelect = { v -> viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(blendingMode = v)) } },
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
            // SGSR reaches 200: Qualcomm's edge_sharpness is 0..2 with 1.0 as their default, so
            // 100 is that default and 200 the widened top end. It is a separate stored value
            // because FSR's is natively clamped to 100 and cannot express the range.
            val sgsr = settings.casMode >= 3
            com.armsx2.ui.settings.IntSliderRow(
                label = str(if (sgsr) "renderer.cas.sharpness.sgsr" else "renderer.cas.sharpness.fsr"),
                value = if (sgsr) settings.sgsrSharpness.coerceIn(0, 200) else settings.casSharpness.coerceIn(0, 100),
                min = 0,
                max = if (sgsr) 200 else 100,
                valueFormatter = { "$it%" },
                onChange = { v ->
                    viewModel.updateSettings {
                        if (sgsr) it.copy(sgsrSharpness = v) else it.copy(casSharpness = v)
                    }
                },
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
        // Shader Mode, Relaxed ZCULL Sync and Read Color Buffers are read when the game boots; the
        // core keeps a change to them for the next boot (issue #228). This applies them now.
        Spacer(Modifier.height(6.dp))
        CompactAction(str("backend.applyRestart"), "↻", Modifier.fillMaxWidth(), MainActivityRuntime::restart)
        Spacer(Modifier.height(6.dp))
        // GPU Turbo in-game: the whole point is A/B-ing it against a scene that is actually
        // stuttering, which is impossible if you have to quit to Settings to flip it. Applied
        // live, not just at the next renderer start.
        MenuSwitchRow(str("renderer.gpuTurbo.label"), settings.ps3.gpuTurbo) { v ->
            viewModel.updateSettings { it.copy(ps3 = it.ps3.copy(gpuTurbo = v)) }
            runCatching { com.armsx3.core.ARMSX3.instance.setGpuTurbo(v) }
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
        scale = settings.shaderChainScale,
        onScaleChange = { percent -> viewModel.updateSettings { it.copy(shaderChainScale = percent) } },
    )
    com.armsx2.ui.common.ShaderManagerSection()
}

@Composable
private fun PerformancePane(state: EmulationMenuUiState, viewModel: EmulationMenuViewModel) {
    val settings = state.settings
    SectionCard(str("perf.speedLimit.label")) {
        if (com.armsx2.ui.xmb.LocalXmbCollector.current != null) {
            MenuSwitchRow(str("perf.speedLimit.label"), settings.frameLimitEnable) { enabled ->
                viewModel.updateSettings { it.copy(frameLimitEnable = enabled) }
            }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (settings.frameLimitEnable) "${settings.ps3.clocksScale}%" else str("setup.toggle.off"),
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
        // Emulated Clock Speed, the same setting as the row in PS3 CPU below. The core takes it
        // at boot only (a running game's clock would jump), so it comes with Apply & Restart.
        HorizontalOptionRow(
            options = listOf(50, 75, 90, 100, 110, 125, 150, 200).map { it to "$it%" },
            selected = settings.ps3.clocksScale,
            keyPrefix = str("perf.speedLimit.label"),
            onSelect = viewModel::setSpeed,
        )
        Spacer(Modifier.height(8.dp))
        CompactAction(str("backend.applyRestart"), "↻", Modifier.fillMaxWidth(), MainActivityRuntime::restart)
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
    // how fast the emulator runs. ZeroFG's card is in every build; the experimental one only in the github build.
    ZeroFgCard(settings, viewModel)
    FrameGenCard(settings, viewModel)
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
        // Everything in this card but Preferred SPU Threads is read when the game boots, and the
        // core keeps a change to it for the next boot rather than switching a running game half
        // over (issue #228). This restarts the game to apply it.
        Spacer(Modifier.height(8.dp))
        CompactAction(str("backend.applyRestart"), "↻", Modifier.fillMaxWidth(), MainActivityRuntime::restart)
    }
    // The only way to produce an .rrc on Android. Every graphics bug reported from a phone
    // used to arrive without one, which is the file upstream asks for first and the only
    // thing that shows which surface actually went wrong.
    SectionCard(str("capture.title")) {
        var armed by remember { mutableStateOf(false) }
        MenuButtonRow(
            title = str("capture.rsx"),
            description = if (armed) str("capture.rsx.armed") else str("capture.rsx.detail"),
            glyph = if (armed) "\u25cf" else "\u2b1a",
        ) {
            armed = true
            Rpcs3Bridge.captureFrame()
        }
        Spacer(Modifier.height(6.dp))
        // The frame as pictures, pass by pass if asked (Settings, Advanced, Developer tools).
        // 0 = not yet, 1 = armed, 2 = the renderer cannot dump frames.
        var dumpState by remember { mutableStateOf(0) }
        MenuButtonRow(
            title = str("capture.frameDump"),
            description = when (dumpState) {
                1 -> str("capture.frameDump.armed")
                2 -> str("capture.frameDump.unsupported")
                else -> str("capture.frameDump.detail")
            },
            glyph = if (dumpState == 1) "●" else "▦",
        ) {
            dumpState = if (Rpcs3Bridge.dumpFrame(com.armsx2.DevTools.frameDumpMode)) 1 else 2
        }
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
    // Stick travel, the Controls tab's slider. In-game because the right value is found by walking
    // and aiming with it; it applies to the next thumb movement, and it is Compose state, so the row
    // follows a change made anywhere else.
    com.armsx2.ui.settings.IntSliderRow(
        label = str("pad.stickTravel.label"),
        value = TouchControls.stickTravelPercent.intValue,
        min = TouchControls.STICK_TRAVEL_MIN,
        max = TouchControls.STICK_TRAVEL_MAX,
        description = str("pad.stickTravel.description"),
        valueFormatter = { "${it}%" },
        onReset = if (TouchControls.stickTravelPercent.intValue != 100) ({ TouchControls.setStickTravelPercent(100) }) else null,
        onChange = { TouchControls.setStickTravelPercent(it) },
    )
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
    // Player 1 only here. This is the port an instrument game actually checks, and a picker per
    // port would swamp the pane; the other six live in the Pad tab. In-game because a game that
    // rejects a standard pad does it at its title screen, which is exactly where you are when
    // you need this, and the core rebuilds its pads so it takes effect without a reboot.
    HorizontalOptions(
        title = str("pad.deviceClass.label"),
        options = com.armsx2.PadDeviceClass.LABEL_KEYS
            .mapIndexed { index, key -> index to str(key) },
        selected = com.armsx2.PadDeviceClass.get(0),
        onSelect = { com.armsx2.PadDeviceClass.set(0, it) },
    )
    Spacer(Modifier.height(6.dp))
    com.armsx2.ui.xmb.LocalXmbCollector.current?.let { c ->
        com.armsx2.ui.xmb.XmbCollected(c, com.armsx2.ui.xmb.xmbControllerMappingNode())
    } ?: CompactAction(str("pad.controllerMapping"), "⌁", Modifier.fillMaxWidth(), viewModel::openControlsManager)
    Spacer(Modifier.height(6.dp))
    CompactAction(str("pad.editTouchLayout"), "✥", Modifier.fillMaxWidth(), viewModel::editTouchControls)
    Spacer(Modifier.height(6.dp))
    // Sits with the touch layout because it's the same job: what the on-screen pad LOOKS
    // like, right after where it's laid out. Full-screen like Controller mapping.
    com.armsx2.ui.xmb.LocalXmbCollector.current?.let { c ->
        com.armsx2.ui.xmb.XmbCollected(c, com.armsx2.ui.xmb.xmbTabFolder("ingame.skins", str("tab.skins"), "tex_thema") {
            com.armsx2.ui.settings.SkinsTab(com.armsx2.ui.InGameOverlay.settingsState)
        })
    } ?: CompactAction(str("tab.skins"), "◈", Modifier.fillMaxWidth(), viewModel::openSkins)
    Spacer(Modifier.height(6.dp))
    // Hotkeys, under Skins: All Settings' Hotkeys page, opened straight over the game.
    com.armsx2.ui.xmb.LocalXmbCollector.current?.let { c ->
        com.armsx2.ui.xmb.XmbCollected(c, com.armsx2.ui.xmb.xmbTabFolder("ingame.hotkeys", str("tab.hotkeys"), "tex_remote") {
            com.armsx2.ui.settings.HotkeysTab(com.armsx2.ui.InGameOverlay.settingsState)
        })
    } ?: CompactAction(str("tab.hotkeys"), "⌘", Modifier.fillMaxWidth(), viewModel::openHotkeys)
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
    // The in-game XMB has its own Settings column and Core Settings, and no screen to open over it.
    val xmb = com.armsx2.ui.xmb.LocalXmbCollector.current != null
    if (!xmb) CompactAction(str("action.allSettings"), "⚙", Modifier.fillMaxWidth(), viewModel::openFullSettings)
    Spacer(Modifier.height(6.dp))
    // ...and the whole core config beneath it, scoped to this game. The rows in this pane are
    // the handful of RPCS3 switches worth flipping mid-session; everything else lived behind
    // the library drawer, where it could only be set globally.
    // Glyph is one already proven to render in the shipped font: "▩" (U+25A9) and "⏻" (U+23FB)
    // come out as tofu boxes on device, while "▣" is used by the BIOS/onboarding screens.
    if (!xmb) CompactAction(str("core.settings.title"), "▣", Modifier.fillMaxWidth(), viewModel::openCoreSettings)
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
    if (!xmb) com.armsx2.ui.patches.Ps3PatchesTab(
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
    // Strict Rendering, Multithreaded RSX, the color and depth buffer switches and HLE lwmutex are
    // read when the game boots; the core keeps a change to them for the next boot (issue #228).
    Spacer(Modifier.height(6.dp))
    CompactAction(str("backend.applyRestart"), "↻", Modifier.fillMaxWidth(), MainActivityRuntime::restart)
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
    // Stable across languages and orderings: the saved tab order and the pad's ids use it.
    val key: String = title,
    val action: () -> Unit,
)

@Composable
// [indexOffset]: where this run starts among all the tab's actions, the index selectedAction counts in.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ActionGrid(actions: List<MenuAction>, selected: Int, onSelect: (Int) -> Unit, indexOffset: Int = 0) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEachIndexed { i, item -> androidx.compose.runtime.key(item.key) {
            val index = indexOffset + i
            val active = index == selected
            val run = { onSelect(index); item.action() }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    // By key, not position: the player can reorder this tab.
                    .controllerFocusable("pause.action.${item.key}", onConfirm = run)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = run, onLongClick = { SessionOrder.startArranging() }),
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
        } }
    }
}

/**
 * The RPCS3 compatibility database entries for the running title, each one switchable.
 *
 * The database is downloaded per title and applied by the core over the top of your own config,
 * so a title it pins to the PPU interpreter (108 of the database's 2195 are) runs on the
 * interpreter no matter what the decoder row in All Settings says. Switching an entry off here
 * withholds that key from the core and hands the setting back to you. A game with no database
 * entry renders nothing at all.
 */
@Composable
private fun DatabaseSection() {
    val serial = com.armsx2.ui.InGameOverlay.currentSerial.value?.takeIf { it.isNotBlank() } ?: return
    val entries = remember(serial) { com.armsx2.config.ConfigDatabase.entriesFor(serial) }
    if (entries.isEmpty()) return

    // A key can be withheld from the core two ways and only one of them is this card's switch:
    // the core also skips any key you have set for this game yourself, so a row could read "on"
    // while the core kept skipping it and nothing on screen said why. Fold both into one switch.
    // Includes the frame-limit nodes while a Display FPS Cap is set: the core skips those too.
    fun ownValues() = com.armsx2.config.ConfigDatabase.ownedKeys(serial)

    var ignored by remember(serial) {
        mutableStateOf(com.armsx2.config.ConfigDatabase.ignoredFor(serial))
    }
    var yours by remember(serial) { mutableStateOf(ownValues()) }

    SectionCard(str("perf.configDb.title")) {
        Text(
            str("perf.configDb.help"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        for ((path, value) in entries) {
            val withheld = path in ignored || path in yours
            // The section name is part of the identity, not decoration: "Core@@PPU Decoder" and a
            // video key of the same name would otherwise read as one row.
            MenuSwitchRow(
                title = "${path.replace("@@", " / ")}: $value",
                checked = !withheld,
                description = when {
                    path in yours -> str("perf.configDb.yours")
                    withheld -> str("perf.configDb.off")
                    else -> null
                },
            ) { applyThis ->
                com.armsx2.config.ConfigDatabase.setIgnored(serial, path, !applyThis)

                // On means "use the database value", which cannot be true while your own value
                // for the same key is still on file: the core gives yours priority.
                if (applyThis) {
                    com.armsx2.config.CoreSettingOverrides
                        .forget(com.armsx2.config.SettingsScope.Game, serial, path)
                }

                ignored = com.armsx2.config.ConfigDatabase.ignoredFor(serial)
                yours = ownValues()
                // Rewrite the file the core reads now, rather than waiting for the next time
                // something happens to save settings for this title.
                com.armsx2.config.ConfigDatabase.writeUserKeys(serial, yours)
            }
        }
    }
}

/**
 * Change Disc and Swap Disc, the two ways to put another disc in, opened from their actions above.
 *
 * Change Disc works for every game, and is what a disc change means on a console for nearly all of
 * them: the game closes and the new disc starts. Swap Disc is the real thing, a disc swapped under a
 * game that keeps running. Only a game that registered disc-change callbacks can take that (SingStar,
 * the Dynasty Warriors Xtreme Legends games), so its action only exists while one is running.
 *
 * Both list the discs of the playlist the game was booted from, then offer the file picker. Picking
 * closes the menu: a swap needs the game running to let go of the old disc.
 */
@Composable
private fun DiscSection(viewModel: EmulationMenuViewModel, swap: Boolean) {
    val playlist = remember { com.armsx3.Rpcs3Bridge.discPlaylist() }

    SectionCard(str(if (swap) "action.swapDisc" else "action.changeDisc")) {
        Text(
            str(if (swap) "disc.swap.note" else "disc.change.note"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            playlist.discs.forEachIndexed { index, disc ->
                val current = disc == playlist.current
                MenuButtonRow(
                    title = disc.substringAfterLast('/'),
                    description = if (current) str("disc.swap.inDrive") else str("disc.swap.discN").format(index + 1),
                    glyph = if (current) "●" else "⏏",
                ) {
                    if (!current) {
                        viewModel.resume()
                        if (swap) MainActivityRuntime.swapDiscTo(disc) else MainActivityRuntime.bootInstead(disc)
                    }
                }
            }
            MenuButtonRow(
                title = str("disc.swap.pickFile"),
                description = str("disc.swap.pickFile.desc"),
                glyph = "…",
            ) { MainActivityRuntime.promptDiscFile(swap) }
        }
    }
}

/** The first tab as a plain list of its rows, for putting them in order: drag a row (hold it,
 *  or grab its handle), or with a controller pick one up with A, move it and put it down with A.
 *  B puts a picked-up row back, and otherwise closes the view, keeping the order. */
@Composable
private fun SessionArrangeView(order: List<String>, labels: Map<String, Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(str("menu.arrange.title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            str("menu.arrange.hint"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactAction(str("common.done"), "✓", Modifier.weight(1f)) {
                val first = order.firstOrNull()
                SessionOrder.finishArranging()
                if (first != null) com.armsx2.ui.settings.SettingsControllerNav.focusWhenRegistered(null, "pause.action.$first")
            }
            CompactAction(str("menu.arrange.reset"), "↺", Modifier.weight(1f)) { SessionOrder.resetOrder() }
        }
        order.forEach { key ->
            androidx.compose.runtime.key(key) {
                val (glyph, title) = labels[key] ?: ("•" to key)
                ArrangeRow(key, glyph, title)
            }
        }
    }
}

@Composable
private fun ArrangeRow(key: String, glyph: String, title: String) {
    val lifted = SessionOrder.lifted == key
    val id = "pause.arrange.$key"
    val selected = com.armsx2.ui.settings.SettingsControllerNav.isSelected(id)
    val scroll = com.armsx2.ui.settings.LocalSettingsScrollState.current
    val scope = rememberCoroutineScope()
    val bring = remember { BringIntoViewRequester() }
    // A row moved with the controller stays in sight as it goes past the fold. Not a dragged one:
    // scrolling the list under the finger would move the row again, and again.
    val index = SessionOrder.indexOf(key)
    LaunchedEffect(lifted, index) { if (lifted && SessionOrder.movedByPad) runCatching { bring.bringIntoView() } }

    // The row's top in root coordinates, unclipped: boundsInRoot is cut to the visible part of the
    // list, so a row partly scrolled out reported the wrong top and a drag on it jumped.
    val rowTop = remember { FloatArray(1) }
    val drag = Modifier.pointerInput(key) {
        val handleWidth = 52.dp.toPx()
        val edge = 44.dp.toPx()
        val stepPx = 7.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // One gesture per touch. On the handle the row moves as soon as it is dragged; on the rest
            // of the row only after a hold, so a plain drag there still scrolls the list.
            val start = (
                if (down.position.x >= size.width - handleWidth) {
                    awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                } else {
                    awaitLongPressOrCancellation(down.id)
                }
                ) ?: return@awaitEachGesture
            var fingerY = rowTop[0] + start.position.y
            fun place() {
                val others = SessionOrder.arrangedKeys().filter { it != key }
                val target = others.count { k -> (SessionOrder.rowBounds[k]?.center?.y ?: Float.MAX_VALUE) < fingerY }
                SessionOrder.moveTo(key, target)
            }
            SessionOrder.lift(key)
            // Near the top or bottom of the visible part of the tab, scroll it, so a row can be taken
            // past the fold.
            val edgeScroll = scope.launch(Dispatchers.Main) {
                while (true) {
                    val view = SessionOrder.viewport
                    if (scroll != null && view != null) {
                        val step = when {
                            fingerY < view.top + edge -> -stepPx
                            fingerY > view.bottom - edge -> stepPx
                            else -> 0f
                        }
                        if (step != 0f && scroll.dispatchRawDelta(step) != 0f) place()
                    }
                    delay(16)
                }
            }
            try {
                place()
                drag(start.id) { change ->
                    change.consume()
                    fingerY = rowTop[0] + change.position.y
                    place()
                }
            } finally {
                edgeScroll.cancel()
                SessionOrder.drop()
            }
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .onGloballyPositioned {
                val p = it.positionInRoot()
                rowTop[0] = p.y
                SessionOrder.rowBounds[key] = androidx.compose.ui.geometry.Rect(p, it.size.toSize())
            }
            .controllerFocusable(id, onConfirm = {
                if (SessionOrder.lifted == key) SessionOrder.drop() else SessionOrder.lift(key)
            })
            .then(drag),
        shape = RoundedCornerShape(16.dp),
        color = when {
            lifted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
        },
        border = BorderStroke(
            if (lifted) 2.dp else 1.dp,
            if (lifted || selected) MaterialTheme.colorScheme.primary.copy(alpha = if (lifted) 1f else 0.72f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.34f),
        ),
        shadowElevation = if (lifted) 10.dp else 0.dp,
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                glyph,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(30.dp),
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // The handle: the row's drag above treats a touch on this end as a grab at once.
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Text("≡", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun SectionCard(
    title: String,
    // Holding the title, where the card can be moved (the first tab's cards).
    onTitleLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // In the in-game XMB (a pane composed for its lists, see XmbPauseTab): a list of its own.
    com.armsx2.ui.xmb.LocalXmbCollector.current?.let { c ->
        com.armsx2.ui.xmb.XmbCollectedSection(c, "p${androidx.compose.runtime.currentCompositeKeyHash}", title) { Column { content() } }
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
    ) {
        Column(Modifier.padding(13.dp)) {
            val latestLongPress by androidx.compose.runtime.rememberUpdatedState(onTitleLongPress)
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = if (onTitleLongPress == null) Modifier
                else Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectTapGestures(onLongPress = { latestLongPress?.invoke() })
                },
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun <T> HorizontalOptions(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    // In the XMB a choice is one setting, not a list holding one.
    com.armsx2.ui.xmb.LocalXmbCollector.current?.let { c ->
        xmbPauseChoice(c, title, options, selected, onSelect)
        return
    }
    SectionCard(title) {
        HorizontalOptionRow(options, selected, keyPrefix = title, onSelect = onSelect)
    }
}

/** A row of chips in the in-game XMB: a setting whose choices are the chips. */
@Composable
private fun <T> xmbPauseChoice(
    c: com.armsx2.ui.xmb.XmbCollector,
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val labels = options.map { it.second }
    val index = options.indexOfFirst { it.first == selected }
    com.armsx2.ui.xmb.XmbCollected(
        c,
        com.armsx2.ui.xmb.XmbNode.Setting(
            key = "p${androidx.compose.runtime.currentCompositeKeyHash}",
            title = title,
            icon = c.icon,
            editor = com.armsx2.ui.xmb.XmbEditor.Choice(labels, { index.coerceAtLeast(0) }) { i -> options.getOrNull(i)?.let { onSelect(it.first) } },
            // A value the chips do not offer (one set in Settings) reads as itself.
            value = { labels.getOrNull(index) ?: selected.toString() },
        ),
    )
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
    com.armsx2.ui.xmb.LocalXmbCollector.current?.let { c ->
        xmbPauseChoice(c, keyPrefix, options, selected, onSelect)
        return
    }
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
internal fun MenuSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    com.armsx2.ui.xmb.LocalXmbCollector.current?.let { c ->
        if (!enabled) return
        com.armsx2.ui.xmb.XmbCollected(
            c,
            com.armsx2.ui.xmb.XmbNode.Setting(
                key = "p${androidx.compose.runtime.currentCompositeKeyHash}",
                title = title,
                icon = c.icon,
                editor = com.armsx2.ui.xmb.XmbEditor.Toggle({ checked }, onCheckedChange),
                value = { if (checked) c.on else c.off },
                description = description,
            ),
        )
        return
    }
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

/** A row that just does something when you tap it. MenuSwitchRow and MenuCycleRow both carry
 *  a value; this one is for a one-shot action, and matches their shape so the card reads evenly. */
@Composable
private fun MenuButtonRow(
    title: String,
    description: String,
    glyph: String,
    onClick: () -> Unit,
) {
    com.armsx2.ui.xmb.LocalXmbCollector.current?.let { c ->
        com.armsx2.ui.xmb.XmbCollected(
            c,
            com.armsx2.ui.xmb.XmbNode.Action("p${androidx.compose.runtime.currentCompositeKeyHash}", title, c.icon, description) { onClick() },
        )
        return
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .controllerFocusable("pause.button.$title", onConfirm = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(glyph, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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
    // In the XMB: an item showing the value, which X steps on.
    com.armsx2.ui.xmb.LocalXmbCollector.current?.let { c ->
        com.armsx2.ui.xmb.XmbCollected(
            c,
            com.armsx2.ui.xmb.XmbNode.Action("p${androidx.compose.runtime.currentCompositeKeyHash}", title, c.icon, valueLabel) { onStep(1) },
        )
        return
    }
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
internal fun CompactAction(title: String, glyph: String, modifier: Modifier, onClick: () -> Unit) {
    com.armsx2.ui.xmb.LocalXmbCollector.current?.let { c ->
        com.armsx2.ui.xmb.XmbCollected(c, com.armsx2.ui.xmb.XmbNode.Action("p${androidx.compose.runtime.currentCompositeKeyHash}", title, c.icon) { onClick() })
        return
    }
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

/**
 * A pause menu tab for the in-game XMB: the same pane, composed out of sight with an XmbCollector
 * set, so its rows become the XMB's lists and change the same settings the same way.
 */
@Composable
internal fun XmbPauseTab(tab: EmulationMenuTab) {
    val viewModel: EmulationMenuViewModel = viewModel()
    // The pane reads the menu's snapshot of the settings, which the pause menu takes as it opens.
    LaunchedEffect(Unit) { viewModel.load(null) }
    val state = viewModel.state.value
    when (tab) {
        EmulationMenuTab.Graphics -> GraphicsPane(state, viewModel)
        EmulationMenuTab.Performance -> PerformancePane(state, viewModel)
        EmulationMenuTab.Controls -> ControlsPane(state, viewModel)
        EmulationMenuTab.Options -> OptionsPane(state, viewModel)
        EmulationMenuTab.Fixes -> {
            DatabaseSection()
            com.armsx2.ui.settings.FixesTab(InGameOverlay.settingsState)
            // The same Apply & Restart the pause menu's Fixes tab ends with (issue #228).
            CompactAction(str("backend.applyRestart"), "↻", Modifier.fillMaxWidth(), MainActivityRuntime::restart)
        }
        else -> Unit
    }
}
