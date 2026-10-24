package com.armsx2.ui.emulation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.armsx2.input.SkylanderPortal
import com.armsx2.input.UsbPassthrough
import com.armsx2.i18n.str
import com.armsx2.ui.settings.controllerFocusable

/**
 * The emulated Skylanders portal, in the in-game menu: what is on it, and the figure files to put on it.
 *
 * The game sees each change at once, as a toy set down or lifted, and carries on underneath the menu.
 * With a real portal passed through (UsbPassthrough) the game is not looking at this one, so it says
 * so instead of offering figures that would go nowhere.
 */
@Composable
fun SkylanderPortalPanel(modifier: Modifier = Modifier) {
    val real by UsbPassthrough.realSkylanderPortal
    // Read when the panel opens, so files copied in meanwhile show up
    val figures = remember { SkylanderPortal.figures() }
    val folder = remember { SkylanderPortal.folder().absolutePath }
    var message by remember { mutableStateOf<String?>(null) }
    val fullText = str("portal.full")
    val notFigureText = str("portal.notFigure")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (real) {
            Text(str("portal.real"), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        Text(str("portal.onPortal"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

        if (SkylanderPortal.placed.isEmpty()) {
            Text(str("portal.empty"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        for ((slot, name) in SkylanderPortal.placed.toSortedMap()) {
            FigureRow(name.substringBeforeLast('.'), str("portal.lift"), "portal.lift.$slot", enabled = true) {
                SkylanderPortal.lift(slot)
                message = null
            }
        }

        Text(
            str("portal.figures"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (figures.isEmpty()) {
            Text(str("portal.noFigures").format(folder), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        for (file in figures) {
            val onPortal = file.name in SkylanderPortal.placed.values
            FigureRow(file.nameWithoutExtension, str(if (onPortal) "portal.onIt" else "portal.place"), "portal.place.${file.name}", enabled = !onPortal) {
                message = when (SkylanderPortal.place(file)) {
                    SkylanderPortal.Result.Placed -> null
                    SkylanderPortal.Result.Full -> fullText
                    SkylanderPortal.Result.NotAFigure -> notFigureText.format(file.name)
                }
            }
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        if (figures.isNotEmpty()) {
            Text(
                str("portal.folder").format(folder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun FigureRow(name: String, action: String, controllerId: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = if (enabled) Modifier.controllerFocusable(controllerId, onConfirm = onClick) else Modifier,
        ) { Text(action) }
    }
}
