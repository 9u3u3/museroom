package com.museroom.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.Friend
import com.museroom.app.net.FriendsRepository
import com.museroom.app.net.PendingRequest
import com.museroom.app.net.Profile
import com.museroom.app.util.formatClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Friends, and what they are playing.
 *
 * Progress bars move between refreshes rather than on them: the server sends a
 * position and the time it was taken, and this extrapolates locally, so one
 * message every fifteen seconds still looks live.
 */
@Composable
fun FriendsCard() {
    val context = LocalContext.current
    val auth = remember { AuthRepository.get(context) }
    val repo = remember { FriendsRepository.get(context) }
    val scope = rememberCoroutineScope()

    val session by auth.session.collectAsStateWithLifecycle()
    if (session == null) return

    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var pending by remember { mutableStateOf<List<PendingRequest>>(emptyList()) }
    var results by remember { mutableStateOf<List<Profile>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        repo.friends().onSuccess { friends = it }.onFailure { message = it.message }
        repo.pending().onSuccess { pending = it }
    }

    LaunchedEffect(session?.userId) {
        while (true) {
            reload()
            delay(15_000)
        }
    }

    Panel {
        Text(
            text = "Friends",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (pending.isNotEmpty()) {
            Spacer(Modifier.size(14.dp))
            Small("Requests")
            pending.forEach { request ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "@${request.profile.handle}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (request.incoming) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    repo.accept(request.profile)
                                        .onFailure { message = it.message }
                                    reload()
                                    busy = false
                                }
                            },
                        ) { Text("Accept") }
                    } else {
                        Small("sent")
                    }
                }
            }
        }

        Spacer(Modifier.size(14.dp))

        if (friends.isEmpty()) {
            Small("Nobody yet. Find someone by their handle below.")
        } else {
            friends.forEach { FriendRow(it) }
        }

        Spacer(Modifier.size(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.length < 2) results = emptyList()
            },
            label = { Text("Find by handle") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(8.dp))
        Button(
            enabled = query.trim().length >= 2 && !busy,
            onClick = {
                busy = true
                message = null
                scope.launch {
                    repo.search(query)
                        .onSuccess { results = it }
                        .onFailure { message = it.message }
                    busy = false
                }
            },
        ) { Text("Search") }

        results.forEach { profile ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "@${profile.handle}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            repo.request(profile)
                                .onSuccess { message = "Request sent to @${profile.handle}" }
                                .onFailure { message = it.message }
                            reload()
                            busy = false
                        }
                    },
                ) { Text("Add") }
            }
        }

        message?.let {
            Spacer(Modifier.size(10.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FriendRow(friend: Friend) {
    val playing = friend.nowPlaying
    ListenerRow(
        handle = friend.profile.handle,
        title = playing?.title.orEmpty(),
        artist = playing?.artist.orEmpty(),
        durationMs = playing?.durationMs ?: 0,
        positionMs = playing?.positionMs ?: 0,
        isPlaying = playing?.isPlaying == true,
        updatedAt = playing?.updatedAt.orEmpty(),
    )
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun Small(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
