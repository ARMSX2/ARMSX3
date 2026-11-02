package com.armsx2.ui.xmb

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.ui.WelcomeBanner
import com.armsx2.ui.emulation.GameErrorNotice
import kotlinx.coroutines.delay

/**
 * The app's notices in XMB mode, as the console shows its own: glass boxes in the top right, under
 * the clock's bar, an icon and the words in the system font, one under another, each coming and
 * going by itself. Hosted once over every screen (WindowImpl). Each notice's own banner draws
 * nothing in XMB mode but keeps its clock, so a notice stays as long here as it would there.
 *
 * - [WelcomeBanner]: "Welcome Back!" on waking, with the player's RPCN avatar, as the console's
 *   sign-in notice shows its user's (one never chosen is the firmware's default); the battery
 *   warnings and other brief notes with ARMSX3's mark.
 * - Compiling during play (CompileBanner), with its progress.
 * - A Discord friend coming online, with their picture, as the console says a friend signed in.
 * - An RPCN invite for the running game, with the console's invitation icon.
 * - Remote play: a friend joining or leaving.
 */
@Composable
fun XmbNotices() {
    val font = rememberXmbFont()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        // The XMB's own scale: dp per 720p unit (XmbView).
        val u = if (w >= h) minOf(w / 1280f, h / 720f) else w / 900f
        val m = remember(u, w, h) { Metrics(u, w, h) }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy((8f * u).dp),
            // Under the clock's bar (50 to 87), the right edge in from the screen's.
            modifier = Modifier.align(Alignment.TopEnd).padding(top = (100f * u).dp, end = (48f * u).dp),
        ) {
            WelcomeNote(u, m, font)
            CompileNote(u, m, font)
            FriendOnlineNote(u, m, font)
            InviteNote(u, m, font)
            RemotePlayNote(u, m, font)
        }
    }
}

/** One notice: the console's glass box, [shown] of it, with [content] in a row. */
@Composable
private fun XmbNoticeBox(u: Float, shown: Float, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape((6f * u).dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = (560f * u).dp)
            .graphicsLayer { alpha = shown }
            .background(Brush.verticalGradient(listOf(Color(0xE0303036), Color(0xE0141418))), shape)
            .border(1.dp, Color.White.copy(alpha = 0.3f), shape)
            .padding(horizontal = (14f * u).dp, vertical = (10f * u).dp),
        content = content,
    )
}

/** [value] while it is set and through its fade after, and how much of it shows. */
@Composable
private fun <T : Any> fading(value: T?): Pair<T?, Float> {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    val shown by animateFloatAsState(
        if (value != null) 1f else 0f,
        tween(if (value != null) 250 else 450),
        label = "xmbNotice",
    )
    @Suppress("UNCHECKED_CAST")
    val held = last[0] as T?
    return (if (value != null || shown > 0f) held else null) to shown
}

@Composable
private fun rememberMark(): ImageBitmap = remember { XmbWordmark.markBadge(128).asImageBitmap() }

/** A picture beside the words: a player's, framed as the console frames its user's. */
private fun picture(u: Float) = Modifier
    .size((40f * u).dp)
    .clip(RoundedCornerShape((4f * u).dp))
    .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape((4f * u).dp))

