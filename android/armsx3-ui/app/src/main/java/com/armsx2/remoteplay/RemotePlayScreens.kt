package com.armsx2.remoteplay

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.armsx2.i18n.str
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.ArmsTopBar
import com.armsx2.ui.common.GlassPanel
import com.armsx2.ui.common.RoundAction
import com.armsx2.ui.settings.controllerFocusable

/**
 * Remote play from the library: find a friend hosting on this Wi-Fi and join as player 2.
 * Hosting is in the in-game menu (Friends), since only a running game can be hosted.
 */
@Composable
fun RemotePlayScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hosts by remember { mutableStateOf(emptyList<RemotePlayHostInfo>()) }
    var address by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val browser = HostBrowser(context) { hosts = it }
        browser.start()
        onDispose { browser.stop() }
    }

    ArmsBackdrop {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ArmsTopBar(
                title = str("remoteplay.title"),
                leading = { RoundAction("←", str("action.back"), onClick = onBack) },
            )
            Column(
                Modifier.padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Text(
                        str("remoteplay.explain"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            str("remoteplay.join.title"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (hosts.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(str("remoteplay.join.searching"), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                str("remoteplay.join.howToHost"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        hosts.forEach { host ->
                            HostRow(host) { RemotePlayViewerActivity.start(context, host.address, host.port, host.name) }
                        }
                    }
                }

                GlassPanel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            str("remoteplay.join.manual"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = address,
                                onValueChange = { address = it.trim() },
                                singleLine = true,
                                label = { Text(str("remoteplay.join.address")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            val join = { joinAddress(context, address) }
                            Button(
                                onClick = join,
                                enabled = address.isNotBlank(),
                                modifier = Modifier.controllerFocusable("remoteplay.join.address", onConfirm = join),
                            ) { Text(str("remoteplay.join.connect")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostRow(host: RemotePlayHostInfo, onJoin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                host.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (host.game.isNotBlank()) {
                Text(
                    host.game,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onJoin,
            modifier = Modifier.controllerFocusable("remoteplay.host.${host.name}", onConfirm = onJoin),
        ) { Text(str("remoteplay.join.connect")) }
    }
}

/** "192.168.1.20" or "192.168.1.20:38433". */
private fun joinAddress(context: Context, text: String) {
    val value = text.trim()
    if (value.isEmpty()) return
    val colon = value.lastIndexOf(':')
    val port = if (colon > 0) value.substring(colon + 1).toIntOrNull() else null
    val host = if (port != null) value.substring(0, colon) else value
    RemotePlayViewerActivity.start(context, host, port ?: RemotePlayProtocol.PORT, host)
}

/** Hosting, in the in-game menu's Friends overlay. */
@Composable
fun RemotePlayHostPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by RemotePlayHost.status
    val start = { RemotePlayHost.start(context) }
    val stop = { RemotePlayHost.stop() }

    GlassPanel(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                str("remoteplay.title"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            when (val current = status) {
                RemotePlayHost.Status.Off -> {
                    Text(
                        str("remoteplay.host.explain"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = start,
                        modifier = Modifier.controllerFocusable("remoteplay.host.start", onConfirm = start),
                    ) { Text(str("remoteplay.host.start")) }
                }

                is RemotePlayHost.Status.Waiting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(str("remoteplay.host.waiting"), style = MaterialTheme.typography.bodySmall)
                    }
                    current.address?.let {
                        Text(
                            str("remoteplay.host.address").format(it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StopButton(stop)
                }

                is RemotePlayHost.Status.Connecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(str("remoteplay.host.connecting").format(current.viewer), style = MaterialTheme.typography.bodySmall)
                    }
                    StopButton(stop)
                }

                is RemotePlayHost.Status.Playing -> {
                    Text(str("remoteplay.host.playing").format(current.viewer), style = MaterialTheme.typography.bodySmall)
                    StopButton(stop)
                }

                is RemotePlayHost.Status.Failed -> {
                    Text(
                        current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = start,
                        modifier = Modifier.controllerFocusable("remoteplay.host.retry", onConfirm = start),
                    ) { Text(str("remoteplay.host.retry")) }
                }
            }
        }
    }
}

@Composable
private fun StopButton(stop: () -> Unit) {
    OutlinedButton(
        onClick = stop,
        modifier = Modifier.controllerFocusable("remoteplay.host.stop", onConfirm = stop),
    ) { Text(str("remoteplay.host.stop")) }
}
