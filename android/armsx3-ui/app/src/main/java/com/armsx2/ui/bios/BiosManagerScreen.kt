package com.armsx2.ui.bios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.common.FileBrowserDialog
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.canBrowse
import com.armsx2.ui.settings.controllerFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.FirmwareRepository
import net.rpcsx.FirmwareStatus
import net.rpcsx.ProgressRepository
import net.rpcsx.RPCSX

/**
 * PS3 firmware, replacing ARMSX2's PS2 BIOS manager.
 *
 * The old screen listed .bin BIOS dumps, let you choose a per-game one, and
 * imported .mec/.nvm companions. None of that maps: PS3 firmware is a single
 * PS3UPDAT.PUP that gets INSTALLED into dev_flash rather than selected. There is
 * one system firmware, so there is nothing to choose between and nothing to
 * override per game -- hence one status card and one action.
 *
 * It also reported "No valid PS2 BIOS files found in that folder" and never
 * showed the firmware you had already installed, because it was reading a BIOS
 * directory that does not exist here instead of asking FirmwareRepository.
 *
 * `game` is accepted and ignored so the existing navigation route still compiles.
 */
@Composable
fun BiosManagerScreen(onBack: () -> Unit, game: com.armsx2.GameInfo? = null) {
    val context = LocalContext.current
    val version by FirmwareRepository.version
    val status by FirmwareRepository.status
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }

    // Re-read fw.json on entry. Firmware may have been installed by the setup
    // wizard in this same session, and the repository is otherwise only loaded
    // once at startup -- which is why this screen used to look empty right after
    // a successful install.
    LaunchedEffect(Unit) { runCatching { FirmwareRepository.load() } }

    if (showBrowser) {
        FileBrowserDialog(
            title = str("setup.bios.selectTitle"),
            extensions = setOf("pup"),
            onPick = { file ->
                showBrowser = false
                busy = true
                message = null
                MainActivityRuntime.invoke {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val id = ProgressRepository.create(context, "Installing firmware")
                            context.contentResolver
                                .openAssetFileDescriptor(android.net.Uri.fromFile(file), "r")
                                .use { afd ->
                                    val fd = afd?.parcelFileDescriptor?.fd
                                        ?: return@runCatching false
                                    RPCSX.instance.installFw(fd, id)
                                }
                        }.getOrDefault(false)
                    }
                    busy = false
                    if (!ok) message = I18n.get("bios.firmware.failed")
                }
            },
            onDismiss = { showBrowser = false },
        )
    }

    // ArmsBackdrop supplies the themed surface AND the content colours. Without
    // it this screen drew on the raw window background, so the headline rendered
    // near-black on a dark backdrop and was unreadable.
    ArmsBackdrop {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            str("setup.page.bios.title"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (status == FirmwareStatus.None) str("bios.firmware.notInstalled")
                    else str("setup.status.installed"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Version comes from the installed dev_flash, so this reflects the
                // firmware actually in use rather than the last file picked.
                version?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    str("setup.step.bios.description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (busy) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(
                    str("setup.firmware.installing"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            OutlinedButton(
                onClick = {
                    // In-app browser when storage is visible; otherwise say why
                    // rather than opening an empty list.
                    if (canBrowse()) showBrowser = true
                    else message = I18n.get("bios.firmware.needsStorage")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .controllerFocusable(
                        "firmware.install",
                        RoundedCornerShape(13.dp),
                        onConfirm = {
                            if (canBrowse()) showBrowser = true
                            else message = I18n.get("bios.firmware.needsStorage")
                        },
                    ),
            ) {
                Text(
                    if (status == FirmwareStatus.None) str("setup.bios.selectTitle")
                    else str("bios.firmware.reinstall"),
                )
            }
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .controllerFocusable("firmware.back", RoundedCornerShape(13.dp), onConfirm = onBack),
        ) {
            Text(str("action.back"))
        }
    }
    }
}
