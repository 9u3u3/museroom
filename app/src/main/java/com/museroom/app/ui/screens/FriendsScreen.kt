package com.museroom.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.Friend
import com.museroom.app.net.FriendsRepository
import com.museroom.app.net.PendingRequest
import com.museroom.app.net.Profile
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.NeoAccentCard
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FriendsScreen() {
    val context = LocalContext.current
    val c = Neo.colors
    val auth = remember { AuthRepository.get(context) }
    val repo = remember { FriendsRepository.get(context) }
    val scope = rememberCoroutineScope()
    val session by auth.session.collectAsStateWithLifecycle()

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
        while (session != null) {
            reload()
            delay(15_000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Friends", drop = c.sky)

        if (session == null) {
            SignInPanel("Sign in for a handle other people can find.")
            return@Column
        }

        pending.forEach { request ->
            NeoAccentCard(fill = c.sky, radius = 16.dp) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Label(if (request.incoming) "Wants to be friends" else "Request sent", color = c.onAccent)
                        Text(request.profile.handle, style = MaterialTheme.typography.titleMedium)
                    }
                    if (request.incoming) {
                        NeoButton("Accept", small = true, tone = NeoTone.Lime, enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                repo.accept(request.profile).onFailure { message = it.message }
                                reload(); busy = false
                            }
                        })
                    }
                }
            }
        }

        Field(query, { query = it; if (it.length < 2) results = emptyList() }, "Find by handle")
        NeoButton(
            "Search",
            small = true,
            enabled = query.trim().length >= 2 && !busy,
            onClick = {
                busy = true; message = null
                scope.launch {
                    repo.search(query).onSuccess { results = it }.onFailure { message = it.message }
                    busy = false
                }
            },
        )

        results.forEach { profile ->
            NeoCard(radius = 14.dp, shadow = 3.dp, padding = 12.dp) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        profile.handle,
                        style = MaterialTheme.typography.titleMedium,
                        color = c.ink,
                        modifier = Modifier.weight(1f),
                    )
                    NeoButton("Add", small = true, enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            repo.request(profile)
                                .onSuccess { message = "Request sent to ${profile.handle}" }
                                .onFailure { message = it.message }
                            reload(); busy = false
                        }
                    })
                }
            }
        }

        if (friends.isEmpty()) {
            NeoCard { Note("Nobody yet. Search for someone by their username above.") }
        } else {
            friends.forEach { friend ->
                ListenerRow(
                    handle = friend.profile.handle,
                    title = friend.nowPlaying?.title.orEmpty(),
                    artist = friend.nowPlaying?.artist.orEmpty(),
                    durationMs = friend.nowPlaying?.durationMs ?: 0,
                    positionMs = friend.nowPlaying?.positionMs ?: 0,
                    isPlaying = friend.nowPlaying?.isPlaying == true,
                    updatedAt = friend.nowPlaying?.updatedAt.orEmpty(),
                    sourceTrackId = friend.nowPlaying?.sourceTrackId,
                    hostId = friend.profile.id,
                    fingerprint = friend.nowPlaying?.title.orEmpty(),
                    avatarUrl = friend.profile.avatarUrl,
                    openToAll = friend.profile.openToAll,
                )
            }
        }

        message?.let { Note(it) }
    }
}
