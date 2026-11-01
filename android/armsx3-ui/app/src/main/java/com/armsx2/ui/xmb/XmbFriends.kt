package com.armsx2.ui.xmb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.ui.home.LibraryKeyboard
import com.armsx2.ui.settings.RpcnAvatarPicker
import com.armsx2.ui.settings.RpcnFriends
import com.armsx2.ui.settings.RpcnInvite
import com.armsx2.ui.settings.parseRpcnFriends
import com.armsx2.ui.settings.parseRpcnInvites
import com.armsx2.ui.settings.rpcnSessionDispatcher
import com.armsx2.ui.settings.rpcnSessionExecutor
import com.armsx3.Rpcs3Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Typing in the XMB, on the app's pad keyboard (LibraryKeyboard), as the console types on its own
 * on-screen keyboard: [ask] opens it, and what was typed is handed over when it closes.
 */
object XmbKeyboard {
    private var pending: ((String) -> Unit)? = null
    private var typed = ""

    fun ask(label: String, initial: String = "", masked: Boolean = false, onDone: (String) -> Unit) {
        typed = initial
        pending = onDone
        LibraryKeyboard.open(initial, { typed = it }, label, masked)
    }

    /** The keyboard has closed: what was typed goes to whoever asked. */
    internal fun closed() {
        val done = pending ?: return
        pending = null
        done(typed)
    }
}

/** Hands the XMB's typing over as the keyboard closes; composed with every XMB. */
@Composable
internal fun XmbKeyboardWatcher() {
    LaunchedEffect(Unit) {
        snapshotFlow { LibraryKeyboard.visible.value }.collect { visible -> if (!visible) XmbKeyboard.closed() }
    }
}

/** RPCN as the XMB reads it, kept between showings so the column does not empty each time. */
internal object XmbRpcn {
    val friends = mutableStateOf<RpcnFriends?>(null)
    val invites = mutableStateOf<List<RpcnInvite>>(emptyList())

    /** Account state: whether one is saved, who is signed in, and the server. */
    val configured = mutableStateOf(false)
    val signedInAs = mutableStateOf("")
    val savedName = mutableStateOf("")
    val host = mutableStateOf("np.rpcs3.net")
    val ipv6 = mutableStateOf(false)

    /** The saved servers, as (name, address). */
    val hosts = mutableStateOf<List<Pair<String, String>>>(emptyList())

    /** Read the account. IO thread. */
    fun readAccount() {
        runCatching {
            val o = JSONObject(Rpcs3Bridge.rpcnStatus())
            configured.value = o.optBoolean("configured", false)
            signedInAs.value = if (o.optBoolean("authentified", false)) o.optString("onlineName", "") else ""
        }
        runCatching {
            val o = JSONObject(Rpcs3Bridge.rpcnGetConfig())
            host.value = o.optString("host").ifBlank { "np.rpcs3.net" }
            ipv6.value = o.optBoolean("ipv6", false)
            savedName.value = o.optString("npid", "")
            val list = o.optJSONArray("hosts")
            hosts.value = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                val e = list?.optJSONObject(i) ?: return@mapNotNull null
                val desc = e.optString("desc", "")
                val addr = e.optString("host", "")
                if (addr.isBlank()) null else (desc.ifBlank { addr } to addr)
            }
        }
    }
}

/**
 * RPCN, where the console's Friends column was, at home and over a game: Account Management,
 * Add a Friend (typed on the pad keyboard), friend requests to accept or decline, game invites to
 * join (in a game, for that game), and every friend with whether they are online and what they
 * are playing. Read every two seconds while [shown], with a friends session held open as the
 * Friends screen holds one.
 */
