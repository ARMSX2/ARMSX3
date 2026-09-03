package com.armsx2.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.i18n.str
import com.armsx2.ui.common.GlassPanel
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

    // The five credential fields are only interesting while setting an account up. Once one is
    // saved they are noise on every visit, so they collapse behind a row and the panel that
    // matters -- who am I signed in as -- comes first.
    var editing by remember { mutableStateOf(false) }
    var confirmDeleteTrophies by remember { mutableStateOf(false) }

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
    val msgNeedUsername = str("rpcn.need.username")
    val msgNeedPassword = str("rpcn.need.password")
    val msgNeedNewPassword = str("rpcn.need.newPassword")
    val msgNeedEmail = str("rpcn.need.email")
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

    val msgTrophiesDeleted = str("rpcn.trophies.deleted")

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // 1. WHERE YOU STAND. First, because it is the only thing most visits want to know,
        //    and because the status line used to sit at the very bottom under the IPv6 switch
        //    -- so a server error read as though IPv6 had caused it.
        RpcnCard(str("rpcn.title"), str("rpcn.description")) {
            when {
                signedInAs.isNotBlank() -> RpcnState(
                    str("rpcn.account.connected") + " " + signedInAs,
                    null,
                    MaterialTheme.colorScheme.primary,
                )
                configured -> RpcnState(
                    str("rpcn.account.saved") + " " + npid,
                    str("rpcn.account.saved.note"),
                    MaterialTheme.colorScheme.primary,
                )
                else -> RpcnState(
                    str("rpcn.state.none"),
                    str("rpcn.state.none.note"),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    status,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 2. WHICH SERVER. The saved list is the control; the free-text box below it is for
        //    typing one that is not saved yet.
        RpcnCard(str("rpcn.hosts.title"), str("rpcn.hosts.description")) {
            hosts.forEach { (desc, addr) ->
                val selected = addr.equals(host.trim(), ignoreCase = true)
                val official = desc == "Official RPCN Server" && addr == "np.rpcs3.net"
                val pick = {
                    host = addr
                    Rpcs3Bridge.rpcnSetConfig(addr, "", "", "")
                    status = msgHostSelected
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(rowAura())
                        .clickable(enabled = !busy, onClick = pick)
                        .controllerFocusable("rpcn.host.$addr", onConfirm = pick)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selected) "\u25cf" else "\u25cb",
                        fontSize = 14.sp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            desc,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(addr, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(str("rpcn.server")) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
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

        Spacer(Modifier.height(10.dp))

        // 3. THE CREDENTIALS. Collapsed whenever an account is already set up, which is the
        //    common case and the one that does not need five text boxes on screen.
        RpcnCard(str("rpcn.section.account"), null) {
            if (configured && !editing) {
                val open = { editing = true }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(rowAura())
                        .clickable(enabled = !busy, onClick = open)
                        .controllerFocusable("rpcn.account.change", onConfirm = open)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            str("rpcn.account.change"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            str("rpcn.account.change.note"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = npid,
                    onValueChange = { npid = it },
                    label = { Text(str("rpcn.username")) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
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
                // Always shown, not only while creating an account.
                //
                // Password reset sends this address to the server, and hiding the box outside
                // creation mode meant Reset password posted an EMPTY email. The server rejects
                // that whole query as Malformed, which reached the user as the meaningless
                // "Server error 1".
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
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
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

                // One filled button, because there is one thing most people want: save what I
                // typed and tell me whether it works. The rest stay text buttons so the eye is
                // not asked to choose between seven equals.
                Button(
                    enabled = !busy,
                    onClick = {
                        // Save first: a login test with a host the user just typed but never
                        // saved would test the old one and report a confusing result.
                        Rpcs3Bridge.rpcnSetConfig(host.trim(), npid.trim(), password, token.trim())
                        password = ""
                        run(msgSignedIn) { Rpcs3Bridge.rpcnTestLogin() }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) { Text(str("rpcn.test")) }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(enabled = !busy, onClick = {
                        Rpcs3Bridge.rpcnSetConfig(host.trim(), npid.trim(), password, token.trim())
                        password = ""
                        status = msgSaved
                        scope.launch { reload() }
                    }) { Text(str("rpcn.save")) }

                    TextButton(enabled = !busy, onClick = {
                        if (!creating) {
                            creating = true
                            status = msgCreateHint
                        } else if (npid.isBlank()) {
                            status = msgNeedUsername
                        } else if (password.isBlank()) {
                            status = msgNeedPassword
                        } else if (email.isBlank()) {
                            status = msgNeedEmail
                        } else {
                            Rpcs3Bridge.rpcnSetConfig(host.trim(), "", "", "")
                            run(msgCreated) {
                                Rpcs3Bridge.rpcnCreateAccount(
                                    npid.trim(), password, npid.trim(), email.trim(),
                                )
                            }
                        }
                    }) { Text(if (creating) str("rpcn.create.go") else str("rpcn.create")) }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Checked here rather than sent and rejected. A required field left empty
                    // makes the whole query Malformed, and the server's answer to that is an
                    // error number with no way for the user to know which box to fill in. Note
                    // the password box is CLEARED by Save, so "I typed it a moment ago" is not
                    // the same as "it is in the field now".
                    TextButton(enabled = !busy, onClick = {
                        when {
                            npid.isBlank() -> status = msgNeedUsername
                            password.isBlank() -> status = msgNeedPassword
                            else -> {
                                Rpcs3Bridge.rpcnSetConfig(host.trim(), "", "", "")
                                run(msgTokenSent) {
                                    Rpcs3Bridge.rpcnResendToken(npid.trim(), password)
                                }
                            }
                        }
                    }) { Text(str("rpcn.token.resend")) }

                    TextButton(enabled = !busy, onClick = {
                        when {
                            npid.isBlank() -> status = msgNeedUsername
                            // No token yet: ask the server to email one, which needs the address.
                            token.isBlank() && email.isBlank() -> status = msgNeedEmail
                            // Token in hand: this is the actual reset, so a new password is required.
                            token.isNotBlank() && password.isBlank() -> status = msgNeedNewPassword
                            else -> {
                                Rpcs3Bridge.rpcnSetConfig(host.trim(), "", "", "")
                                run(msgResetSent) {
                                    if (token.isBlank()) {
                                        Rpcs3Bridge.rpcnSendResetToken(npid.trim(), email.trim())
                                    } else {
                                        Rpcs3Bridge.rpcnResetPassword(npid.trim(), token.trim(), password)
                                    }
                                }
                            }
                        }
                    }) { Text(str("rpcn.reset")) }

                    if (configured) {
                        Spacer(Modifier.weight(1f))
                        TextButton(enabled = !busy, onClick = { editing = false }) {
                            Text(str("rpcn.account.hide"))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 4. THE REST. IPv6 goes through the shared ToggleRow so it looks and behaves like
        //    every other switch in Settings, and the destructive action lives on its own at
        //    the bottom where destructive actions belong.
        RpcnCard(str("rpcn.section.advanced"), null) {
            ToggleRow(
                str("rpcn.ipv6.label"),
                ipv6,
                description = str("rpcn.ipv6.description"),
            ) {
                ipv6 = it
                scope.launch { withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnSetIpv6(it) } }
            }
            HorizontalDivider(
                Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            val askDelete = { confirmDeleteTrophies = true }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(rowAura())
                    .clickable(enabled = !busy, onClick = askDelete)
                    .controllerFocusable("rpcn.trophies.delete", onConfirm = askDelete)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        str("rpcn.trophies.title"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        str("rpcn.trophies.description"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (confirmDeleteTrophies) {
            AlertDialog(
                onDismissRequest = { confirmDeleteTrophies = false },
                title = { Text(str("rpcn.trophies.title")) },
                text = { Text(str("rpcn.trophies.confirm")) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDeleteTrophies = false
                        run(msgTrophiesDeleted) { Rpcs3Bridge.rpcnDeleteTrophies() }
                    }) { Text(str("rpcn.trophies.title")) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteTrophies = false }) {
                        Text(str("action.cancel"))
                    }
                },
            )
        }
    }
}

/** One titled panel. Four of these replace what used to be a single flat column of every
 *  control RPCN has, in the order they happened to be written. */
@Composable
private fun RpcnCard(title: String, description: String?, content: @Composable () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Text(
                    description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** The one line that answers "am I set up?", with its footnote. */
@Composable
private fun RpcnState(line: String, note: String?, color: androidx.compose.ui.graphics.Color) {
    Text(line, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
    if (note != null) {
        Text(
            note,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
