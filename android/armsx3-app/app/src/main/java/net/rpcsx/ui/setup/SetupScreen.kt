package net.rpcsx.ui.setup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.rpcsx.FirmwareRepository
import net.rpcsx.FirmwareStatus
import net.rpcsx.GameRepository
import net.rpcsx.MenuSfx
import net.rpcsx.PrecompilerService
import net.rpcsx.PrecompilerServiceAction
import net.rpcsx.SetupRepository
import net.rpcsx.utils.FileUtil

/**
 * ARMSX3 first-run setup.
 *
 * Three steps, in dependency order:
 *   1. Welcome / what this needs
 *   2. PS3 firmware  -- the only hard blocker (BootGame returns
 *      firmware_missing without dev_flash populated)
 *   3. Games         -- optional; internal storage already works without it
 *
 * The games step is deliberately skippable. The ARMSX1 lesson was that a
 * first-run flow which *requires* an external folder leaves titles already
 * sitting in app-private storage invisible, and the user reports "internal
 * shows no games". Here the internal directory is always scanned regardless of
 * whether a SAF folder is ever granted -- see [scanInternal].
 */
@Composable
fun SetupScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }

    val firmwareVersion by remember { FirmwareRepository.version }
    val firmwareStatus by remember { FirmwareRepository.status }

    val installFwLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                PrecompilerService.start(
                    context,
                    PrecompilerServiceAction.InstallFirmware,
                    uri
                )
            }
        }
    )

    val gameFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            uri?.let {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                FileUtil.installPackages(context, it)
            }
        }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            Text(
                text = "ARMSX3",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "PlayStation 3 emulation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(40.dp))

            when (step) {
                0 -> WelcomeStep(
                    onNext = {
                        MenuSfx.play(MenuSfx.Event.SELECT)
                        step = 1
                    }
                )

                1 -> FirmwareStep(
                    version = firmwareVersion,
                    status = firmwareStatus,
                    onInstall = {
                        MenuSfx.play(MenuSfx.Event.SUBMENU)
                        installFwLauncher.launch("*/*")
                    },
                    onNext = {
                        MenuSfx.play(MenuSfx.Event.SELECT)
                        step = 2
                    },
                    onBack = {
                        MenuSfx.play(MenuSfx.Event.BACK)
                        step = 0
                    }
                )

                else -> GamesStep(
                    onPickFolder = {
                        MenuSfx.play(MenuSfx.Event.SUBMENU)
                        gameFolderLauncher.launch(null)
                    },
                    onFinish = {
                        MenuSfx.play(MenuSfx.Event.SELECT)
                        scanInternal()
                        SetupRepository.markCompleted()
                        onFinished()
                    },
                    onBack = {
                        MenuSfx.play(MenuSfx.Event.BACK)
                        step = 1
                    }
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * Always rescan the app-private games directory on finishing setup.
 *
 * This is the ARMSX1 "internal storage shows no games" guard: titles installed
 * via a PKG, or copied in before setup ran, live under RPCSX.rootDirectory and
 * are NOT covered by any SAF grant. If the library only refreshed when an
 * external folder was picked, those would stay invisible forever and look like
 * a scanning bug.
 */
private fun scanInternal() {
    GameRepository.queueRefresh()
}

@Composable
private fun StepCard(
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            content()
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepCard(
        title = "Welcome",
        body = "ARMSX3 needs two things before it can run games:\n\n" +
            "1.  PS3 system firmware (PS3UPDAT.PUP), downloaded from Sony\n" +
            "2.  Your own game dumps\n\n" +
            "Neither is included. This takes about a minute."
    ) {
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get started")
        }
    }
}

@Composable
private fun FirmwareStep(
    version: String?,
    status: FirmwareStatus,
    onInstall: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val installed = version != null

    StepCard(
        title = "PS3 firmware",
        body = if (installed) {
            "Firmware $version is installed."
        } else {
            "Download PS3UPDAT.PUP from Sony's official PlayStation 3 update " +
                "page, then select it here. ARMSX3 will decrypt and install it " +
                "into dev_flash.\n\nGames cannot boot without this."
        }
    ) {
        when {
            status == FirmwareStatus.Installed && !installed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(12.dp))
                    Text("Installing firmware…")
                }
            }

            installed -> {
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue")
                }
            }

            else -> {
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Text("Select PS3UPDAT.PUP")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            if (!installed) {
                // Escape hatch: someone re-running setup, or installing
                // firmware later from Settings, should not be trapped here.
                TextButton(onClick = onNext) { Text("Skip for now") }
            }
        }
    }
}

@Composable
private fun GamesStep(
    onPickFolder: () -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    StepCard(
        title = "Games",
        body = "Point ARMSX3 at a folder containing your dumps, and it will " +
            "scan for them.\n\nThis is optional — anything already installed " +
            "to ARMSX3's own storage is always picked up."
    ) {
        OutlinedButton(
            onClick = onPickFolder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Choose games folder")
        }

        Spacer(Modifier.height(12.dp))

        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text("Finish")
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}
