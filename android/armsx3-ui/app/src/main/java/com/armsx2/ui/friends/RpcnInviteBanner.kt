package com.armsx2.ui.friends

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.armsx2.MenuSfx
import com.armsx2.i18n.str
import com.armsx2.ui.settings.RpcnInvite

/**
 * "<name> invited you to play", over the game, when an RPCN invite for it arrives.
 *
 * The emulator's own notice is one line of OSD text in a corner for six seconds, with no sound,
 * and a friend sent two invites during a match without either being noticed. This is the size of
 * the other friend banners, stays longer, and says where to take it: there is no XMB here to pick
 * an invite from, so it is joined from Friends in the in-game menu.
 *
 * Only invites the running game can take (rpcnGetInvites), like that list. An invite for another
 * game still gets the OSD line, and nothing here could use it.
 */
object RpcnInviteNotice {
    /** The sender of the invite being shown, or null. */
    val from = mutableStateOf<String?>(null)

    private var listed: Set<Long> = emptySet()

    /** With what rpcnGetInvites lists now, polled while a game runs. On the main thread. Any id
     *  that was not in the last list is new, which also holds when a game's next executable starts
     *  a new client whose ids begin again. */
    internal fun update(invites: List<RpcnInvite>) {
        val arrived = invites.lastOrNull { it.id !in listed }
        listed = invites.mapTo(HashSet()) { it.id }

        if (arrived != null) {
            from.value = arrived.from
            MenuSfx.play(MenuSfx.Event.POPUP_OPEN)
        }
    }

    fun reset() {
        listed = emptySet()
    }
}

@Composable
fun RpcnInviteBanner() {
    val sender by RpcnInviteNotice.from

    // Keyed on the sender, so a second invite during the first restarts the clock
    LaunchedEffect(sender) {
        if (sender != null) {
            kotlinx.coroutines.delay(8000)
            RpcnInviteNotice.from.value = null
        }
    }

    AnimatedVisibility(
        visible = sender != null,
        enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { -it },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it },
    ) {
        // Held separately so the exit animation still has something to draw after the state clears
        val shown = remember(sender) { sender }
        Surface(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
            shadowElevation = 14.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    str("rpcn.invite.banner").format(shown.orEmpty()),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    str("rpcn.invite.banner.howTo"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