@Composable
internal fun xmbRpcnColumn(controller: XmbController, shown: Boolean, inGame: Boolean): XmbColumn {
    val scope = rememberCoroutineScope()
    val full = "explore_plugin_full"
    rememberRpcnPolling(shown, inGame)
    val friends = XmbRpcn.friends.value
    val ok = fw(full, "msg_ok", "xmb.ok")
    val yes = fw(full, "msg_yes", "xmb.yes")
    val no = fw(full, "msg_no", "xmb.no")
    val online = fw(full, "msg_online", "xmb.rpcn.online")
    val offline = str("xmb.rpcn.offline")
    val playing = str("rpcn.friends.playing")
    val wantsToAdd = str("rpcn.friends.wantsToAdd")
    val waiting = str("rpcn.friends.waiting")
    val accept = str("rpcn.friends.accept")
    val decline = str("rpcn.friends.decline")
    val cancel = str("rpcn.friends.cancel")
    val remove = str("rpcn.friends.remove")
    val removeAsk = str("rpcn.friends.removeConfirm")
    val addTitle = fw(full, "msg_friend_register", "xmb.rpcn.add")
    val userLabel = str("rpcn.friends.username")
    val needName = str("rpcn.friends.need.name")
    val msgAdded = str("rpcn.friends.added")
    val msgAccepted = str("rpcn.friends.accepted")
    val msgDeclined = str("rpcn.friends.declined")
    val msgCancelled = str("rpcn.friends.cancelled")
    val msgRemoved = str("rpcn.friends.removed")
    val inviteFrom = str("rpcn.invites.from")
    val join = str("rpcn.invites.join")
    val msgJoining = str("rpcn.invites.joining")
    val msgInviteDeclined = str("rpcn.invites.declined")
    val friendIcon = XmbIcon("explore_category_friend", "tex_Avatar_Default", "🙂")
    val requestIcon = XmbIcon("explore_category_friend", "item_tex_NewFriend", "➕")
    val inviteIcon = XmbIcon("explore_category_friend", "item_tex_Invitation", "✉")

    // "" from the core is success; anything else is its own message, which says more than ours would.
    fun act(done: String, call: () -> String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(call).getOrElse { it.message.orEmpty() } }
            controller.tell(result.ifBlank { done }, ok)
            XmbRpcn.friends.value = parseRpcnFriends(withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnGetFriends() })
        }
    }

    val items = buildList {
        add(xmbRpcnAccount(controller))
        // Hosting belongs to the game being played: only over a game.
        if (inGame) add(xmbRemotePlayNode(controller))
        add(
            XmbNode.Action("rpcn.add", addTitle, requestIcon) {
                XmbKeyboard.ask(userLabel) { typed ->
                    val name = typed.trim()
                    if (name.isEmpty()) {
                        controller.tell(needName, ok)
                    } else {
                        // Adding someone who already asked is accepting them, and the server says so.
                        val asked = friends?.received?.contains(name) == true
                        act(if (asked) msgAccepted.format(name) else msgAdded.format(name)) { Rpcs3Bridge.rpcnAddFriend(name) }
                    }
                }
            },
        )
        // Game invites, for the game that is running: join or decline.
        if (inGame) XmbRpcn.invites.value.forEach { invite ->
            add(
                XmbNode.Action("rpcn.invite.${invite.id}", inviteFrom.format(invite.from), inviteIcon, invite.subject.ifBlank { invite.body }) {
                    controller.dialog = XmbController.Dialog(
                        listOf(inviteFrom.format(invite.from), invite.subject, invite.body).filter { it.isNotBlank() }.joinToString("\n"),
                        listOf(
                            XmbOption(join) { act(msgJoining.format(invite.from)) { Rpcs3Bridge.rpcnAcceptInvite(invite.id) } },
                            XmbOption(decline) { act(msgInviteDeclined.format(invite.from)) { Rpcs3Bridge.rpcnDeclineInvite(invite.id) } },
                        ),
                    )
                    XmbSounds.play(XmbSounds.Sfx.OPTION)
                },
            )
        }
        // Requests from others: accept or decline. Requests this account sent: cancel.
        friends?.received?.forEach { name ->
            add(
                XmbNode.Action("rpcn.received.$name", name, requestIcon, wantsToAdd) {
                    controller.dialog = XmbController.Dialog(
                        "$name\n$wantsToAdd",
                        listOf(
                            XmbOption(accept) { act(msgAccepted.format(name)) { Rpcs3Bridge.rpcnAddFriend(name) } },
                            XmbOption(decline) { act(msgDeclined.format(name)) { Rpcs3Bridge.rpcnRemoveFriend(name) } },
                        ),
                    )
                    XmbSounds.play(XmbSounds.Sfx.OPTION)
                },
            )
        }
        friends?.sent?.forEach { name ->
            add(
                XmbNode.Action("rpcn.sent.$name", name, requestIcon, waiting, options = listOf(XmbOption(cancel) {
                    act(msgCancelled.format(name)) { Rpcs3Bridge.rpcnRemoveFriend(name) }
                })) {},
            )
        }
        friends?.friends?.forEach { f ->
            val sub = when {
                !f.online -> offline
                f.title.isNotBlank() -> playing.format(f.title)
                else -> online
            }
            val removeOption = XmbOption(remove) {
                controller.ask(removeAsk.format(f.name), yes, no, focusNo = true) {
                    act(msgRemoved.format(f.name)) { Rpcs3Bridge.rpcnRemoveFriend(f.name) }
                }
            }
            add(XmbNode.Action("rpcn.friend.${f.name}", f.name, friendIcon, sub, options = listOf(removeOption)) {
                controller.options = XmbController.Options(f.name, listOf(removeOption))
            })
        }
        // Discord friends in ARMSX3 now, after the RPCN ones, and Discord itself.
        addAll(xmbDiscordNodes())
    }
    return XmbColumn(
        key = "rpcn",
        title = str("rpcn.screen.title"),
        icon = XmbIcon("xmb_ingame", "tex_friend", "👥"),
        items = items,
    )
}

