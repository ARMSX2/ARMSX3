package net.rpcsx.ui.settings

import net.rpcsx.R

import androidx.compose.ui.res.painterResource

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.rpcsx.AndroidPerformance
import net.rpcsx.MenuSfx
import net.rpcsx.ui.settings.components.preference.SliderPreference
import net.rpcsx.ui.settings.components.preference.SwitchPreference

/**
 * Android-only performance controls.
 *
 * Everything the CORE already owns -- Frame limit (fps cap), Second Frame
 * Limit, Thread Scheduler Mode, Sleep Timers Accuracy, PPU/SPURS thread counts,
 * Vblank Rate -- is deliberately absent here. Those are generated automatically
 * from RPCS3's config tree in the main Settings screen, and mirroring them
 * would give one value two controls that can disagree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceSettingsScreen(navigateBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    val sustained by remember { AndroidPerformance.sustainedMode }
    val clockHint by remember { AndroidPerformance.clockHint }
    val targetFps by remember { AndroidPerformance.clockHintTargetFps }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Performance") },
                navigationIcon = {
                    IconButton(onClick = {
                        MenuSfx.play(MenuSfx.Event.BACK)
                        navigateBack()
                    }) {
                        Icon(painter = painterResource(id = R.drawable.ic_keyboard_arrow_left), contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Device",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            )

            SwitchPreference(
                checked = sustained,
                title = "Sustained performance",
                subtitle = {
                    Text(
                        if (AndroidPerformance.isSustainedSupported) {
                            "Hold a flat, lower clock instead of bursting. Peak " +
                                "speed drops, but long sessions stop collapsing " +
                                "once the device gets hot."
                        } else {
                            "Requires Android 7.0 or newer"
                        }
                    )
                },
                enabled = AndroidPerformance.isSustainedSupported,
                onClick = { value ->
                    activity?.let { AndroidPerformance.setSustained(it, value) }
                }
            )

            HorizontalDivider()

            Text(
                text = "CPU clock hint (ADPF)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            )

            SwitchPreference(
                checked = clockHint,
                title = "Report frame work to the governor",
                subtitle = {
                    Text(
                        if (AndroidPerformance.isClockHintSupported) {
                            "EXPERIMENTAL. Tells Android how long each frame's " +
                                "work actually took so it can pick a clock instead " +
                                "of guessing from CPU load — an emulator's " +
                                "busy-waiting threads read as 100% load whether or " +
                                "not they need the clock."
                        } else {
                            "Requires Android 12 or newer"
                        }
                    )
                },
                enabled = AndroidPerformance.isClockHintSupported,
                onClick = { value ->
                    AndroidPerformance.setClockHint(context, value)
                }
            )

            SliderPreference(
                value = targetFps.toFloat(),
                onValueChange = { v ->
                    AndroidPerformance.setClockHintTargetFps(v.toInt())
                    AndroidPerformance.updateTargetWorkDuration()
                },
                title = "Target frame rate",
                subtitle = "$targetFps fps — the frame budget the hint is built around",
                enabled = AndroidPerformance.isClockHintSupported && clockHint,
                valueRange = 15f..120f,
                steps = 6
            )

            HorizontalDivider()

            Text(
                text = "Frame limit, thread scheduler, SPURS/PPU thread counts " +
                    "and vblank rate are emulator settings — find them under " +
                    "Settings → Core and Settings → Video.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
