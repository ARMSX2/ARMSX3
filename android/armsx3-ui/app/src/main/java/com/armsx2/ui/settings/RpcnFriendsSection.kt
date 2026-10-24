package com.armsx2.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.armsx2.i18n.str
import com.armsx2.ui.common.PadInput
import com.armsx2.ui.home.LibraryKeyboard
import com.armsx3.Rpcs3Bridge
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * RPCN friends: add, remove, see who is online and what they are playing, and answer friend
 * requests.
 *
 * The core has had add_friend/remove_friend and the friend list all along; nothing reached them
 * from Android, so there was no way to add a friend at all short of doing it from a game that
 * offers its own friend UI.
 */
@Composable
fun RpcnFriendsSection() {
    Column(Modifier.fillMaxWidth()) {
        Text(
            str("rpcn.friends.title"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        RpcnFriendsList(controllerIdPrefix = "rpcn.friends", canAdd = true, canRemove = true, showInvites = false)
    }
}

/** A friend, and their presence as their game sets it: everything RPCN sends about one. */
internal data class RpcnFriend(
    val name: String,
    val online: Boolean,
    /** The game, as its presence names it. */
    val title: String = "",
    val status: String = "",
    val comment: String = "",
)

/** One read of the RPCN friend list: friends, and the requests waiting on either side. */
internal data class RpcnFriends(
    val signedIn: Boolean = false,
    /** Online first. */
    val friends: List<RpcnFriend> = emptyList(),
    /** Requests from others, waiting on this account. */
    val received: List<String> = emptyList(),
    /** Requests this account sent, waiting on them. */
    val sent: List<String> = emptyList(),
)

/** A game invite that has not been used yet. */
internal data class RpcnInvite(val id: Long, val from: String, val subject: String, val body: String)

internal fun parseRpcnFriends(json: String): RpcnFriends = runCatching {
    val o = JSONObject(json)

    fun names(key: String): List<String> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
            .sortedBy { it.lowercase() }
    }

    val arr = o.optJSONArray("friends")
    RpcnFriends(
        signedIn = o.optBoolean("signedIn"),
        friends = (0 until (arr?.length() ?: 0)).map { i ->
            val e = arr!!.getJSONObject(i)
            RpcnFriend(
                name = e.optString("npid"),
                online = e.optBoolean("online"),
                title = e.optString("title"),
                status = e.optString("status"),
                comment = e.optString("comment"),
            )
        }.sortedWith(compareByDescending<RpcnFriend> { it.online }.thenBy { it.name.lowercase() }),
        received = names("received"),
        sent = names("sent"),
    )
}.getOrDefault(RpcnFriends())

internal fun parseRpcnInvites(json: String): List<RpcnInvite> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val e = arr.getJSONObject(i)
        RpcnInvite(e.optLong("id"), e.optString("from"), e.optString("subject"), e.optString("body"))
    }
}.getOrDefault(emptyList())

/**
 * Opening and closing the held session, one at a time and in order.
 *
 * Closing on leave cannot use the screen's coroutine scope (it is cancelled by then) and tears a
 * client down, which joins threads, so it cannot run on the main thread either. A single thread
 * also keeps a close from overtaking a sign-in that is still in flight when the screen goes away:
 * the native side counts opens, and a close that ran first would leave the session held for good.
 */
private val sessionExecutor = Executors.newSingleThreadExecutor { Thread(it, "rpcn-friends") }
private val sessionDispatcher = sessionExecutor.asCoroutineDispatcher()

