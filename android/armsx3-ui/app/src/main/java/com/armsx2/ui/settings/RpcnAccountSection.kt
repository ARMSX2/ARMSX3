package com.armsx2.ui.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.i18n.str
import com.armsx2.ui.common.GlassPanel
import com.armsx2.ui.common.PadInput
import com.armsx2.ui.home.LibraryKeyboard
import com.armsx3.Rpcs3Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * RPCN, the community replacement for PSN that online play goes through: who you are on it, how to
 * get signed in, and which server.
 *
 * It leads with a profile card: the avatar games show for you (select it to change it), the RPCN
 * name, and whether it is signed in. Under it only the step at hand, one at a time: signing in,
 * making an account, activating it with the code RPCN emails, or resetting a password. Then
 * [friends], once there is an account to have friends on, the servers, and the switches few people
 * need ([advanced]; the library's RPCN page links to Settings for those instead). It was one card of
 * five text boxes and seven buttons, which people read as a puzzle.
 *
 * Passwords go to the core as typed, and the core hands RPCN what RPCS3 would: a password derived
 * from it (_armsx3_rpcnSignIn). So the password someone uses in RPCS3 works here as they know it;
 * pasting the long string out of RPCS3's rpcn.yml, which is what people had to do, is not needed
 * (and still works).
 *
 * Every RPCN call blocks on the network, so all of them run on Dispatchers.IO. A failure comes back
 * from the core as a finished sentence, never an error code.
 */
@Composable
fun RpcnAccountSection(friends: (@Composable () -> Unit)? = null, advanced: Boolean = true) {
    val scope = rememberCoroutineScope()

    // What the core holds, read on entry and after anything that writes
    var host by remember { mutableStateOf("np.rpcs3.net") }
    var hosts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var savedName by remember { mutableStateOf("") }
    var ipv6 by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(false) }
    var signedInAs by remember { mutableStateOf("") }

    // The form
    var step by remember { mutableStateOf<Step?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // What the last action did, shown on the card it was done from
    var notice by remember { mutableStateOf<AccountNotice?>(null) }
    var serverNotice by remember { mutableStateOf<AccountNotice?>(null) }
    var advancedNotice by remember { mutableStateOf<AccountNotice?>(null) }

    var addingServer by remember { mutableStateOf(false) }
    var serverName by remember { mutableStateOf("") }
    var serverAddress by remember { mutableStateOf("") }
    var deleteArmed by remember { mutableStateOf(false) }

    // str() is @Composable, so it cannot be called from run() or an onClick lambda. The callbacks
    // close over these instead.
    val msgWorking = str("rpcn.working")
    val msgSignedIn = str("rpcn.signedIn")
    val msgCreated = str("rpcn.created")
    val msgTokenSent = str("rpcn.token.sent")
    val msgResetSent = str("rpcn.reset.sent")
    val msgNeedUsername = str("rpcn.need.username")
    val msgNeedPassword = str("rpcn.need.password")
    val msgNeedNewPassword = str("rpcn.need.newPassword")
    val msgNeedEmail = str("rpcn.need.email")
    val msgNeedCode = str("rpcn.need.code")
    val msgNeedServer = str("rpcn.hosts.need")
    val msgHostAdded = str("rpcn.hosts.added")
    val msgHostRemoved = str("rpcn.hosts.removed")
    val msgHostsReset = str("rpcn.hosts.resetDone")
    val msgHostSelected = str("rpcn.hosts.selected")
    val msgTrophiesDeleted = str("rpcn.trophies.deleted")

    /** Whether an account is saved, and who is signed in while a game or the friends list is. */
    suspend fun readStatus() {
        val status = withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnStatus() }
        runCatching {
            val o = JSONObject(status)
            configured = o.optBoolean("configured", false)
            signedInAs = if (o.optBoolean("authentified", false)) o.optString("onlineName", "") else ""
        }
    }

    /** Everything the core holds. */
    suspend fun reload() {
        val config = withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnGetConfig() }
        runCatching {
            val o = JSONObject(config)
            host = o.optString("host").ifBlank { "np.rpcs3.net" }
            savedName = o.optString("npid", "")
            ipv6 = o.optBoolean("ipv6", false)
            val list = o.optJSONArray("hosts")
            hosts = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                val e = list!!.optJSONObject(i) ?: return@mapNotNull null
                val desc = e.optString("desc", "")
                val addr = e.optString("host", "")
                if (desc.isNotBlank() && addr.isNotBlank()) desc to addr else null
            }
        }
        readStatus()

        if (step == null) {
            step = if (configured) Step.Profile else Step.SignIn
            if (username.isBlank()) username = savedName
        }
    }

    LaunchedEffect(Unit) {
        reload()
        // The friends list signs in on its own a moment after this opens, and a game can go online
        // underneath the in-game menu; both are local reads
        while (true) {
            delay(2_000)
            readStatus()
        }
    }

    LaunchedEffect(deleteArmed) {
        if (deleteArmed) {
            delay(5_000)
            deleteArmed = false
        }
    }

    /** One blocking RPCN call; its sentence goes to [show]. [onOk] runs when it worked. */
    fun run(
        okMessage: String,
        show: (AccountNotice) -> Unit = { notice = it },
        onOk: () -> Unit = {},
        block: () -> String,
    ) {
        if (busy) return
        busy = true
        show(AccountNotice(msgWorking, error = false))

        scope.launch {
            val message = withContext(Dispatchers.IO) { runCatching(block).getOrElse { it.message.orEmpty() } }
            busy = false
            if (message.isBlank()) {
                show(AccountNotice(okMessage, error = false))
                onOk()
                // Re-read rather than assume: the core saves what worked by itself
                reload()
            } else {
                show(AccountNotice(message, error = true))
            }
        }
    }

    // A new step swaps the card under the pad's selection, and a selection whose control goes falls
    // to the top of the page. So with the pad driving it lets go first and lands on the new card.
    var restoreTo by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(restoreTo) {
        val targets = restoreTo ?: return@LaunchedEffect
        var attempts = 0
        while (targets.none { SettingsControllerNav.selectById(it) } && attempts < 10) {
            attempts++
            withFrameNanos {}
        }
        restoreTo = null
    }

    /** With the pad driving, select the first of [ids] that is on screen once this change is. */
    fun landOn(vararg ids: String) {
        if (PadInput.active.value) {
            SettingsControllerNav.clearSelection()
            restoreTo = ids.toList()
        }
    }

    fun switchTo(next: Step) {
        when (next) {
            Step.Profile -> landOn("rpcn.profile.check")
            Step.Activate -> landOn("rpcn.field.username", "rpcn.field.code")
            else -> landOn("rpcn.field.username")
        }
        step = next
    }

    fun go(next: Step) {
        notice = null
        switchTo(next)
    }

    fun signIn(token: String = "") {
        when {
            username.isBlank() -> notice = AccountNotice(msgNeedUsername, error = true)
            password.isEmpty() -> notice = AccountNotice(msgNeedPassword, error = true)
            else -> run(msgSignedIn, onOk = {
                password = ""
                code = ""
                switchTo(Step.Profile)
            }) { Rpcs3Bridge.rpcnSignIn(host.trim(), username.trim(), password, token.trim()) }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Who you are
        ProfileCard(
            name = if (configured || signedInAs.isNotBlank()) signedInAs.ifBlank { savedName } else null,
            state = when {
                signedInAs.isNotBlank() -> ProfileState.SignedIn
                configured -> ProfileState.Saved
                else -> ProfileState.None
            },
            server = hosts.firstOrNull { it.second.equals(host.trim(), ignoreCase = true) }?.first ?: host,
            notice = notice.takeIf { step == Step.Profile },
            showActions = configured && step == Step.Profile,
        ) {
            PrimaryAction("rpcn.profile.check", str("rpcn.action.check"), !busy) {
                run(msgSignedIn) { Rpcs3Bridge.rpcnTestLogin() }
            }
            SecondaryAction("rpcn.profile.switch", str("rpcn.action.switch"), !busy) {
                username = ""
                password = ""
                go(Step.SignIn)
            }
        }

        // The step at hand
        val formNotice = notice.takeIf { step != Step.Profile }
        when (step) {
            Step.SignIn -> FormCard(str("rpcn.signIn.title"), str("rpcn.signIn.note")) {
                Field("rpcn.field.username", str("rpcn.username"), username, enabled = !busy) { username = it }
                Field("rpcn.field.password", str("rpcn.password"), password, masked = true, enabled = !busy) { password = it }
                PrimaryAction("rpcn.signIn.go", str("rpcn.action.signIn"), !busy, Modifier.fillMaxWidth()) { signIn() }
                NoticeText(formNotice)
                LinkRow {
                    LinkAction("rpcn.signIn.create", str("rpcn.action.create"), !busy) { go(Step.Create) }
                    LinkAction("rpcn.signIn.forgot", str("rpcn.signIn.forgot"), !busy) { go(Step.Reset) }
                }
                LinkRow {
                    LinkAction("rpcn.signIn.code", str("rpcn.signIn.haveCode"), !busy) { go(Step.Activate) }
                    if (configured) LinkAction("rpcn.signIn.back", str("action.back"), !busy) { go(Step.Profile) }
                }
            }

            Step.Create -> FormCard(str("rpcn.create.title"), str("rpcn.create.note")) {
                Field("rpcn.field.username", str("rpcn.username"), username, enabled = !busy) { username = it }
                Hint(str("rpcn.username.rules"))
                Field("rpcn.field.password", str("rpcn.password"), password, masked = true, enabled = !busy) { password = it }
                Field("rpcn.field.email", str("rpcn.email"), email, keyboard = KeyboardType.Email, enabled = !busy) { email = it }
                Hint(str("rpcn.email.why"))
                PrimaryAction("rpcn.create.go", str("rpcn.create.go"), !busy, Modifier.fillMaxWidth()) {
                    when {
                        username.isBlank() -> notice = AccountNotice(msgNeedUsername, error = true)
                        password.isEmpty() -> notice = AccountNotice(msgNeedPassword, error = true)
                        email.isBlank() -> notice = AccountNotice(msgNeedEmail, error = true)
                        else -> run(msgCreated, onOk = { switchTo(Step.Activate) }) {
                            Rpcs3Bridge.rpcnSetConfig(host.trim(), "", "", "")
                            Rpcs3Bridge.rpcnCreateAccount(username.trim(), password, username.trim(), email.trim())
                        }
                    }
                }
                NoticeText(formNotice)
                LinkRow { LinkAction("rpcn.create.back", str("action.back"), !busy) { go(Step.SignIn) } }
            }

            Step.Activate -> FormCard(str("rpcn.activate.title"), str("rpcn.activate.note")) {
                // Straight after Create the name and password are still here. Coming back later
                // they are not, and the code is redeemed with them.
                val askAccount = remember { username.isBlank() || password.isEmpty() }
                if (askAccount) {
                    Field("rpcn.field.username", str("rpcn.username"), username, enabled = !busy) { username = it }
                    Field("rpcn.field.password", str("rpcn.password"), password, masked = true, enabled = !busy) { password = it }
                }
                Field("rpcn.field.code", str("rpcn.token"), code, enabled = !busy) { code = it }
                PrimaryAction("rpcn.activate.go", str("rpcn.activate.go"), !busy, Modifier.fillMaxWidth()) {
                    if (code.isBlank()) notice = AccountNotice(msgNeedCode, error = true) else signIn(token = code)
                }
                NoticeText(formNotice)
                LinkRow {
                    LinkAction("rpcn.activate.resend", str("rpcn.token.resend"), !busy) {
                        when {
                            username.isBlank() -> notice = AccountNotice(msgNeedUsername, error = true)
                            password.isEmpty() -> notice = AccountNotice(msgNeedPassword, error = true)
                            else -> run(msgTokenSent) {
                                Rpcs3Bridge.rpcnSetConfig(host.trim(), "", "", "")
                                Rpcs3Bridge.rpcnResendToken(username.trim(), password)
                            }
                        }
                    }
                    LinkAction("rpcn.activate.back", str("action.back"), !busy) { go(Step.SignIn) }
                }
            }

            Step.Reset -> FormCard(str("rpcn.reset.title"), str("rpcn.reset.note")) {
                Field("rpcn.field.username", str("rpcn.username"), username, enabled = !busy) { username = it }
                Field("rpcn.field.email", str("rpcn.email"), email, keyboard = KeyboardType.Email, enabled = !busy) { email = it }
                PrimaryAction("rpcn.reset.ask", str("rpcn.reset.ask"), !busy, Modifier.fillMaxWidth()) {
                    when {
                        username.isBlank() -> notice = AccountNotice(msgNeedUsername, error = true)
                        email.isBlank() -> notice = AccountNotice(msgNeedEmail, error = true)
                        else -> run(msgResetSent, onOk = { switchTo(Step.ResetCode) }) {
                            Rpcs3Bridge.rpcnSetConfig(host.trim(), "", "", "")
                            Rpcs3Bridge.rpcnSendResetToken(username.trim(), email.trim())
                        }
                    }
                }
                NoticeText(formNotice)
                LinkRow {
                    LinkAction("rpcn.reset.haveCode", str("rpcn.reset.haveCode"), !busy) { go(Step.ResetCode) }
                    LinkAction("rpcn.reset.back", str("action.back"), !busy) { go(Step.SignIn) }
                }
            }

            Step.ResetCode -> FormCard(str("rpcn.reset.codeTitle"), str("rpcn.reset.codeNote")) {
                Field("rpcn.field.username", str("rpcn.username"), username, enabled = !busy) { username = it }
                Field("rpcn.field.code", str("rpcn.reset.code"), code, enabled = !busy) { code = it }
                Field("rpcn.field.password", str("rpcn.reset.newPassword"), password, masked = true, enabled = !busy) { password = it }
                PrimaryAction("rpcn.reset.go", str("rpcn.reset.go"), !busy, Modifier.fillMaxWidth()) {
                    when {
                        username.isBlank() -> notice = AccountNotice(msgNeedUsername, error = true)
                        code.isBlank() -> notice = AccountNotice(msgNeedCode, error = true)
                        password.isEmpty() -> notice = AccountNotice(msgNeedNewPassword, error = true)
                        // The new password, then signed in with it, in one go
                        else -> run(msgSignedIn, onOk = {
                            password = ""
                            code = ""
                            switchTo(Step.Profile)
                        }) {
                            Rpcs3Bridge.rpcnSetConfig(host.trim(), "", "", "")
                            Rpcs3Bridge.rpcnResetPassword(username.trim(), code.trim(), password).ifBlank {
                                Rpcs3Bridge.rpcnSignIn(host.trim(), username.trim(), password, "")
                            }
                        }
                    }
                }
                NoticeText(formNotice)
                LinkRow { LinkAction("rpcn.resetCode.back", str("action.back"), !busy) { go(Step.Reset) } }
            }

            Step.Profile, null -> Unit
        }

        if (configured && friends != null) {
            GlassPanel(Modifier.fillMaxWidth()) { friends() }
        }

        // Which server
        FormCard(str("rpcn.hosts.title"), str("rpcn.hosts.description")) {
            hosts.forEach { (desc, addr) ->
                val official = desc == "Official RPCN Server" && addr == "np.rpcs3.net"
                ServerRow(
                    name = desc,
                    address = addr,
                    selected = addr.equals(host.trim(), ignoreCase = true),
                    enabled = !busy,
                    onPick = {
                        host = addr
                        Rpcs3Bridge.rpcnSetConfig(addr, "", "", "")
                        serverNotice = AccountNotice(msgHostSelected, error = false)
                    },
                    onRemove = if (official) null else ({
                        landOn("rpcn.hosts.add")
                        scope.launch {
                            val message = withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnDelHost(desc, addr) }
                            serverNotice = if (message.isBlank()) AccountNotice(msgHostRemoved, error = false) else AccountNotice(message, error = true)
                            reload()
                        }
                    }),
                )
            }

            if (addingServer) {
                Field("rpcn.field.serverName", str("rpcn.hosts.name"), serverName, enabled = !busy) { serverName = it }
                Field("rpcn.field.serverAddress", str("rpcn.hosts.address"), serverAddress, keyboard = KeyboardType.Uri, enabled = !busy) { serverAddress = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryAction("rpcn.hosts.save", str("rpcn.hosts.save"), !busy) {
                        val name = serverName.trim()
                        val addr = serverAddress.trim()
                        if (name.isBlank() || addr.isBlank()) {
                            serverNotice = AccountNotice(msgNeedServer, error = true)
                        } else {
                            scope.launch {
                                val message = withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnAddHost(name, addr) }
                                if (message.isBlank()) {
                                    serverNotice = AccountNotice(msgHostAdded, error = false)
                                    landOn("rpcn.hosts.add")
                                    addingServer = false
                                    serverName = ""
                                    serverAddress = ""
                                } else {
                                    serverNotice = AccountNotice(message, error = true)
                                }
                                reload()
                            }
                        }
                    }
                    SecondaryAction("rpcn.hosts.cancel", str("action.cancel"), !busy) {
                        landOn("rpcn.hosts.add")
                        addingServer = false
                    }
                }
            } else {
                LinkRow {
                    LinkAction("rpcn.hosts.add", str("rpcn.hosts.add"), !busy) {
                        landOn("rpcn.field.serverName")
                        addingServer = true
                    }
                    LinkAction("rpcn.hosts.reset", str("rpcn.hosts.reset"), !busy) {
                        scope.launch {
                            withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnResetHosts() }
                            serverNotice = AccountNotice(msgHostsReset, error = false)
                            reload()
                        }
                    }
                }
            }
            NoticeText(serverNotice)
        }

        // The rest: IPv6 through the shared ToggleRow, the one destructive action at the bottom
        if (advanced) FormCard(str("rpcn.section.advanced"), null) {
            ToggleRow(str("rpcn.ipv6.label"), ipv6, description = str("rpcn.ipv6.description")) {
                ipv6 = it
                scope.launch { withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnSetIpv6(it) } }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            // Asks twice on the row itself rather than in a dialog, which a pad cannot reach
            val delete = {
                if (!busy) {
                    if (!deleteArmed) {
                        deleteArmed = true
                    } else {
                        deleteArmed = false
                        run(msgTrophiesDeleted, show = { advancedNotice = it }) { Rpcs3Bridge.rpcnDeleteTrophies() }
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(rowAura())
                    .clickable(enabled = !busy, onClick = delete)
                    .controllerFocusable("rpcn.trophies.delete", onConfirm = delete)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Text(
                    str(if (deleteArmed) "rpcn.trophies.again" else "rpcn.trophies.title"),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    str(if (deleteArmed) "rpcn.trophies.confirm" else "rpcn.trophies.description"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NoticeText(advancedNotice)
        }
    }
}

/** Which step of getting an account working is on screen. */
private enum class Step { Profile, SignIn, Create, Activate, Reset, ResetCode }

private enum class ProfileState { SignedIn, Saved, None }

private class AccountNotice(val text: String, val error: Boolean)

/**
 * Who you are on RPCN: the avatar games show for you inside a slowly turning ring (select it to
 * change it), the name, whether it is signed in, and the server.
 */
@Composable
private fun ProfileCard(
    name: String?,
    state: ProfileState,
    server: String,
    notice: AccountNotice?,
    showActions: Boolean,
    actions: @Composable () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val openPicker = { RpcnAvatarPicker.open.value = true }

    GlassPanel(Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(primary.copy(alpha = 0.30f), Color.Transparent)))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RpcnAvatarRing(
                    96.dp,
                    Modifier
                        .clip(CircleShape)
                        .clickable(onClick = openPicker)
                        .controllerFocusable("rpcn.profile.avatar", CircleShape, onConfirm = openPicker),
                ) {
                    RpcnAvatarImage(Modifier.size(78.dp).clip(CircleShape))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        name ?: str("rpcn.profile.none"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatePill(state)
                    Text(
                        str("rpcn.profile.server").format(server),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        str("rpcn.profile.avatarHint"),
                        style = MaterialTheme.typography.labelSmall,
                        color = primary,
                    )
                }
            }

            if (showActions) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    actions()
                }
            }
            NoticeText(notice)
        }
    }
}

