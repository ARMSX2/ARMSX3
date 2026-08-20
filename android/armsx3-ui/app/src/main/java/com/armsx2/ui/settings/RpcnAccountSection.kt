package com.armsx2.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.armsx2.i18n.str
import com.armsx3.Rpcs3Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * RPCN account setup.
 *
 * RPCN is RPCS3's replacement for PSN — the thing that makes online play work at all. The
 * client is compiled into this core and always has been; what was missing was any way to
 * reach it. "PSN status" was a boolean that could only pick Disconnected or Simulated, and
 * there was no screen anywhere to enter a username, a password or a server. So online was
 * not broken here, it was unreachable, and a user asking why it did not work had no answer.
 *
 * An RPCN account is created from the client, not from a website: the server takes a
 * username, password and email, then mails a token that has to be entered once to activate
 * the account. That is the whole flow below.
 *
 * Every call here blocks on the network, so all of them run on Dispatchers.IO. A failure
 * comes back from the core as a finished sentence rather than an error code, so there is no
 * second copy of RPCN's two error enums to keep in step on this side.
 */
@Composable
fun RpcnAccountSection() {
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("") }
    var npid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    var hasPassword by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    // The saved-server list is the core's own cfg_rpcn "Hosts" entry, read back rather than
    // mirrored, so add/remove/reset and whatever the core did to it always agree.
    var hosts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var ipv6 by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    // Saved-account state, read from the core on entry. RPCN keeps no persistent session, so
    // this is what actually survives a restart -- see the note on rpcnStatus.
    var configured by remember { mutableStateOf(false) }
    var signedInAs by remember { mutableStateOf("") }

    // str() is @Composable, so it cannot be called from run() or from an onClick lambda.
    // Resolve every message the callbacks need up here, where it is legal, and let them
    // close over plain strings.
    val msgWorking = str("rpcn.working")
    val msgSaved = str("rpcn.saved")
    val msgSignedIn = str("rpcn.signedIn")
    val msgCreated = str("rpcn.created")
    val msgCreateHint = str("rpcn.create.hint")
    val msgTokenSent = str("rpcn.token.sent")
    val msgResetSent = str("rpcn.reset.sent")
    val msgHostAdded = str("rpcn.hosts.added")
    val msgHostRemoved = str("rpcn.hosts.removed")
    val msgHostsReset = str("rpcn.hosts.resetDone")
    val msgHostSelected = str("rpcn.hosts.selected")

    /** Re-read everything the core holds. Called on entry and after anything that writes. */
    suspend fun reload() {
        val json = withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnGetConfig() }

        if (json.isNotBlank()) {
            runCatching {
                val o = JSONObject(json)
                host = o.optString("host", "np.rpcs3.net")
                npid = o.optString("npid", "")
                hasPassword = o.optBoolean("hasPassword", false)
                ipv6 = o.optBoolean("ipv6", false)

                val arr = o.optJSONArray("hosts")
                val list = mutableListOf<Pair<String, String>>()
                for (i in 0 until (arr?.length() ?: 0)) {
                    val e = arr!!.optJSONObject(i) ?: continue
                    val desc = e.optString("desc", "")
                    val addr = e.optString("host", "")
                    if (desc.isNotBlank() && addr.isNotBlank()) list.add(desc to addr)
                }
                hosts = list
            }
        }

        if (host.isBlank()) host = "np.rpcs3.net"

        val statusJson = withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnStatus() }
        if (statusJson.isNotBlank()) {
            runCatching {
                val o = JSONObject(statusJson)
                configured = o.optBoolean("configured", false)
                // Only when a game is actually online; otherwise there is no live client.
                signedInAs = if (o.optBoolean("authentified", false))
                    o.optString("onlineName", "") else ""
            }
        }
    }

    // Seed from whatever the core already has, so an existing account shows up rather than
    // looking unset.
    LaunchedEffect(Unit) { reload() }

    /** Run one blocking RPCN call, funnelling its message into [status]. */
    fun run(okMessage: String, block: () -> String) {
        if (busy) return
        busy = true
        status = msgWorking

        scope.launch {
            val message = withContext(Dispatchers.IO) { runCatching(block).getOrElse { "" } }
            status = message.ifBlank { okMessage }
            busy = false

            if (message.isBlank()) {
                // Re-read rather than assume: the core saves credentials itself on a
                // successful create or reset, and this is what proves it.
                reload()
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            str("rpcn.title"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            str("rpcn.description"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )

        // Answers "did it remember me?" on entry, without having to press anything. The
        // account is saved; the connection is not, and the wording keeps those apart.
        if (signedInAs.isNotBlank()) {
            Text(
                str("rpcn.account.connected") + " " + signedInAs,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        } else if (configured) {
            Column(Modifier.padding(bottom = 6.dp)) {
                Text(
                    str("rpcn.account.saved") + " " + npid,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    str("rpcn.account.saved.note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(str("rpcn.server")) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        // Saved servers. Tapping one selects it; the official entry is protected against
        // deletion in the core, which is what guarantees there is always a way back.
        Text(
            str("rpcn.hosts.title"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            str("rpcn.hosts.description"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
        )

        hosts.forEach { (desc, addr) ->
            val selected = addr.equals(host.trim(), ignoreCase = true)
            val official = desc == "Official RPCN Server" && addr == "np.rpcs3.net"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) {
                        host = addr
                        Rpcs3Bridge.rpcnSetConfig(addr, "", "", "")
                        status = msgHostSelected
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selected) "\u25cf  $desc" else "\u25cb  $desc",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "  $addr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (!official) {
                    TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            val message = withContext(Dispatchers.IO) {
                                Rpcs3Bridge.rpcnDelHost(desc, addr)
                            }
                            status = message.ifBlank { msgHostRemoved }
                            reload()
                        }
                    }) { Text(str("rpcn.hosts.remove")) }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(enabled = !busy, onClick = {
                newName = ""
                naming = true
            }) { Text(str("rpcn.hosts.add")) }

            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnResetHosts() }
                    status = msgHostsReset
                    reload()
                }
            }) { Text(str("rpcn.hosts.reset")) }
        }

        if (naming) {
            AlertDialog(
                onDismissRequest = { naming = false },
                title = { Text(str("rpcn.hosts.add")) },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(str("rpcn.hosts.name")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = newName.trim()
                        val addr = host.trim()
                        naming = false
                        scope.launch {
                            val message = withContext(Dispatchers.IO) {
                                Rpcs3Bridge.rpcnAddHost(name, addr)
                            }
                            status = message.ifBlank { msgHostAdded }
                            reload()
                        }
                    }) { Text(str("rpcn.save")) }
                },
                dismissButton = {
                    TextButton(onClick = { naming = false }) { Text(str("action.cancel")) }
                },
            )
        }

        OutlinedTextField(
            value = npid,
            onValueChange = { npid = it },
            label = { Text(str("rpcn.username")) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = {
                // Say when one is already stored, because the field is deliberately blank
                // and an empty box would otherwise read as "no password set".
                //
                // Not shown even though it could be: cfg_rpcn stores the password in
                // PLAINTEXT in rpcn.yml (set_password does a straight from_string), so
                // rendering it would put it on screen as well as on disk for no gain.
                Text(if (hasPassword) str("rpcn.password.stored") else str("rpcn.password"))
            },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )

        if (creating) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(str("rpcn.email")) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Text(
                str("rpcn.email.why"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // The token arrives by email after creating an account and activates it. Kept
        // visible always: it is also what a password reset needs.
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(str("rpcn.token")) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(enabled = !busy, onClick = {
                Rpcs3Bridge.rpcnSetConfig(host.trim(), npid.trim(), password, token.trim())
                password = ""
                status = msgSaved
                scope.launch { reload() }
            }) { Text(str("rpcn.save")) }

            TextButton(enabled = !busy, onClick = {
                // Save first: a login test with a host the user just typed but never saved
                // would test the old one and report a confusing result.
                Rpcs3Bridge.rpcnSetConfig(host.trim(), npid.trim(), password, token.trim())
                password = ""
                run(msgSignedIn) { Rpcs3Bridge.rpcnTestLogin() }
            }) { Text(str("rpcn.test")) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(enabled = !busy, onClick = {
                if (!creating) {
                    creating = true
                    status = msgCreateHint
                } else {
                    Rpcs3Bridge.rpcnSetConfig(host.trim(), "", "", "")
                    run(msgCreated) {
                        Rpcs3Bridge.rpcnCreateAccount(
                            npid.trim(), password, npid.trim(), email.trim(),
                        )
                    }
                }
            }) { Text(if (creating) str("rpcn.create.go") else str("rpcn.create")) }

            TextButton(enabled = !busy, onClick = {
                Rpcs3Bridge.rpcnSetConfig(host.trim(), "", "", "")
                run(msgTokenSent) {
                    Rpcs3Bridge.rpcnResendToken(npid.trim(), password)
                }
            }) { Text(str("rpcn.token.resend")) }

            TextButton(enabled = !busy, onClick = {
                Rpcs3Bridge.rpcnSetConfig(host.trim(), "", "", "")
                run(msgResetSent) {
                    if (token.isBlank()) {
                        Rpcs3Bridge.rpcnSendResetToken(npid.trim(), email.trim())
                    } else {
                        Rpcs3Bridge.rpcnResetPassword(npid.trim(), token.trim(), password)
                    }
                }
            }) { Text(str("rpcn.reset")) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    str("rpcn.ipv6.label"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    str("rpcn.ipv6.description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = ipv6,
                enabled = !busy,
                onCheckedChange = {
                    ipv6 = it
                    scope.launch {
                        withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnSetIpv6(it) }
                    }
                },
            )
        }

        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
