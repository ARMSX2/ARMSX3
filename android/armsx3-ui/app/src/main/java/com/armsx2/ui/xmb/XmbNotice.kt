package com.armsx2.ui.xmb

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.armsx2.ui.WelcomeBanner
import kotlinx.coroutines.delay

/**
 * The app's brief notes ([WelcomeBanner]: "Welcome Back!" on waking, the battery warnings) in XMB
 * mode, as the console shows a notice: a dark box in the top right, under the clock, an icon and
 * the words in the system font, coming and going by itself. Waking shows the player's RPCN avatar,
 * as the console's sign-in notice shows its user's, which every player has: one they never chose
 * is the firmware's default. Anything else shows ARMSX3's mark.
 */
@Composable
fun XmbNotice() {
    val msg = WelcomeBanner.text.value ?: return
    val token = WelcomeBanner.token.intValue
    val welcome = WelcomeBanner.welcome.value
    val font = rememberXmbFont()
    val avatar = com.armsx2.ui.settings.rememberCurrentAvatar()
    val mark = remember { XmbWordmark.markBadge(128).asImageBitmap() }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(token) {
        shown.snapTo(0f)
        shown.animateTo(1f, tween(250))
        delay(4000)
        shown.animateTo(0f, tween(450))
        WelcomeBanner.text.value = null
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        // The XMB's own scale: dp per 720p unit (XmbView).
        val u = if (w >= h) minOf(w / 1280f, h / 720f) else w / 900f
        val m = remember(u, w, h) { Metrics(u, w, h) }
        val shape = RoundedCornerShape((6f * u).dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopEnd)
                // Under the clock's bar (50 to 87), its right edge in from the screen's.
                .padding(top = (100f * u).dp, end = (48f * u).dp)
                .widthIn(max = (520f * u).dp)
                .graphicsLayer { alpha = shown.value }
                .background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Black.copy(alpha = 0.66f))),
                    shape,
                )
                .border(1.dp, Color.White.copy(alpha = 0.3f), shape)
                .padding(horizontal = (14f * u).dp, vertical = (10f * u).dp),
        ) {
            val picture = Modifier
                .size((40f * u).dp)
                .clip(RoundedCornerShape((4f * u).dp))
                .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape((4f * u).dp))
            if (welcome && avatar != null) {
                Image(avatar, contentDescription = null, modifier = picture)
            } else {
                Image(mark, contentDescription = null, modifier = Modifier.size((40f * u).dp))
            }
            Spacer(Modifier.width((14f * u).dp))
            Text(msg, style = xmbText(font, m.labelSp * 1.2f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
