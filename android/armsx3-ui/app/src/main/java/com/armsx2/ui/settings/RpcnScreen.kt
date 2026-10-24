package com.armsx2.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.config.ConfigStore
import com.armsx2.config.SettingsScope
import com.armsx2.i18n.str
import com.armsx2.ui.InGameOverlay
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.ArmsTopBar
import com.armsx2.ui.common.GlassPanel
import com.armsx2.ui.common.RoundAction

/** PSN status as Settings stores it: off, simulated, RPCN. */
private const val PsnRpcn = 2

/**
 * RPCN on a page of its own, from the library's menu: the account, friends, servers and the rest,
 * as the Network settings show them.
 *
 * Settings shows them only once PSN status is set to RPCN, so someone looking for where to sign in
 * found nothing until they changed a setting they had no reason to know about. Here they are always
 * shown, under a card that switches RPCN on while it is off. That is the global setting, as the
 * menu this opens from belongs to no game.
 */
@Composable
fun RpcnScreen(onBack: () -> Unit) {
    var psnStatus by remember { mutableIntStateOf(ConfigStore.loadGlobal().ps3.psnStatus) }
    val useRpcn = {
        val previous = ConfigStore.loadGlobal()
        ConfigStore.save(SettingsScope.Global, null, previous.copy(ps3 = previous.ps3.copy(psnStatus = PsnRpcn)), previous)
        // So a later save from a menu holding the old copy does not put it back
        InGameOverlay.reloadSettings()
        psnStatus = PsnRpcn
    }

    ArmsBackdrop {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ArmsTopBar(
                    title = str("rpcn.screen.title"),
                    leading = { RoundAction("←", str("action.back"), onClick = onBack) },
                )
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (psnStatus != PsnRpcn) {
                        GlassPanel(Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    str("rpcn.off.title"),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(str("rpcn.off.note"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(
                                    onClick = useRpcn,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.height(46.dp).controllerFocusable("rpcn.off.turnOn", onConfirm = useRpcn),
                                ) { Text(str("rpcn.off.turnOn"), fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                    RpcnAccountSection(friends = { RpcnFriendsSection() })
                }
            }

            // The avatar picker, drawn over the page as it is over Settings (RpcnAvatar.kt)
            if (RpcnAvatarPicker.open.value) RpcnAvatarPickerOverlay()
        }
    }
}