/**
 * The RPCN friend list with its requests, shared by the RPCN settings and the Friends tabs.
 *
 * Read again every two seconds while it is on screen. Reading is local (the signed-in session
 * already holds the list) and requests, presence and sign-ins change while the screen is open:
 * read once on open, a new request showed only after leaving and coming back.
 *
 * Accepting a request is adding them back and declining it is removing them, as on desktop: on
 * RPCN a friendship is two adds, and RemoveFriend also refuses or withdraws a pending one. A
 * game's invite list offers friends who are online, so until a request is accepted there is no
 * one to invite.
 *
 * A game playing online has a session to read. Outside one there is none, so with an account saved
 * the list connects on its own and holds that session for as long as it is showing: a saved
 * account reads as signed in, and a separate "Sign in" step here read as being signed out.
 *
 * @param canRemove whether friends can be removed here. Not in the in-game menu, where a Remove
 *   next to every name only gets in the way of what that panel is for.
 * @param showInvites whether game invites are listed, for the in-game menu: an invite can only be
 *   taken by the game it is for, while it runs.
 */
@Composable
internal fun RpcnFriendsList(
    controllerIdPrefix: String,
    canAdd: Boolean,
    canRemove: Boolean,
    showInvites: Boolean,
) {
    val scope = rememberCoroutineScope()

    var data by remember { mutableStateOf(RpcnFriends()) }
    var invites by remember { mutableStateOf(emptyList<RpcnInvite>()) }
    // Nothing is drawn for the signed-out case until the first read, so a game's signed-in list
    // does not open with a flash of "Connecting".
    var loaded by remember { mutableStateOf(false) }
    var newFriend by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // The friend whose Remove is waiting for a second press, or null.
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    // A control to hand the pad's selection to once it has registered, or null.
    var restoreTo by remember { mutableStateOf<String?>(null) }
    // Sessions this list opened; each one is closed when it leaves the screen.
    var opened by remember { mutableIntStateOf(0) }
    // Whether an account is saved (null until known), and whether this list has tried to connect it.
    var configured by remember { mutableStateOf<Boolean?>(null) }
    var connectTried by remember { mutableStateOf(false) }

    // str() is @Composable, so it cannot be called from inside a coroutine or an onClick lambda.
    val msgWorking = str("rpcn.friends.working")
    val msgAdded = str("rpcn.friends.added")
    val msgAccepted = str("rpcn.friends.accepted")
    val msgDeclined = str("rpcn.friends.declined")
    val msgCancelled = str("rpcn.friends.cancelled")
    val msgRemoved = str("rpcn.friends.removed")
    val msgNeedName = str("rpcn.friends.need.name")
    val msgJoining = str("rpcn.invites.joining")
    val msgInviteDeclined = str("rpcn.invites.declined")

    suspend fun read() {
        data = parseRpcnFriends(withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnGetFriends() })
        if (showInvites) invites = parseRpcnInvites(withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnGetInvites() })
        loaded = true
    }

    LaunchedEffect(Unit) {
        while (true) {
            read()
            delay(2_000)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            repeat(opened) { sessionExecutor.execute { Rpcs3Bridge.rpcnFriendsSession(false) } }
        }
    }

    // "" from the core is success; anything else is its own message, which says more than a
    // generic failure would.
    fun act(done: String, action: () -> String, onSuccess: () -> Unit = {}) {
        busy = true
        status = msgWorking
        scope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            status = result.ifBlank { done }
            if (result.isBlank()) onSuccess()
            busy = false
            read()
        }
    }

    fun connect() {
        busy = true
        status = ""
        connectTried = true
        opened++
        scope.launch {
            val result = withContext(sessionDispatcher) { Rpcs3Bridge.rpcnFriendsSession(true) }
            status = result
            busy = false
            read()
        }
    }

    LaunchedEffect(Unit) {
        configured = withContext(Dispatchers.IO) {
            runCatching { JSONObject(Rpcs3Bridge.rpcnStatus()).optBoolean("configured") }.getOrDefault(false)
        }
    }

    // Once, after the first read shows no session: a game that is online needs nothing from here.
    LaunchedEffect(loaded, configured) {
        if (loaded && configured == true && !data.signedIn && !connectTried) connect()
    }

    // Swapping a row's buttons drops the selected one from the pad's registry, which sends the
    // selection (and the page) to the top. So the buttons let go of it first, and with the pad
    // driving it lands here instead: Keep when the question appears, back on Remove after Keep.
    // Under touch nothing is selected at all, as nothing would show it.
    LaunchedEffect(confirmRemove) {
        val name = confirmRemove ?: return@LaunchedEffect
        if (PadInput.active.value) restoreTo = "$controllerIdPrefix.keep.$name"
    }
    LaunchedEffect(restoreTo) {
        val target = restoreTo ?: return@LaunchedEffect
        var attempts = 0
        while (!SettingsControllerNav.selectById(target) && attempts < 10) {
            attempts++
            withFrameNanos {}
        }
        restoreTo = null
    }

    Column(Modifier.fillMaxWidth()) {
        if (canAdd) {
            val add: () -> Unit = add@{
                val name = newFriend.trim()
                if (name.isEmpty()) {
                    status = msgNeedName
                    return@add
                }
                // Adding someone who already asked is accepting them, and the server says so.
                val done = if (name in data.received) msgAccepted.format(name) else msgAdded.format(name)
                act(done, { Rpcs3Bridge.rpcnAddFriend(name) }) { newFriend = "" }
            }
            val nameLabel = str("rpcn.friends.username")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A pad cannot type into the field itself: A opens the controller keyboard for it
                OutlinedTextField(
                    value = newFriend,
                    onValueChange = { newFriend = it },
                    label = { Text(nameLabel) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).controllerFocusable(
                        "$controllerIdPrefix.name",
                        onConfirm = { if (!busy) LibraryKeyboard.open(newFriend, { newFriend = it }, nameLabel) },
                    ),
                )
                TextButton(
                    enabled = !busy,
                    onClick = add,
                    modifier = Modifier.controllerFocusable("$controllerIdPrefix.add", onConfirm = add),
                ) { Text(str("rpcn.friends.add")) }
            }
        }

        if (!loaded) {
            // First read still on its way.
        } else if (!data.signedIn) {
            when {
                configured == false -> Note(str("rpcn.friends.noAccount"))
                busy || configured == null || !connectTried -> Note(str("rpcn.friends.connecting"))
                else -> {
                    // The reason, if the core gave one, is in the status line below.
                    Note(str("rpcn.friends.signedOut"))
                    TextButton(
                        enabled = !busy,
                        onClick = ::connect,
                        modifier = Modifier.controllerFocusable("$controllerIdPrefix.retry", onConfirm = ::connect),
                    ) { Text(str("rpcn.friends.retry")) }
                }
            }
        } else {
            if (showInvites) {
                SectionLabel(str("rpcn.invites.title"))
                if (invites.isEmpty()) Note(str("rpcn.invites.none"))
                invites.forEach { invite ->
                    val join = { act(msgJoining.format(invite.from), { Rpcs3Bridge.rpcnAcceptInvite(invite.id) }) }
                    val decline = { act(msgInviteDeclined.format(invite.from), { Rpcs3Bridge.rpcnDeclineInvite(invite.id) }) }
                    val detail = listOf(invite.subject, invite.body).filter { it.isNotBlank() }.joinToString(" ")
                    EntryRow(str("rpcn.invites.from").format(invite.from), detail) {
                        TextButton(
                            enabled = !busy,
                            onClick = join,
                            modifier = Modifier.controllerFocusable("$controllerIdPrefix.join.${invite.id}", onConfirm = join),
                        ) { Text(str("rpcn.invites.join")) }
                        TextButton(
                            enabled = !busy,
                            onClick = decline,
                            modifier = Modifier.controllerFocusable("$controllerIdPrefix.invdecline.${invite.id}", onConfirm = decline),
                        ) { Text(str("rpcn.friends.decline")) }
                    }
                }
            }

            // Always there once signed in, so "no requests" reads as an answer rather than as a
            // section that is missing.
            SectionLabel(str("rpcn.friends.requests"))
            if (data.received.isEmpty() && data.sent.isEmpty()) Note(str("rpcn.friends.noRequests"))
            data.received.forEach { name ->
                val accept = { act(msgAccepted.format(name), { Rpcs3Bridge.rpcnAddFriend(name) }) }
                val decline = { act(msgDeclined.format(name), { Rpcs3Bridge.rpcnRemoveFriend(name) }) }
                EntryRow(name, str("rpcn.friends.wantsToAdd")) {
                    TextButton(
                        enabled = !busy,
                        onClick = accept,
                        modifier = Modifier.controllerFocusable("$controllerIdPrefix.accept.$name", onConfirm = accept),
                    ) { Text(str("rpcn.friends.accept")) }
                    TextButton(
                        enabled = !busy,
                        onClick = decline,
                        modifier = Modifier.controllerFocusable("$controllerIdPrefix.decline.$name", onConfirm = decline),
                    ) { Text(str("rpcn.friends.decline")) }
                }
            }
            data.sent.forEach { name ->
                val cancel = { act(msgCancelled.format(name), { Rpcs3Bridge.rpcnRemoveFriend(name) }) }
                EntryRow(name, str("rpcn.friends.waiting")) {
                    TextButton(
                        enabled = !busy,
                        onClick = cancel,
                        modifier = Modifier.controllerFocusable("$controllerIdPrefix.cancel.$name", onConfirm = cancel),
                    ) { Text(str("rpcn.friends.cancel")) }
                }
            }

            SectionLabel(str("rpcn.friends.title"))
            if (data.friends.isEmpty()) {
                Note(str(if (canAdd) "rpcn.friends.empty" else "rpcn.friends.empty.elsewhere"))
            }
            data.friends.forEach { friend ->
                val name = friend.name
                if (confirmRemove == name) {
                    val remove = {
                        SettingsControllerNav.clearSelection()
                        confirmRemove = null
                        if (PadInput.active.value && canAdd) restoreTo = "$controllerIdPrefix.add"
                        act(msgRemoved.format(name), { Rpcs3Bridge.rpcnRemoveFriend(name) })
                    }
                    val keep = {
                        SettingsControllerNav.clearSelection()
                        confirmRemove = null
                        if (PadInput.active.value) restoreTo = "$controllerIdPrefix.remove.$name"
                    }
                    EntryRow(str("rpcn.friends.removeConfirm").format(name), null) {
                        TextButton(
                            enabled = !busy,
                            onClick = remove,
                            modifier = Modifier.controllerFocusable("$controllerIdPrefix.confirmremove.$name", onConfirm = remove),
                        ) { Text(str("rpcn.friends.remove"), color = MaterialTheme.colorScheme.error) }
                        TextButton(
                            onClick = keep,
                            modifier = Modifier.controllerFocusable("$controllerIdPrefix.keep.$name", onConfirm = keep),
                        ) { Text(str("rpcn.friends.keep")) }
                    }
                } else {
                    FriendRow(friend) {
                        if (canRemove) {
                            val ask = { confirmRemove = name }
                            TextButton(
                                enabled = !busy,
                                onClick = ask,
                                modifier = Modifier.controllerFocusable("$controllerIdPrefix.remove.$name", onConfirm = ask),
                            ) { Text(str("rpcn.friends.remove")) }
                        }
                    }
                }
            }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** A name with one line under it, and its buttons. */
@Composable
private fun EntryRow(name: String, detail: String?, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/**
 * A friend: whether they are online, and while they are, what their game says they are doing.
 *
 * Presence is the game's to set (title, a status line, a comment), so a game that sets none shows
 * just "Online". RPCN sends no pictures of other users: there is no avatar to show here.
 */
@Composable
private fun FriendRow(friend: RpcnFriend, actions: @Composable () -> Unit) {
    val stateText = if (friend.online) str("rpcn.friends.online") else str("rpcn.friends.offline")
    val playing = if (friend.online && friend.title.isNotBlank()) str("rpcn.friends.playing").format(friend.title) else ""
    val presence = if (friend.online) listOf(friend.status, friend.comment).filter { it.isNotBlank() }.joinToString(" ") else ""
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (friend.online) "● " else "○ ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (friend.online) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    friend.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    "  $stateText",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (friend.online) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (playing.isNotBlank()) {
                Text(
                    playing,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (presence.isNotBlank()) {
                Text(
                    presence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}