/**
 * Account Management: who is signed in and on which server, Sign In (typed on the pad keyboard),
 * the avatar (the app's own picker, kept as it is), and the RPCN screen for creating an account,
 * a forgotten password and servers.
 */
@Composable
private fun xmbRpcnAccount(controller: XmbController): XmbNode {
    val scope = rememberCoroutineScope()
    val ok = fw("explore_plugin_full", "msg_ok", "xmb.ok")
    val title = fw("explore_plugin_full", "msg_account_manage", "xmb.rpcn.account")
    val icon = XmbIcon("explore_category_psn", "item_tex_account", "🛰")
    val none = str("rpcn.profile.none")
    val signedIn = str("rpcn.profile.signedIn")
    val saved = str("rpcn.profile.saved")
    val notSetUp = str("rpcn.profile.notSetUp")
    val server = str("rpcn.profile.server")
    val check = str("rpcn.action.check")
    val signInTitle = str("rpcn.action.signIn")
    val switchTitle = str("rpcn.action.switch")
    val userLabel = str("rpcn.username")
    val passLabel = str("rpcn.password")
    val avatar = com.armsx2.ui.settings.rememberCurrentAvatar()
    val avatarTitle = str("xmb.rpcn.avatar")
    val avatarDesc = str("rpcn.profile.avatarHint")
    val serversTitle = str("rpcn.hosts.title")
    val serversDesc = str("rpcn.hosts.description")
    val addServer = str("rpcn.hosts.add")
    val serverName = str("rpcn.hosts.name")
    val serverAddress = str("rpcn.hosts.address")
    val serverAdded = str("rpcn.hosts.added")
    val serverSelected = str("rpcn.hosts.selected")
    val resetServers = str("rpcn.hosts.reset")
    val resetDone = str("rpcn.hosts.resetDone")
    val removeServer = str("rpcn.hosts.remove")
    val removed = str("rpcn.hosts.removed")
    val moreTitle = str("xmb.rpcn.more")
    val moreDesc = str("xmb.rpcn.more.desc")
    val (on, off) = xmbOnOff()
    val createTitle = str("rpcn.action.create")
    val createNote = str("rpcn.username.rules")
    val emailLabel = str("rpcn.email")
    val created = str("rpcn.created")
    val haveCode = str("rpcn.signIn.haveCode")
    val tokenLabel = str("rpcn.token")
    val resend = str("rpcn.token.resend")
    val tokenSent = str("rpcn.token.sent")
    val forgot = str("rpcn.signIn.forgot")
    val resetSent = str("rpcn.reset.sent")
    val newPassword = str("rpcn.reset.codeTitle")
    val resetCode = str("rpcn.reset.code")
    val newPassLabel = str("rpcn.reset.newPassword")
    val ipv6Label = str("rpcn.ipv6.label")
    val ipv6Desc = str("rpcn.ipv6.description")
    val trophiesTitle = str("rpcn.trophies.title")
    val trophiesDesc = str("rpcn.trophies.description")
    val trophiesConfirm = str("rpcn.trophies.confirm")
    val trophiesDeleted = str("rpcn.trophies.deleted")
    val deleteWord = str("action.delete")
    val cancelWord = str("action.cancel")

    fun perform(done: String, call: () -> String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(call).getOrElse { it.message.orEmpty() } }
            withContext(Dispatchers.IO) { XmbRpcn.readAccount() }
            controller.tell(result.ifBlank { done }, ok, error = result.isNotBlank())
        }
    }

    fun signIn() {
        XmbKeyboard.ask(userLabel, XmbRpcn.savedName.value) { user ->
            if (user.isBlank()) return@ask
            XmbKeyboard.ask(passLabel, masked = true) { pass ->
                if (pass.isEmpty()) return@ask
                perform(signedIn) { Rpcs3Bridge.rpcnSignIn(XmbRpcn.host.value.trim(), user.trim(), pass, "") }
            }
        }
    }

    // The rest of the account, each step typed on the pad keyboard: a new account, its code from
    // the email, a forgotten password and the code for a new one.
    fun account(onDone: (String, String) -> Unit) {
        XmbKeyboard.ask(userLabel, XmbRpcn.savedName.value) { user ->
            if (user.isBlank()) return@ask
            XmbKeyboard.ask(passLabel, masked = true) { pass -> if (pass.isNotEmpty()) onDone(user.trim(), pass) }
        }
    }

    fun create() {
        XmbKeyboard.ask(userLabel) { user ->
            if (user.isBlank()) return@ask
            XmbKeyboard.ask(passLabel, masked = true) { pass ->
                if (pass.isEmpty()) return@ask
                XmbKeyboard.ask(emailLabel) { email ->
                    if (email.isBlank()) return@ask
                    perform(created) {
                        Rpcs3Bridge.rpcnSetConfig(XmbRpcn.host.value.trim(), "", "", "")
                        Rpcs3Bridge.rpcnCreateAccount(user.trim(), pass, user.trim(), email.trim())
                    }
                }
            }
        }
    }

    fun activate() = account { user, pass ->
        XmbKeyboard.ask(tokenLabel) { code ->
            if (code.isBlank()) return@ask
            perform(signedIn) { Rpcs3Bridge.rpcnSignIn(XmbRpcn.host.value.trim(), user, pass, code.trim()) }
        }
    }

    fun resendCode() = account { user, pass ->
        perform(tokenSent) {
            Rpcs3Bridge.rpcnSetConfig(XmbRpcn.host.value.trim(), "", "", "")
            Rpcs3Bridge.rpcnResendToken(user, pass)
        }
    }

    fun forgotPassword() {
        XmbKeyboard.ask(userLabel, XmbRpcn.savedName.value) { user ->
            if (user.isBlank()) return@ask
            XmbKeyboard.ask(emailLabel) { email ->
                if (email.isBlank()) return@ask
                perform(resetSent) {
                    Rpcs3Bridge.rpcnSetConfig(XmbRpcn.host.value.trim(), "", "", "")
                    Rpcs3Bridge.rpcnSendResetToken(user.trim(), email.trim())
                }
            }
        }
    }

    fun resetPassword() {
        XmbKeyboard.ask(userLabel, XmbRpcn.savedName.value) { user ->
            if (user.isBlank()) return@ask
            XmbKeyboard.ask(resetCode) { code ->
                if (code.isBlank()) return@ask
                XmbKeyboard.ask(newPassLabel, masked = true) { pass ->
                    if (pass.isEmpty()) return@ask
                    perform(signedIn) {
                        Rpcs3Bridge.rpcnSetConfig(XmbRpcn.host.value.trim(), "", "", "")
                        Rpcs3Bridge.rpcnResetPassword(user.trim(), code.trim(), pass).ifBlank {
                            Rpcs3Bridge.rpcnSignIn(XmbRpcn.host.value.trim(), user.trim(), pass, "")
                        }
                    }
                }
            }
        }
    }

    val reader: @Composable () -> Unit = {
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { XmbRpcn.readAccount() } }
    }
    // The avatar as XMB lists of its own (see xmbAvatarNode), not the app's picker.
    val avatarNode = com.armsx2.ui.settings.xmbAvatarNode(avatarTitle, avatarDesc, icon)
    // The player's own avatar and name on it, as the console's column leads with its user.
    val who = XmbRpcn.signedInAs.value.ifBlank { XmbRpcn.savedName.value }
    val itemIcon = avatar?.let { XmbIcon(bitmap = it, glyph = icon.glyph, shadow = false) } ?: icon
    val sub = listOfNotNull(
        who.takeIf { it.isNotBlank() },
        when {
            XmbRpcn.signedInAs.value.isNotBlank() -> signedIn
            XmbRpcn.configured.value -> saved
            else -> notSetUp
        },
    ).joinToString("   ")
    return XmbNode.Folder("rpcn.account", title, itemIcon, sub, live = true, host = reader) {
        val who = XmbRpcn.signedInAs.value.ifBlank { XmbRpcn.savedName.value }
        val state = when {
            XmbRpcn.signedInAs.value.isNotBlank() -> signedIn
            XmbRpcn.configured.value -> saved
            else -> notSetUp
        }
        listOf(
            XmbNode.Action("rpcn.account.who", who.ifBlank { none }, icon, "$state   ${server.format(XmbRpcn.host.value)}") {
                if (XmbRpcn.configured.value) perform(signedIn) { Rpcs3Bridge.rpcnTestLogin() } else signIn()
            }.withOptions(if (XmbRpcn.configured.value) listOf(XmbOption(check) { perform(signedIn) { Rpcs3Bridge.rpcnTestLogin() } }) else emptyList()),
            XmbNode.Action("rpcn.account.signIn", if (XmbRpcn.configured.value) switchTitle else signInTitle, icon) { signIn() },
            avatarNode,
            // The server, from the saved ones; adding one is typed on the pad keyboard.
            run {
                val hosts = XmbRpcn.hosts.value
                val labels = hosts.map { (desc, addr) -> if (desc == addr) addr else "$desc ($addr)" }
                XmbNode.Folder("rpcn.account.servers", serversTitle, icon, server.format(XmbRpcn.host.value) + "   " + serversDesc, live = true) {
                    buildList {
                        val now = XmbRpcn.hosts.value
                        if (now.isNotEmpty()) add(
                            XmbNode.Setting(
                                "rpcn.server", serversTitle, icon,
                                XmbEditor.Choice(labels, { now.indexOfFirst { it.second.equals(XmbRpcn.host.value.trim(), true) }.coerceAtLeast(0) }) { i ->
                                    val addr = now.getOrNull(i)?.second ?: return@Choice
                                    perform(serverSelected) { Rpcs3Bridge.rpcnSetConfig(addr, "", "", ""); "" }
                                },
                                { server.format(XmbRpcn.host.value) },
                            ),
                        )
                        add(XmbNode.Action("rpcn.server.add", addServer, icon) {
                            XmbKeyboard.ask(serverName) { name ->
                                if (name.isBlank()) return@ask
                                XmbKeyboard.ask(serverAddress) { addr ->
                                    if (addr.isBlank()) return@ask
                                    perform(serverAdded) { Rpcs3Bridge.rpcnAddHost(name.trim(), addr.trim()) }
                                }
                            }
                        })
                        now.filterNot { it.second.equals("np.rpcs3.net", true) }.forEach { (desc, addr) ->
                            add(XmbNode.Action("rpcn.server.remove.$addr", "$removeServer: $desc", icon, addr) {
                                perform(removed) { Rpcs3Bridge.rpcnDelHost(desc, addr) }
                            })
                        }
                        add(XmbNode.Action("rpcn.server.reset", resetServers, icon) {
                            perform(resetDone) { Rpcs3Bridge.rpcnResetHosts(); "" }
                        })
                    }
                }
            },
            XmbNode.Folder("rpcn.account.more", moreTitle, icon, moreDesc) {
                listOf(
                    XmbNode.Action("rpcn.more.create", createTitle, icon, createNote) { create() },
                    XmbNode.Action("rpcn.more.activate", haveCode, icon) { activate() },
                    XmbNode.Action("rpcn.more.resend", resend, icon) { resendCode() },
                    XmbNode.Action("rpcn.more.forgot", forgot, icon) { forgotPassword() },
                    XmbNode.Action("rpcn.more.reset", newPassword, icon) { resetPassword() },
                    XmbNode.Setting(
                        "rpcn.more.ipv6", ipv6Label, icon,
                        XmbEditor.Toggle({ XmbRpcn.ipv6.value }) { wanted ->
                            XmbRpcn.ipv6.value = wanted
                            scope.launch { withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnSetIpv6(wanted) } }
                        },
                        { if (XmbRpcn.ipv6.value) on else off },
                        description = ipv6Desc,
                    ),
                    XmbNode.Action("rpcn.more.trophies", trophiesTitle, icon, trophiesDesc) {
                        controller.ask(trophiesConfirm, deleteWord, cancelWord, focusNo = true) {
                            perform(trophiesDeleted) { Rpcs3Bridge.rpcnDeleteTrophies() }
                        }
                    },
                )
            },
        )
    }
}

