package com.armsx2.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.data.library.GameLibraryRepository
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.FileBrowserDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rpcsx.ProgressRepository
import net.rpcsx.RPCSX

/**
 * Install a .pkg (game, update or DLC) into the emulator's own storage.
 *
 * The native side already did all of this -- _rpcsx_install dispatches on the file's
 * magic and hands a PKG to installPkg -- but RPCSX.instance.install() had ZERO callers,
 * so there was no way to reach it from the UI. Reported by two testers as a serious
 * oversight, and it read as a missing feature rather than a missing button.
 *
 * Installs land in config/dev_hdd0/game, which GameLibraryRepository now scans, so the
 * title shows up in the library on the next scan. The cache is keyed by folder set and
 * would not notice a new game inside a folder it already knows, hence invalidateCache().
 */
@Composable
fun PackageInstallerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    var progressId by remember { mutableStateOf<Long?>(null) }

    // getItem returns MutableState<ProgressEntry>; reading .value here and .longValue
    // below is what subscribes this composable to the native progress callbacks.
    val progress = ProgressRepository.getItem(progressId)?.value
    val fraction = progress?.let {
        if (it.isIndeterminate()) null
        else (it.value.longValue.toFloat() / it.max.longValue.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    if (showBrowser) {
        FileBrowserDialog(
            title = str("packages.select.title"),
            // PUP is deliberately absent: firmware has its own screen, and routing it
            // through here would let someone install firmware from a menu that says
            // nothing about it. EDAT rides along because _rpcsx_install handles it and
            // it is what DLC licences arrive as.
            extensions = setOf("pkg", "edat"),
            onPick = { file ->
                showBrowser = false
                busy = true
                message = null
                MainActivityRuntime.invoke {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val id = ProgressRepository.create(context, "Installing ${file.name}")
                            progressId = id
                            context.contentResolver
                                .openAssetFileDescriptor(android.net.Uri.fromFile(file), "r")
                                .use { afd ->
                                    val fd = afd?.parcelFileDescriptor?.fd
                                        ?: return@runCatching false
                                    RPCSX.instance.install(fd, id)
                                }
                        }.getOrDefault(false)
                    }
                    busy = false
                    progressId = null
                    // I18n.get, not str(): this runs inside a coroutine, and str() is a
                    // @Composable that can only be called during composition.
                    message = if (ok) {
                        // Force the library to re-read storage; the folder set is unchanged
                        // so nothing else would prompt a rescan.
                        GameLibraryRepository(context).invalidateCache()
                        I18n.get("packages.install.done")
                    } else {
                        I18n.get("packages.install.failed")
                    }
                }
            },
            onDismiss = { showBrowser = false },
        )
    }

    ArmsBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                str("packages.title"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        str("packages.description"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (busy) {
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            str("packages.installing"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(onClick = { showBrowser = true }) {
                            Text(str("packages.select.action"))
                        }
                    }

                    message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            TextButton(onClick = onBack) { Text(str("action.back")) }
        }
    }
}
