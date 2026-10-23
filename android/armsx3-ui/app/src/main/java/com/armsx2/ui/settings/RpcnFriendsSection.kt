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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.armsx2.i18n.str
import com.armsx3.Rpcs3Bridge
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * RPCN friends: add, remove, see who is online, and answer friend requests.
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
        RpcnFriendsList(controllerIdPrefix = "rpcn.friends", canAdd = true, canRemove = true)
    }
}

/** One read of the RPCN friend list: friends, and the requests waiting on either side. */
internal data class RpcnFriends(
    val signedIn: Boolean = false,
    /** Name and whether they are online; online first. */
    val friends: List<Pair<String, Boolean>> = emptyList(),
    /** Requests from others, waiting on this account. */
    val received: List<String> = emptyList(),
    /** Requests this account sent, waiting on them. */
    val sent: List<String> = emptyList(),
)

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
            e.optString("npid") to e.optBoolean("online")
        }.sortedWith(compareByDescending<Pair<String, Boolean>> { it.second }.thenBy { it.first.lowercase() }),
        received = names("received"),
        sent = names("sent"),
    )
}.getOrDefault(RpcnFriends())

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
 * The RPCN friend list with its requests, shared by the RPCN settings and the Friends tab.
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
 * A game in progress has a session to read. Outside one there is nothing until someone asks: the
 * sign-in button holds a session open for as long as this list is showing, and nothing here opens
 * one on its own.
 */
@Composable
internal fun RpcnFriendsList(controllerIdPrefix: String, canAdd: Boolean, canRemove: Boolean) {
    val scope = rememberCoroutineScope()

    var data by remember { mutableStateOf(RpcnFriends()) }
    // Nothing is drawn for the signed-out case until the first read, so a game's signed-in list
    // does not open with a flash of the sign-in button.
    var loaded by remember { mutableStateOf(false) }
    var newFriend by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // Sessions this list opened; each one is closed when it leaves the screen.
    var opened by remember { mutableIntStateOf(0) }

    // str() is @Composable, so it cannot be called from inside a coroutine or an onClick lambda.
    val msgWorking = str("rpcn.friends.working")
    val msgAdded = str("rpcn.friends.added")
    val msgAccepted = str("rpcn.friends.accepted")
    val msgDeclined = str("rpcn.friends.declined")
    val msgCancelled = str("rpcn.friends.cancelled")
    val msgRemoved = str("rpcn.friends.removed")
    val msgNeedName = str("rpcn.friends.need.name")

    suspend fun read() {
        data = parseRpcnFriends(withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnGetFriends() })
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

    fun signIn() {
        busy = true
        status = msgWorking
        opened++
        scope.launch {
            val result = withContext(sessionDispatcher) { Rpcs3Bridge.rpcnFriendsSession(true) }
            status = result
            busy = false
            read()
        }
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newFriend,
                    onValueChange = { newFriend = it },
                    label = { Text(str("rpcn.friends.username")) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
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
            Text(
                str("rpcn.friends.signedOut"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(
                enabled = !busy,
                onClick = ::signIn,
                modifier = Modifier.controllerFocusable("$controllerIdPrefix.signin", onConfirm = ::signIn),
            ) { Text(str("rpcn.friends.signin")) }
        } else {
            if (data.received.isNotEmpty()) {
                SectionLabel(str("rpcn.friends.requests"))
                data.received.forEach { name ->
                    val accept = { act(msgAccepted.format(name), { Rpcs3Bridge.rpcnAddFriend(name) }) }
                    val decline = { act(msgDeclined.format(name), { Rpcs3Bridge.rpcnRemoveFriend(name) }) }
                    NameRow(name) {
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
            }

            if (data.sent.isNotEmpty()) {
                SectionLabel(str("rpcn.friends.sent"))
                data.sent.forEach { name ->
                    val cancel = { act(msgCancelled.format(name), { Rpcs3Bridge.rpcnRemoveFriend(name) }) }
                    NameRow(name) {
                        TextButton(
                            enabled = !busy,
                            onClick = cancel,
                            modifier = Modifier.controllerFocusable("$controllerIdPrefix.cancel.$name", onConfirm = cancel),
                        ) { Text(str("rpcn.friends.cancel")) }
                    }
                }
            }

            if (data.friends.isEmpty()) {
                if (data.received.isEmpty() && data.sent.isEmpty()) {
                    Text(
                        str(if (canAdd) "rpcn.friends.empty" else "rpcn.friends.empty.elsewhere"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else {
                if (data.received.isNotEmpty() || data.sent.isNotEmpty()) SectionLabel(str("rpcn.friends.title"))
                data.friends.forEach { (name, online) ->
                    val remove = { act(msgRemoved.format(name), { Rpcs3Bridge.rpcnRemoveFriend(name) }) }
                    NameRow(if (online) "● $name" else "○ $name", online) {
                        if (canRemove) {
                            TextButton(
                                enabled = !busy,
                                onClick = remove,
                                modifier = Modifier.controllerFocusable("$controllerIdPrefix.remove.$name", onConfirm = remove),
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
                modifier = Modifier.padding(top = 4.dp),
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
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun NameRow(name: String, highlight: Boolean = true, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}
