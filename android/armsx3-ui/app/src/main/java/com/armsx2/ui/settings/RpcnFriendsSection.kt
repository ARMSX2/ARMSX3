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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.armsx2.i18n.str
import com.armsx3.Rpcs3Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * RPCN friends: add, remove, and see who is online.
 *
 * The core has had add_friend/remove_friend/get_friend_presence_by_index all along; nothing
 * reached them from Android, so there was no way to add a friend at all short of doing it from a
 * game that offers its own friend UI.
 *
 * Listing deliberately does NOT sign in -- it shows what an already-authenticated session knows
 * and an empty list otherwise, because a settings screen that opens a network session merely by
 * being looked at is not what anyone expects. Adding and removing are explicit actions and do
 * authenticate, which is what makes them work outside a running game.
 */
@Composable
fun RpcnFriendsSection() {
    val scope = rememberCoroutineScope()

    var friends by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var newFriend by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // str() is @Composable, so it cannot be called from inside a coroutine or an onClick lambda.
    val msgWorking = str("rpcn.friends.working")
    val msgAdded = str("rpcn.friends.added")
    val msgRemoved = str("rpcn.friends.removed")
    val msgNeedName = str("rpcn.friends.need.name")

    fun refresh() {
        scope.launch {
            val json = withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnGetFriends() }
            friends = runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    o.optString("npid") to o.optBoolean("online")
                }.sortedWith(compareByDescending<Pair<String, Boolean>> { it.second }.thenBy { it.first.lowercase() })
            }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxWidth()) {
        Text(
            str("rpcn.friends.title"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )

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
                onClick = {
                    val name = newFriend.trim()
                    if (name.isEmpty()) {
                        status = msgNeedName
                        return@TextButton
                    }
                    busy = true
                    status = msgWorking
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnAddFriend(name) }
                        // "" is success; anything else is the core's own message, which says more
                        // than a generic failure would.
                        status = result.ifBlank { msgAdded.format(name) }
                        if (result.isBlank()) newFriend = ""
                        busy = false
                        refresh()
                    }
                },
            ) { Text(str("rpcn.friends.add")) }
        }

        if (friends.isEmpty()) {
            Text(
                str("rpcn.friends.empty"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            friends.forEach { (name, online) ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (online) "● $name" else "○ $name",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (online) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            status = msgWorking
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { Rpcs3Bridge.rpcnRemoveFriend(name) }
                                status = result.ifBlank { msgRemoved.format(name) }
                                busy = false
                                refresh()
                            }
                        },
                    ) { Text(str("rpcn.friends.remove")) }
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