private fun XmbNode.Action.withOptions(options: List<XmbOption>): XmbNode.Action =
    if (options.isEmpty()) this else XmbNode.Action(key, title, icon, subtitle, options, onConfirm = onConfirm)

/**
 * The friend list (and, in a game, its invites) while [shown]: with an account saved, a session
 * held open for as long as the column is shown (one open and one close per showing, as the
 * Friends screen does), and everything read every two seconds.
 */
@Composable
private fun rememberRpcnPolling(shown: Boolean, inGame: Boolean) {
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        withContext(Dispatchers.IO) { XmbRpcn.readAccount() }
        if (!XmbRpcn.configured.value) {
            XmbRpcn.friends.value = null
            return@LaunchedEffect
        }
        try {
            // Runs to the end even when the column is left meanwhile, so the close below always
            // has an open to match.
            withContext(NonCancellable + rpcnSessionDispatcher) { Rpcs3Bridge.rpcnFriendsSession(true) }
            while (true) {
                XmbRpcn.friends.value = parseRpcnFriends(withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnGetFriends() })
                if (inGame) XmbRpcn.invites.value = parseRpcnInvites(withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnGetInvites() })
                delay(2_000)
            }
        } finally {
            rpcnSessionExecutor.execute { Rpcs3Bridge.rpcnFriendsSession(false) }
        }
    }
}

