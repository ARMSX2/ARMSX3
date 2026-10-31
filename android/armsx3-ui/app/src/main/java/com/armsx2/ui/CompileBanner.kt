package com.armsx2.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.EmuState
import com.armsx2.i18n.I18n
import com.armsx2.runtime.MainActivityRuntime
import kotlinx.coroutines.delay
import com.armsx3.core.ARMSX3

/**
 * "Compiling PPU modules" while a game compiles PPU code during play.
 *
 * The boot-time compile has its own progress popup, but code a game loads later only had the
 * core's small corner notice, which on a loading screen reads as a hang. Castlevania: Harmony of
 * Despair compiles its DLC modules after a save is loaded, 61 of them in 15 minutes on the first
 * load. The core bridges the gaps between such modules (_armsx3_getPlayCompileState), so this
 * stays up for the whole stretch instead of flashing once per module.
 *
 * The core reports a stretch only once compiling has gone on for ten seconds without a break, the
 * kind that holds the game up; short compiles during play never show it. Polled twice a second,
 * only while a game is running; the app pauses the game when it goes to the background, which
 * stops the polling too.
 */
@Composable
fun CompileBannerOverlay(scope: BoxScope) {
    val running = MainActivityRuntime.eState.value == EmuState.RUNNING
    var state by remember { mutableIntStateOf(0) }

    LaunchedEffect(running) {
        state = 0
        if (!running) return@LaunchedEffect

        while (true) {
            delay(500)
            state = runCatching { ARMSX3.instance.getPlayCompileState() }.getOrDefault(0)
        }
    }

    // 0 = nothing to show. Otherwise the low 16 bits are 1 + the modules done, and bits 16 to 30
    // how many there are when the core knows (_armsx3_getPlayCompileState).
    if (state <= 0) return
    val done = (state and 0xffff) - 1
    val total = (state ushr 16) and 0x7fff

    with(scope) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 12.dp)
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = I18n.get("ingame.compiling.title"),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (total > 0) {
                        I18n.get("ingame.compiling.detailOf").format(done, total)
                    } else {
                        I18n.get("ingame.compiling.detail").format(done)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