/** An avatar inside a slowly turning ring, as the RPCN page and the avatar picker show the player's. */
@Composable
internal fun RpcnAvatarRing(diameter: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val colors = listOf(primary, MaterialTheme.colorScheme.tertiary, primary.copy(alpha = 0.12f), primary)
    val ring = rememberInfiniteTransition(label = "avatar-ring")
    val angle by ring.animateFloat(0f, 360f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "angle")

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        // The angle is read while drawing only, so turning it redraws the ring and recomposes nothing
        Canvas(Modifier.size(diameter)) {
            rotate(angle) {
                drawCircle(Brush.sweepGradient(colors), radius = size.minDimension / 2f - 3.dp.toPx(), style = Stroke(5.dp.toPx()))
            }
        }
        content()
    }
}

/** Signed in, saved, or nothing yet, as a small coloured pill. */
@Composable
private fun StatePill(state: ProfileState) {
    val (color, label) = when (state) {
        ProfileState.SignedIn -> Color(0xFF3DDC84) to str("rpcn.profile.signedIn")
        ProfileState.Saved -> MaterialTheme.colorScheme.primary to str("rpcn.profile.saved")
        ProfileState.None -> MaterialTheme.colorScheme.onSurfaceVariant to str("rpcn.profile.notSetUp")
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** One titled card. */
@Composable
private fun FormCard(title: String, description: String?, content: @Composable () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                if (description != null) {
                    Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

/**
 * A text field touch types into as any other, and the pad's A opens the controller keyboard for
 * (LibraryKeyboard), since a pad cannot type into the field itself. A password shows dots, with
 * Show to check what was typed.
 */
@Composable
private fun Field(
    id: String,
    label: String,
    value: String,
    masked: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        visualTransformation = if (masked && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (masked) KeyboardType.Password else keyboard),
        trailingIcon = if (masked) {
            { TextButton(onClick = { shown = !shown }) { Text(str(if (shown) "rpcn.password.hide" else "rpcn.password.show")) } }
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .controllerFocusable(id, onConfirm = { if (enabled) LibraryKeyboard.open(value, onChange, label, masked) }),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NoticeText(notice: AccountNotice?) {
    if (notice == null) return
    Text(
        notice.text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (notice.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun PrimaryAction(id: String, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.height(46.dp).controllerFocusable(id, onConfirm = { if (enabled) onClick() }),
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SecondaryAction(id: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.height(46.dp).controllerFocusable(id, onConfirm = { if (enabled) onClick() }),
    ) { Text(label) }
}

/** The quieter ways on from a card: back, forgot, have a code. */
@Composable
private fun LinkRow(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { content() }
}

@Composable
private fun LinkAction(id: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.controllerFocusable(id, onConfirm = { if (enabled) onClick() }),
    ) { Text(label) }
}

/** A saved server: lit when it is the one in use, with Remove unless it is the official one. */
@Composable
private fun ServerRow(
    name: String,
    address: String,
    selected: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onPick)
            .controllerFocusable("rpcn.host.$address", onConfirm = { if (enabled) onPick() })
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (selected) primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRemove != null) {
            TextButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.controllerFocusable("rpcn.host.remove.$address", onConfirm = { if (enabled) onRemove() }),
            ) { Text(str("rpcn.hosts.remove")) }
        }
    }
}