/**
 * Remote Play hosting, in the in-game RPCN column, as the pause menu's Friends panel has it: host on
 * this Wi-Fi or invite online (a short code to share, or a long one with the friend's reply typed
 * back), let a friend in or not, and stop. A friend asking to join is also asked in a dialog as the
 * XMB comes up (XmbInGameMenu).
 */
@Composable
private fun xmbRemotePlayNode(controller: XmbController): XmbNode {
    val context = androidx.compose.ui.platform.LocalContext.current
    val status = com.armsx2.remoteplay.RemotePlayHost.status.value
    val localNetwork = com.armsx2.remoteplay.rememberLocalNetworkAccess()
    val ok = fw("explore_plugin_full", "msg_ok", "xmb.ok")
    val title = str("remoteplay.title")
    val icon = XmbIcon("explore_category_network", "tex_premo", "\uD83D\uDCE1")
    val wifi = str("remoteplay.host.wifi")
    val invite = str("remoteplay.host.invite")
    val stop = str("remoteplay.host.stop")
    val waiting = str("remoteplay.host.waiting")
    val address = str("remoteplay.host.address")
    val connecting = str("remoteplay.host.connecting")
    val playing = str("remoteplay.host.playing")
    val preparing = str("remoteplay.invite.preparing")
    val step1 = str("remoteplay.invite.step1")
    val lobbyCode = str("remoteplay.invite.lobbyCode")
    val lobbyShare = str("remoteplay.invite.lobbyShare")
    val longShare = str("remoteplay.invite.share")
    val share = str("remoteplay.code.share")
    val copy = str("remoteplay.code.copy")
    val cancel = str("remoteplay.invite.cancel")
    val reply = str("remoteplay.invite.reply")
    val badReply = str("remoteplay.invite.badReply")
    val asking = str("remoteplay.request.title")
    val accept = str("remoteplay.request.accept")
    val decline = str("remoteplay.request.decline")
    val explain = str("remoteplay.host.explain")
    val host = com.armsx2.remoteplay.RemotePlayHost

    // A failure from a press here is said in a dialog: the folder's own line is out of sight while
    // its list is open.
    val asked = androidx.compose.runtime.remember { booleanArrayOf(false) }
    androidx.compose.runtime.LaunchedEffect(status) {
        if (status is com.armsx2.remoteplay.RemotePlayHost.Status.Failed && asked[0]) controller.tell(status.message, ok)
        if (status !is com.armsx2.remoteplay.RemotePlayHost.Status.Off) asked[0] = false
    }
    fun hostWifi() {
        asked[0] = true
        localNetwork { allowed -> if (allowed) host.startWifi(context) else host.refuseLocalNetwork() }
    }
    fun hostOnline() {
        asked[0] = true
        localNetwork { host.createInvite(context) }
    }
    fun copy(text: String) {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(title, text))
    }
    val startWifi = XmbNode.Action("remote.wifi", wifi, icon) { hostWifi() }
    val startOnline = XmbNode.Action("remote.invite", invite, icon) { hostOnline() }
    val stopNode = XmbNode.Action("remote.stop", stop, icon) { host.stop() }
    // Where hosting stands, at the top of the list: the address to give, the code, or what went wrong.
    fun line(text: String) = XmbNode.Action("remote.state", text.lineSequence().first(), icon, text.lines().drop(1).joinToString(" ").ifBlank { null }) {
        controller.tell(text, ok)
    }

    // One list that reads where hosting stands each time it is drawn, so it follows a press made in it
    // (an open list keeps the folder it was opened from).
    fun items(st: com.armsx2.remoteplay.RemotePlayHost.Status): List<XmbNode> = when (st) {
        com.armsx2.remoteplay.RemotePlayHost.Status.Off -> listOf(startWifi, startOnline)
        is com.armsx2.remoteplay.RemotePlayHost.Status.Failed -> listOf(line(st.message), startWifi, startOnline)
        is com.armsx2.remoteplay.RemotePlayHost.Status.Waiting ->
            listOf(line(listOfNotNull(waiting, st.address?.let { address.format(it) }).joinToString("   ")), startOnline, stopNode)
        is com.armsx2.remoteplay.RemotePlayHost.Status.Inviting -> {
            val code = st.lobbyCode
            val long = st.code
            when {
                code != null -> listOf(
                    XmbNode.Action("remote.code", code, icon, lobbyCode) { controller.tell("$lobbyCode\n$code", ok) },
                    XmbNode.Action("remote.share", share, icon) { com.armsx2.remoteplay.shareText(context, lobbyShare.format(code)) },
                    XmbNode.Action("remote.copy", copy, icon) { copy(code) },
                    XmbNode.Action("remote.cancel", cancel, icon) { host.stop() },
                )
                long != null -> listOf(
                    line(step1),
                    XmbNode.Action("remote.share", share, icon) { com.armsx2.remoteplay.shareText(context, longShare.format(long)) },
                    XmbNode.Action("remote.copy", copy, icon) { copy(long) },
                    XmbNode.Action("remote.reply", reply, icon) {
                        XmbKeyboard.ask(reply) { typed -> if (typed.isNotBlank() && !host.acceptReply(typed)) controller.tell(badReply, ok) }
                    },
                    XmbNode.Action("remote.cancel", cancel, icon) { host.stop() },
                )
                else -> listOf(line(preparing), XmbNode.Action("remote.cancel", cancel, icon) { host.stop() })
            }
        }
        is com.armsx2.remoteplay.RemotePlayHost.Status.Asking -> listOf(
            line(asking.format(st.viewer)),
            XmbNode.Action("remote.accept", accept, icon) { host.accept() },
            XmbNode.Action("remote.decline", decline, icon) { host.decline() },
        )
        is com.armsx2.remoteplay.RemotePlayHost.Status.Connecting -> listOf(line(connecting.format(st.viewer)), stopNode)
        is com.armsx2.remoteplay.RemotePlayHost.Status.Playing -> listOf(line(playing.format(st.viewer)), stopNode)
    }
    val summary = when (status) {
        com.armsx2.remoteplay.RemotePlayHost.Status.Off -> explain.substringBefore(". ") + "."
        is com.armsx2.remoteplay.RemotePlayHost.Status.Failed -> status.message
        is com.armsx2.remoteplay.RemotePlayHost.Status.Waiting -> listOfNotNull(waiting, status.address?.let { address.format(it) }).joinToString("   ")
        is com.armsx2.remoteplay.RemotePlayHost.Status.Inviting -> status.lobbyCode?.let { "$lobbyCode $it" } ?: if (status.code != null) step1 else preparing
        is com.armsx2.remoteplay.RemotePlayHost.Status.Asking -> asking.format(status.viewer)
        is com.armsx2.remoteplay.RemotePlayHost.Status.Connecting -> connecting.format(status.viewer)
        is com.armsx2.remoteplay.RemotePlayHost.Status.Playing -> playing.format(status.viewer)
    }
    return XmbNode.Folder("remote", title, icon, summary, live = true) { items(host.status.value) }
}