@Composable
private fun WelcomeNote(u: Float, m: Metrics, font: FontFamily) {
    val token = WelcomeBanner.token.intValue
    LaunchedEffect(token) {
        if (WelcomeBanner.text.value == null) return@LaunchedEffect
        delay(4250)
        WelcomeBanner.text.value = null
    }
    val (msg, shown) = fading(WelcomeBanner.text.value)
    msg ?: return
    val avatar = if (WelcomeBanner.welcome.value) com.armsx2.ui.settings.rememberCurrentAvatar() else null
    val mark = rememberMark()
    XmbNoticeBox(u, shown) {
        if (avatar != null) {
            Image(avatar, contentDescription = null, modifier = picture(u))
        } else {
            Image(mark, contentDescription = null, modifier = Modifier.size((40f * u).dp))
        }
        Spacer(Modifier.width((14f * u).dp))
        Text(msg, style = xmbText(font, m.labelSp * 1.2f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompileNote(u: Float, m: Metrics, font: FontFamily) {
    val (state, shown) = fading(com.armsx2.ui.PlayCompile.state.intValue.takeIf { it > 0 })
    state ?: return
    // As the core reports it (_armsx3_getPlayCompileState): 1 + the modules done, and how many
    // there are when it knows.
    val done = (state and 0xffff) - 1
    val total = (state ushr 16) and 0x7fff
    val mark = rememberMark()
    XmbNoticeBox(u, shown) {
        Image(mark, contentDescription = null, modifier = Modifier.size((40f * u).dp))
        Spacer(Modifier.width((14f * u).dp))
        Column {
            Text(I18n.get("ingame.compiling.title"), style = xmbText(font, m.labelSp * 1.2f), maxLines = 1)
            if (total > 0) {
                // The bar the XMB's own progress uses: white over a faint track.
                Box(
                    Modifier
                        .padding(vertical = (6f * u).dp)
                        .width((300f * u).dp)
                        .height((5f * u).dp)
                        .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(50)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((done.toFloat() / total).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color.White, RoundedCornerShape(50)),
                    )
                }
            }
            Text(
                if (total > 0) I18n.get("ingame.compiling.detailOf").format(done, total)
                else I18n.get("ingame.compiling.detail").format(done),
                style = xmbText(font, m.subtitleSp * 1.05f, alpha = 0.8f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FriendOnlineNote(u: Float, m: Metrics, font: FontFamily) {
    val (friend, shown) = fading(com.armsx2.DiscordPresence.justOnline.value)
    friend ?: return
    val nowOnline = str("friends.nowOnline")
    XmbNoticeBox(u, shown) {
        if (friend.avatarUrl.isNotBlank()) {
            AsyncImage(model = friend.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = picture(u))
        } else {
            XmbIconView(XmbIcon("explore_category_friend", "tex_Avatar_Default", "🙂"), (40f * u).dp)
        }
        Spacer(Modifier.width((14f * u).dp))
        Text("${friend.name} $nowOnline", style = xmbText(font, m.labelSp * 1.2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InviteNote(u: Float, m: Metrics, font: FontFamily) {
    val (from, shown) = fading(com.armsx2.ui.friends.RpcnInviteNotice.from.value)
    from ?: return
    val invited = str("rpcn.invite.banner").format(from)
    val howTo = str("xmb.invite.howTo")
    XmbNoticeBox(u, shown) {
        XmbIconView(XmbIcon("explore_category_friend", "item_tex_Invitation", "✉"), (40f * u).dp)
        Spacer(Modifier.width((14f * u).dp))
        Column {
            Text(invited, style = xmbText(font, m.labelSp * 1.2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(howTo, style = xmbText(font, m.subtitleSp * 1.05f, alpha = 0.8f))
        }
    }
}

@Composable
private fun RemotePlayNote(u: Float, m: Metrics, font: FontFamily) {
    val (notice, shown) = fading(com.armsx2.remoteplay.RemotePlayHost.notice.value)
    notice ?: return
    val text = str(if (notice.joined) "remoteplay.notice.joined" else "remoteplay.notice.left").format(notice.name)
    XmbNoticeBox(u, shown) {
        XmbIconView(XmbIcon("xmb_ingame", "tex_friend", "👥"), (40f * u).dp)
        Spacer(Modifier.width((14f * u).dp))
        Text(text, style = xmbText(font, m.labelSp * 1.2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Why the game stopped ([GameErrorNotice]), in XMB mode: the console's error dialog, with its error
 * sound, in the XMB on screen. A stopped game with no XMB up gets the in-game XMB opened over it,
 * where it is closed anyway. OK clears it, as tapping the library's banner does. False when no XMB
 * could take it (the Quick Menu's pause menu is up, or a panel is open), so the banner shows instead.
 */
@Composable
fun xmbGameError(reason: String?): Boolean {
    var missed by remember(reason) { mutableStateOf(false) }
    val title = str("game.error.title")
    val what = if (reason != null && "RPCN" in reason) str("game.error.rpcn") else str("game.error.other")
    val ok = fw("explore_plugin_full", "msg_ok", "xmb.ok")
    LaunchedEffect(reason) {
        if (reason == null) return@LaunchedEffect
        var c = XmbDialogs.target()
        if (c == null && !com.armsx2.ui.WindowImpl.overlayVisible.value) {
            com.armsx2.ui.InGameOverlay.open()
            // The in-game XMB takes the pad once it is composed, a frame or two from now.
            for (i in 0 until 30) {
                withFrameNanos {}
                c = XmbDialogs.target()
                if (c != null) break
            }
        }
        val target = c
        if (target == null) {
            missed = true
            return@LaunchedEffect
        }
        XmbSounds.play(XmbSounds.Sfx.ERROR)
        target.dialog = XmbController.Dialog(
            listOf(title, what, reason).joinToString("\n"),
            listOf(XmbOption(ok) { GameErrorNotice.clear() }),
            onDismiss = { GameErrorNotice.clear() },
        )
    }
    return !missed
}
