package com.museroom.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.Friend
import com.museroom.app.net.FriendsRepository
import com.museroom.app.player.LocalPlayer
import com.museroom.app.player.Playback
import com.museroom.app.player.Library
import com.museroom.app.ui.Neo
import com.museroom.app.ui.Refreshing

/**
 * Quick picks, held still.
 *
 * They were seeded from whatever was played last, which meant the shelf changed
 * under somebody every time they pressed a song — including when they pressed a
 * song *on the shelf*, so it rearranged itself as it was being used. A shelf
 * that will not sit still is not a shelf.
 *
 * They are decided once and then left alone, and change when the app is opened
 * again or when somebody asks for a new set. This lives outside the composable
 * so that moving between tabs does not count as asking.
 */
private object Picks {
    var seed: String? = null
    var tracks: List<LocalPlayer.Track> = emptyList()
    var looking = false

    /** Forget them, so the next look decides again. */
    fun again() {
        seed = null
        tracks = emptyList()
    }
}

/**
 * The two shelves that make the app worth opening without typing.
 *
 * Both are built from what was played here rather than from an account, because
 * Museroom has no YouTube account and asking for one to see a home screen would
 * be a strange first thing to demand. Nothing appears until something has been
 * played, which is honest: on a fresh install there genuinely is nothing to
 * suggest, and an empty shelf with a title above it is worse than no shelf.
 */
@Composable
fun HomeSections(onOpenPlayer: () -> Unit, onOpenRooms: () -> Unit = {}) {
    val recent by Library.recent.collectAsStateWithLifecycle()
    var picks by remember { mutableStateOf(Picks.tracks) }
    var refreshing by remember { mutableStateOf(false) }

    // Decided once. The key is deliberately not the last track played: that is
    // exactly the thing that must not move the shelf.
    LaunchedEffect(refreshing, recent.isEmpty()) {
        if (Picks.tracks.isNotEmpty() && !refreshing) {
            picks = Picks.tracks
            return@LaunchedEffect
        }
        val chosen = Picks.seed ?: recent.firstOrNull()?.id ?: return@LaunchedEffect
        if (Picks.looking) return@LaunchedEffect
        Picks.looking = true
        val found = Playback.radio(chosen)
        Picks.looking = false
        if (found.isNotEmpty()) {
            Picks.seed = chosen
            Picks.tracks = found
            picks = found
        }
        refreshing = false
    }

    LiveRooms(onOpenRooms)

    if (recent.isEmpty()) return

    if (picks.isNotEmpty()) {
        Shelf(
            title = "Quick picks",
            action = "New set",
            onAction = {
                Picks.again()
                refreshing = true
            },
        )
        picks.take(4).forEachIndexed { i, track ->
            TrackRow(
                track = track,
                playing = false,
                appearAfter = i,
                onClick = { Playback.play(picks, i, from = "Quick picks") },
            )
        }
    }

    Shelf(title = "Listen again")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(recent, key = { it.id }) { track ->
            Column(
                Modifier
                    .width(118.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { Playback.play(recent, recent.indexOf(track), from = "Listen again") },
            ) {
                TrackCover(
                    track.id,
                    track.artworkUrl,
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    radius = 14.dp,
                    shadow = 4.dp,
                    stroke = 3.dp,
                    dot = 11.dp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = Neo.colors.ink.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Friends who are playing something right now.
 *
 * A room is not a thing that exists on its own — it is a person with music on
 * and the door open — so this is a list of people rather than a list of rooms.
 * Tapping one opens their page, which is where joining has always lived.
 *
 * Nothing is drawn when nobody is listening, including when nobody is signed
 * in. A heading over an empty strip is a promise the screen cannot keep.
 */
@Composable
private fun LiveRooms(onOpenRooms: () -> Unit) {
    val c = Neo.colors
    val context = LocalContext.current
    val session by remember { AuthRepository.get(context).session }
        .collectAsStateWithLifecycle()
    val repo = remember { FriendsRepository.get(context) }
    var live by remember { mutableStateOf<List<Friend>>(emptyList()) }

    Refreshing(session?.userId, everyMs = 20_000) {
        if (session == null) {
            live = emptyList()
            return@Refreshing
        }
        repo.friends().onSuccess { all ->
            live = all.filter { it.nowPlaying != null }
        }
    }

    if (live.isEmpty()) return

    Shelf(title = "Rooms live now", action = "All", onAction = onOpenRooms)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(live, key = { it.profile.id }) { friend ->
            val playing = friend.nowPlaying
            Column(
                Modifier
                    .width(126.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { Person.show(friend.profile.id, friend.profile.handle) },
            ) {
                Text(
                    "@" + friend.profile.handle,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    playing?.title.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = c.ink.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item {
            Column(
                Modifier
                    .width(126.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenRooms,
                    ),
            ) {
                Text(
                    "Host",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 12.sp,
                    color = c.violet,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Start one",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = c.ink.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** A section heading with an optional thing to do to the whole section. */
@Composable
private fun Shelf(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = Neo.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = c.ink)
        if (action != null && onAction != null) {
            Text(
                action.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.W900,
                letterSpacing = 1.4.sp,
                fontSize = 10.sp,
                color = c.ink.copy(alpha = 0.55f),
                modifier = Modifier
                    .padding(bottom = 3.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAction,
                    ),
            )
        }
    }
}