/**
 * Discord, as the Friends panel has it: friends of the player's who are in ARMSX3 now, each with
 * what they are playing, then Discord itself (connect or disconnect, and the two status settings).
 * Nothing when the build has no Discord.
 */
@Composable
private fun xmbDiscordNodes(): List<XmbNode> {
    if (!com.armsx2.DiscordPresence.available()) return emptyList()
    val (on, off) = xmbOnOff()
    val status = com.armsx2.DiscordPresence.status.value
    val friends = com.armsx2.DiscordPresence.friends.value
    val me = com.armsx2.DiscordPresence.self.value
    val icon = XmbIcon(model = com.armsx2.R.drawable.ic_discord, glyph = "\uD83D\uDCAC")
    val inLibrary = str("friends.inLibrary")
    val inGame = str("friends.playing")
    val connected = str("friends.connected")
    val connecting = str("friends.connecting")
    val connect = str("friends.connect")
    val disconnect = str("friends.disconnect")
    val explain = str("friends.explain")
    val notify = str("friends.notify")
    val notifyDesc = str("friends.notify.desc")
    val showGame = str("friends.showGame")
    val showGameDesc = str("friends.showGame.desc")
    val nobody = str("friends.nobody")
    return buildList {
        friends.forEach { f ->
            add(
                XmbNode.Action(
                    "discord.friend.${f.name}",
                    f.name,
                    // Their picture, and the cover of the game they are playing in its corner.
                    if (f.avatarUrl.isNotBlank()) XmbIcon(model = f.avatarUrl, glyph = "\uD83D\uDE42", shadow = false, inset = f.coverUrl)
                    else XmbIcon(model = com.armsx2.R.drawable.ic_discord, glyph = "\uD83D\uDCAC", inset = f.coverUrl),
                    "Discord   " + when {
                        f.inLibrary -> inLibrary
                        f.game.isNotBlank() -> f.game
                        else -> inGame
                    },
                ) {},
            )
        }
        val sub = when (status) {
            com.armsx2.DiscordPresence.CONNECTED -> listOfNotNull(me?.name, connected).joinToString("   ")
            com.armsx2.DiscordPresence.AUTHORIZING, com.armsx2.DiscordPresence.CONNECTING -> connecting
            else -> com.armsx2.DiscordPresence.error.value ?: explain.substringBefore(". ") + "."
        }
        add(
            XmbNode.Folder("discord", "Discord", me?.avatarUrl?.takeIf { it.isNotBlank() }?.let { XmbIcon(model = it, glyph = icon.glyph, shadow = false) } ?: icon, sub, live = true) {
                val connectedNow = com.armsx2.DiscordPresence.status.value == com.armsx2.DiscordPresence.CONNECTED
                buildList {
                    if (connectedNow) {
                        add(
                            XmbNode.Setting(
                                "discord.notify", notify, icon,
                                XmbEditor.Toggle({ com.armsx2.DiscordPresence.notifyInGame }) { com.armsx2.DiscordPresence.notifyInGame = it },
                                { if (com.armsx2.DiscordPresence.notifyInGame) on else off }, notifyDesc,
                            ),
                        )
                        add(
                            XmbNode.Setting(
                                "discord.showGame", showGame, icon,
                                XmbEditor.Toggle({ com.armsx2.DiscordPresence.showGameInStatus }) { com.armsx2.DiscordPresence.showGameInStatus = it },
                                { if (com.armsx2.DiscordPresence.showGameInStatus) on else off }, showGameDesc,
                            ),
                        )
                        if (com.armsx2.DiscordPresence.friends.value.isEmpty()) add(XmbNode.Action("discord.nobody", nobody, icon) {})
                        add(XmbNode.Action("discord.disconnect", disconnect, icon) { com.armsx2.DiscordPresence.signOut() })
                    } else {
                        add(XmbNode.Action("discord.connect", connect, icon, explain) { com.armsx2.DiscordPresence.authorize() })
                    }
                }
            },
        )
    }
}
