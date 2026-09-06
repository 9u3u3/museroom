package com.museroom.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.media.pickActive
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.Updates
import com.museroom.app.player.Playback
import com.museroom.app.privacy.PrivacyState
import com.museroom.app.sync.FollowSession
import com.museroom.app.sync.TogetherHost
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.Kicker
import com.museroom.app.ui.kit.LiveDot
import com.museroom.app.ui.kit.NeoAccentCard
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.NeoRound
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.ui.kit.Shelf
import com.museroom.app.ui.kit.hardShadow
import kotlinx.coroutines.launch

/**
 * Home, as the design has it.
 *
 * The order is an argument. What another app is playing comes first, because it
 * is the one thing on the screen that is happening whether or not Museroom is
 * open, and the only useful answer to it is the button beside it. Rooms come
 * next, because a room is happening now and a shelf is not. Then the two
 * shelves, which are an invitation, and an invitation goes last.
 *
 * There is deliberately no player on this screen any more. A full-bleed cover
 * that folded as the page scrolled was the first thing anybody saw and the
 * last thing anybody used: what is playing lives in the bar above the rail,
 * one tap from the player itself.
 */
@Composable
fun NowScreen(
    onOpenPlayer: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenRoom: () -> Unit = {},
    onOpenRooms: () -> Unit = {},
) {
    val context = LocalContext.current
    val c = Neo.colors
    val scroll = rememberScrollState()

    val sessions by NowPlayingRepository.sessions.collectAsStateWithLifecycle()
    val privacy = remember { PrivacyState.get(context) }
    val isPrivate by privacy.privateSession.collectAsStateWithLifecycle()
    val session by remember { AuthRepository.get(context).session }
        .collectAsStateWithLifecycle()
    val following by FollowSession.following.collectAsStateWithLifecycle()
    val hosting by TogetherHost.on.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TabBar(
            left = { Wordmark() },
            right = {
                NeoRound(NeoIcons.Search, "Search", onOpenSearch)
                NeoRound(NeoIcons.History, "Your listening", onOpenHistory)
            },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // A room you are already in is the one thing more urgent than what
            // another app is playing, so it goes above everything.
            if (following != null || hosting) {
                InRoomStrip(
                    handle = following?.handle,
                    hosting = following == null,
                    onOpen = onOpenRoom,
                )
                Spacer(Modifier.height(14.dp))
            }

            UpdateCard()

            if (isPrivate) {
                NeoAccentCard(fill = c.pink, radius = 16.dp) {
                    Text(
                        "Private session — nothing is being recorded",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            // Reading another app is a different act from playing, so it is a
            // different colour: sky, never the lime that means Museroom is the
            // one making the sound.
            val heard = sessions.pickActive()?.takeIf { !it.isRoom }
            if (heard != null) {
                TakeOver(
                    source = heard.sourceLabel,
                    title = heard.title,
                    artist = heard.artist,
                )
                Spacer(Modifier.height(4.dp))
            }

            LiveRooms(onOpenRooms)
            HomeShelves(onOpenPlayer = onOpenPlayer)

            if (session == null) {
                Spacer(Modifier.height(20.dp))
                SignInPanel("Your listening stays on this phone until you sign in.")
            }
        }
    }
}

/**
 * The strip that says you are in a room, and takes you back to it.
 *
 * A line rather than a card with a player in it. The room has its own screen
 * now, and repeating its transport here would be two sets of controls steering
 * the same music from two places.
 */
@Composable
private fun InRoomStrip(handle: String?, hosting: Boolean, onOpen: () -> Unit) {
    val c = Neo.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .hardShadow(4.dp, c.onAccent, shape)
            .clip(shape)
            .background(c.lime)
            .border(3.dp, c.onAccent, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        LiveDot()
        Column(Modifier.weight(1f)) {
            Kicker(if (hosting) "Hosting" else "Listening with", color = c.onAccent)
            Text(
                if (hosting) "Your room" else "@${handle.orEmpty()}",
                style = MaterialTheme.typography.titleMedium,
                color = c.onAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NeoButton("Open", small = true, tone = NeoTone.Paper, onClick = onOpen)
    }
}

/**
 * Another app is playing, and Museroom is only reading it.
 *
 * The button offers the one thing reading cannot do: bring the song into
 * Museroom's own player, where there is a queue, a room and a scrubber. It
 * searches for the track rather than assuming an id, because the only thing a
 * media session reliably publishes is its name.
 */
@Composable
private fun TakeOver(source: String, title: String, artist: String) {
    val c = Neo.colors
    val scope = rememberCoroutineScope()
    var busy by remember(title) { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .hardShadow(4.dp, c.onAccent, shape)
            .clip(shape)
            .background(c.sky)
            .border(3.dp, c.onAccent, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Kicker("$source is playing", color = c.onAccent)
            Text(
                title.ifBlank { "Something" },
                style = MaterialTheme.typography.titleMedium,
                fontSize = 15.sp,
                color = c.onAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NeoButton(
            if (busy) "Finding" else "Take over",
            small = true,
            tone = NeoTone.Lime,
            enabled = !busy && title.isNotBlank(),
            onClick = {
                busy = true
                scope.launch {
                    val found = Playback.search(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "))
                    if (found.isNotEmpty()) Playback.play(found, 0, from = source)
                    busy = false
                }
            },
        )
    }
}

/**
 * Friends who have something on right now.
 *
 * A room is not a thing that exists on its own — it is a person with music on
 * and the door open — so this is a list of people rather than a list of rooms.
 * The last tile is the way to start one, which is the only place on the home
 * screen that offer belongs.
 */
@Composable
private fun LiveRooms(onOpenRooms: () -> Unit) {
    val c = Neo.colors
    val context = LocalContext.current
    val session by remember { AuthRepository.get(context).session }
        .collectAsStateWithLifecycle()
    val repo = remember { com.museroom.app.net.FriendsRepository.get(context) }
    var live by remember { mutableStateOf<List<com.museroom.app.net.Friend>>(emptyList()) }

    com.museroom.app.ui.Refreshing(session?.userId, everyMs = 20_000) {
        if (session == null) {
            live = emptyList()
            return@Refreshing
        }
        repo.friends().onSuccess { all -> live = all.filter { it.nowPlaying != null } }
    }

    if (live.isEmpty() && session == null) return

    Shelf("Rooms live now", action = "All", onAction = onOpenRooms)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(live, key = { it.profile.id }) { friend ->
            val playing = friend.nowPlaying
            Column(
                Modifier
                    .width(82.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { Person.show(friend.profile.id, friend.profile.handle) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    TrackCover(
                        friend.profile.id,
                        null,
                        Modifier.size(82.dp),
                        radius = 16.dp,
                        shadow = 4.dp,
                        stroke = 3.dp,
                        dot = 10.dp,
                    )
                    LiveDot(Modifier.align(Alignment.TopEnd).padding(6.dp))
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "@" + friend.profile.handle,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            // The plus tile: a card with no artwork, because there is no room
            // yet. It is drawn as an empty frame rather than a button so it
            // sits in the strip as one more of the same kind of thing.
            Column(
                Modifier
                    .width(82.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenRooms,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val shape = RoundedCornerShape(16.dp)
                Box(
                    Modifier
                        .size(82.dp)
                        .hardShadow(4.dp, c.ink, shape)
                        .clip(shape)
                        .background(c.card)
                        .border(3.dp, c.ink, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", style = bangers(38).copy(color = c.ink))
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "Host",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 11.sp,
                )
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

/**
 * A newer build exists.
 *
 * Not being on the Play Store means nothing updates itself and nobody is told,
 * so somebody who installed once would sit on that build for ever. This is the
 * telling. It opens the page and they decide, the same way they did the first
 * time — nothing downloads itself, and saying no to a version means no.
 */
@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val c = Neo.colors
    val release by Updates.available.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { Updates.check(context) }
    val update = release ?: return

    NeoAccentCard(
        fill = c.pink,
        radius = 18.dp,
        shadow = 5.dp,
        padding = 16.dp,
        modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
    ) {
        Kicker("Update", color = c.onAccent)
        Spacer(Modifier.size(4.dp))
        Text(
            "Museroom ${update.versionName} is out",
            style = MaterialTheme.typography.titleMedium,
            color = c.onAccent,
        )
        if (update.notes.isNotBlank()) {
            Spacer(Modifier.size(3.dp))
            Text(
                update.notes,
                style = MaterialTheme.typography.bodySmall,
                color = c.onAccent.copy(alpha = 0.85f),
            )
        }
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            NeoButton("Get it", small = true, tone = NeoTone.Lime, onClick = {
                Updates.open(context, update)
            })
            NeoButton("Not now", small = true, tone = NeoTone.Paper, onClick = {
                Updates.skip(context, update)
            })
        }
    }
}
