package com.armsx2.input

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures

/**
 * The keys the Android IME does not have, floated above it.
 *
 * The emulated keyboard works, but a soft keyboard is built for typing text and has no
 * arrows, no Escape and no function row -- so the first thing it was used for, a game's
 * debug menu, opened and then could not be navigated. Those keys are not optional extras
 * for that job; they are the whole interaction.
 *
 * This does not replace the IME. Prediction, swipe and non-Latin input all still come from
 * whichever keyboard the user has chosen, and this only adds what that keyboard cannot
 * express. It appears and disappears with [SoftKeyboard.visible], so there is nothing to
 * place in the touch layout and nothing to discover.
 *
 * Taps go through [SoftKeyboard.tap], the same paced queue the IME's own keys use, so a
 * press is held long enough for the guest to sample it (see KEY_STEP_MS -- a press and
 * release issued back to back can land entirely between two guest polls and be missed).
 *
 * Gestures rather than clickable(): this sits next to a focused IME sink, and anything
 * focusable here can take focus off it and drop the keyboard mid-use. detectTapGestures
 * never touches focus.
 */
private data class ExtraKey(val label: String, val code: Int, val wide: Boolean = false)

private val BASE_KEYS = listOf(
    ExtraKey("Esc", KeyEvent.KEYCODE_ESCAPE),
    ExtraKey("Tab", KeyEvent.KEYCODE_TAB),
    ExtraKey("←", KeyEvent.KEYCODE_DPAD_LEFT),
    ExtraKey("↑", KeyEvent.KEYCODE_DPAD_UP),
    ExtraKey("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    ExtraKey("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    // Space and Enter are on the IME too, but the IME is not always the thing that comes up --
    // and Space in particular is the key that opens the debug menu this was first used for, so
    // it should not depend on another keyboard appearing.
    ExtraKey("Space", KeyEvent.KEYCODE_SPACE, wide = true),
    ExtraKey("Enter", KeyEvent.KEYCODE_ENTER, wide = true),
)

// KEYCODE_F1..F12 are contiguous, the same way the handler's qt mapping assumes.
private val FN_KEYS = (0..11).map { ExtraKey("F${it + 1}", KeyEvent.KEYCODE_F1 + it) }

@Composable
fun BoxScope.KeyboardExtraKeys() {
    if (!SoftKeyboard.visible.value) return

    var showFn by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            // imePadding lifts it clear of the keyboard; navigationBarsPadding keeps it off
            // the gesture bar on the frames where the IME is animating out.
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (key in if (showFn) FN_KEYS else BASE_KEYS) {
                KeyCap(key.label, wide = key.wide) { SoftKeyboard.tap(key.code) }
            }

            KeyCap(if (showFn) "abc" else "Fn", accent = true) { showFn = !showFn }
        }
    }
}

@Composable
private fun KeyCap(label: String, accent: Boolean = false, wide: Boolean = false, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = if (wide) 92.dp else 44.dp)
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (accent) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .pointerInput(label) { detectTapGestures { onTap() } }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (accent) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
