package com.armsx2.ui.underclock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.ui.common.ArmsTopBar
import com.armsx2.ui.common.RoundAction
import com.armsx2.ui.settings.controllerFocusable

private const val CLUSTERTUNE_URL = "https://github.com/AurelioB/ClusterTune"

/**
 * A pointer to ClusterTune, not a clock control of our own.
 *
 * ARMSX3 cannot cap CPU or GPU clocks: that needs root, or the vendor firmware service AYN and
 * Retroid ship, which only a privileged app may call. ClusterTune already does both, per cluster
 * and per app, so this screen explains what it is and opens its repo rather than duplicating it.
 */
@Composable
fun UnderclockingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val open = { openExternalUrl(context, CLUSTERTUNE_URL) }

    Column(Modifier.fillMaxSize()) {
        ArmsTopBar(
            title = str("underclock.title"),
            leading = { RoundAction("←", str("action.back"), onBack) },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Card(str("underclock.body"))
            Button(
                onClick = open,
                modifier = Modifier
                    .fillMaxWidth()
                    .controllerFocusable("underclock.open", onConfirm = open),
            ) { Text(str("underclock.open")) }
            Card(str("underclock.note"))
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Card(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

// Opened the way the drawer opens its links: launch and catch the miss. LocalUriHandler rethrows
// ActivityNotFoundException as IllegalArgumentException, and a resolveActivity() pre-check is
// filtered by package visibility and can read null for a link that would open.
private fun openExternalUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, I18n.get("about.openFailed"), Toast.LENGTH_LONG).show()
    }
}
