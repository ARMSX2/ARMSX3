package net.rpcsx.ui.skins

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcsx.ControllerSkinStore
import net.rpcsx.MenuSfx
import net.rpcsx.R
import net.rpcsx.SkinRepo

/**
 * On-screen controller skins: pick a bundled one, import your own
 * (folder of ic_controller_*.png, or a .zip), or install from the community
 * repo that [SkinRepo] indexes.
 *
 * ControllerSkinStore and SkinRepo were ported from ARMSX2 but had zero
 * references — this is the screen that makes them reachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinsScreen(navigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableStateOf(0) }
    val activeId by remember { ControllerSkinStore.activeSkinId }

    // list() returns imported skins only; the bundled ones live in BUILTIN and
    // are selectable without importing. Present them as one list, with a null
    // entry for "no skin" so the default look is reachable again after picking.
    data class Entry(val id: String?, val name: String, val removable: Boolean)

    val entries = remember(refreshKey) {
        buildList {
            add(Entry(null, "Default (no skin)", removable = false))
            ControllerSkinStore.BUILTIN.forEach { add(Entry(it.id, it.name, removable = false)) }
            ControllerSkinStore.list(context).forEach {
                add(Entry(it.id, "${it.name}  (${it.imageCount} images)", removable = true))
            }
        }
    }

    var remote by remember { mutableStateOf<List<SkinRepo.RemoteSkin>?>(null) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            ControllerSkinStore.importFromTree(context, it)
            refreshKey++
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            ControllerSkinStore.importFromZip(context, it)
            refreshKey++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Controller skins") },
                navigationIcon = {
                    IconButton(onClick = {
                        MenuSfx.play(MenuSfx.Event.BACK)
                        navigateBack()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                            contentDescription = null
                        )
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
                text = "Installed",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            )

            entries.forEach { entry ->
                // Explicit Unit: the trailing refreshKey++ would otherwise infer
                // () -> Int and fail to match onClick's () -> Unit.
                val select: () -> Unit = {
                    MenuSfx.play(MenuSfx.Event.SELECT)
                    ControllerSkinStore.setActive(context, entry.id)
                    refreshKey++
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = select)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = activeId == entry.id, onClick = select)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (entry.removable && entry.id != null) {
                        TextButton(onClick = {
                            MenuSfx.play(MenuSfx.Event.BACK)
                            ControllerSkinStore.delete(context, entry.id)
                            refreshKey++
                        }) { Text("Remove") }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Import",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        MenuSfx.play(MenuSfx.Event.SUBMENU)
                        folderPicker.launch(null)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Folder") }

                OutlinedButton(
                    onClick = {
                        MenuSfx.play(MenuSfx.Event.SUBMENU)
                        zipPicker.launch("*/*")
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(".zip") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                text = "Community skins",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )

            when {
                browsing -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }

                remoteError != null -> Text(
                    text = remoteError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                remote == null -> Button(
                    onClick = {
                        MenuSfx.play(MenuSfx.Event.SUBMENU)
                        browsing = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { SkinRepo.fetch() }
                            }
                            browsing = false
                            result
                                .onSuccess { remote = it }
                                .onFailure { remoteError = "Couldn't reach the skin repository." }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) { Text("Browse") }

                else -> remote!!.forEach { skin ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = skin.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                MenuSfx.play(MenuSfx.Event.SELECT)
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        runCatching { SkinRepo.install(context, skin) }
                                    }
                                    refreshKey++
                                }
                            }) { Text("Install") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
