package com.armsx2.ui.emulation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.MenuSfx
import com.armsx2.i18n.str

/**
 * Why the game stopped, when one of its threads dies of a fatal error.
 *
 * The game then stops where it was, and on screen that is a freeze with nothing said: a tester whose
 * every game "got stuck at SPU compile" had a network thread killed for wanting RPCN, which only the
 * log showed. The reason RPCS3 gives is in English, so a line of our own says what to do about it,
 * for the causes there is something to do about.
 *
 * Polled with the RPCN invites while a game runs (MainActivityRuntime.onStart), and up until the
 * game is stopped or the banner is tapped away.
 */
object GameErrorNotice {
    /** The reason, its first line only, or null. */
    val reason = mutableStateOf<String?>(null)

    /** With what the core gave (Rpcs3Bridge.takeGameError). On the main thread. */
    fun show(error: String) {
        val line = error.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return
        if (reason.value == null) MenuSfx.play(MenuSfx.Event.POPUP_OPEN)
        reason.value = line
    }

    fun clear() {
        reason.value = null
    }
}

@Composable
fun GameErrorBanner() {
    val reason by GameErrorNotice.reason

    AnimatedVisibility(
        visible = reason != null,
        enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { -it },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it },
    ) {
        // Held separately so the exit animation still has something to draw after the state clears
        val shown = remember(reason) { reason }.orEmpty()
        val what = if ("RPCN" in shown) str("game.error.rpcn") else str("game.error.other")

        Surface(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .widthIn(max = 560.dp)
                .clickable { GameErrorNotice.clear() },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.8f)),
            shadowElevation = 14.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    str("game.error.title"),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(what, style = MaterialTheme.typography.bodyMedium)
                Text(shown, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
