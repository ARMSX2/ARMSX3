package com.armsx2.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.armsx2.config.Settings
import com.armsx2.i18n.str

/**
 * Where frame generation's section sits in the github build. It is not in the play build: it
 * runs on shaders taken from another company's product, so none of it ships through the store,
 * not the screens, not their text and not the library (see the github copy of this file). This
 * says where it can be had instead, without a link.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun FrameGenSection(s: Settings, apply: (Settings) -> Unit) {
    CollapsibleSection(str("perf.framegenPlay.title")) {
        Text(
            str("perf.framegenPlay.note"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
        )
    }
}
