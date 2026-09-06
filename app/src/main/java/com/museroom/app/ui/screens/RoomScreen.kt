package com.museroom.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.FriendsRepository
import com.museroom.app.net.RoomMember
import com.museroom.app.sync.FollowSession
import com.museroom.app.sync.FollowState
import com.museroom.app.sync.RoomPlayer
import com.museroom.app.sync.RoomPresence
import com.museroom.app.sync.TogetherHost
import com.museroom.app.ui.Neo
import com.museroom.app.ui.Refreshing
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.Kicker
import com.museroom.app.ui.kit.LiveDot
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoBar
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.NeoRound
import com.museroom.app.ui.kit.NeoSegment
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.ui.kit.Shelf
import com.museroom.app.ui.kit.hardShadow
import com.museroom.app.util.formatClock

/**
 * The room, as a place.
 *
 * It used to be three cards on the home screen, which put the one thing you
 * were actually doing in a column with the shelves. A room is somewhere you
 * are, so it gets a screen of its own, and like the player it covers the rail
 * while you are in it.
 *
 * The mode is a switch rather than a menu, because there are exactly two and
 * the difference between them is one sentence long. The sentence is printed
 * under it, because the cost of together mode — that Museroom becomes the
 * speaker and the other app has to stop — should be stated rather than
 * discovered.
 */
@Composable
fun RoomScreen(onClose: () -> Unit, onOpenQueue: () -> Unit = {}) {
    val context = LocalContext.current
    val c = Neo.colors
    val session by remember { AuthRepository.get(context).session }
        .collectAsStateWithLifecycle()
    val following by FollowSession.following.collectAsStateWithLifecycle()
    val hosting = following == null

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NeoRound(NeoIcons.Chevron, "Close", onClose)
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LiveDot(size = 9.dp)
                    Kicker(if (hosting) "Hosting" else "Listening with")
                }
                Text(
                    if (hosting) "Your room" else "@${following?.handle.orEmpty()}'s room",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 15.sp,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!hosting) {
                NeoButton(
                    "Leave",
                    small = true,
                    tone = NeoTone.Pink,
                    onClick = { FollowSession.stop(); onClose() },
                )
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            if (session == null) {
                Spacer(Modifier.height(10.dp))
                SignInPanel("A room needs an account, so the people in it have names.", heading = false)
                return@Column
            }

            if (hosting) HostControls(onOpenQueue) else GuestPanel()

            Members(hosting = hosting, hostId = following?.hostId)
        }
    }
}

/**
 * The host's side: the mode, what is playing, the transport, the queue.
 *
 * Search, play now, queue, skip and pause exist because the host must be able
 * to change song without leaving the mode. Taking any of them away turns
 * together mode into a thing you can only start and stop.
 */
@Composable
private fun HostControls(onOpenQueue: () -> Unit) {
    val context = LocalContext.current
    val c = Neo.colors
    val on by TogetherHost.on.collectAsStateWithLifecycle()
    val state by TogetherHost.state.collectAsStateWithLifecycle()
    val queue by TogetherHost.queue.collectAsStateWithLifecycle()
    val elsewhere by TogetherHost.playingElsewhere.collectAsStateWithLifecycle()
    val player by RoomPlayer.snapshot.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    NeoCard(
        radius = 18.dp,
        shadow = 5.dp,
        padding = 12.dp,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        NeoSegment(
            options = listOf("Broadcast", "Together"),
            selected = if (on) 1 else 0,
            onPick = { picked ->
                if (picked == 1) TogetherHost.start(context) else TogetherHost.stop()
            },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (on) {
                "Every phone plays through Museroom and starts on the same beat. " +
                    "Nobody's Spotify is the speaker, yours included."
            } else {
                "Your room plays a copy of whatever your music app is playing, " +
                    "a few seconds behind you."
            },
            style = MaterialTheme.typography.bodySmall,
            color = c.ink.copy(alpha = 0.65f),
        )
    }

    if (elsewhere && on) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Another app is still playing here. Pause it, or you will hear two songs.",
            style = MaterialTheme.typography.bodySmall,
            color = c.pink,
        )
    }

    if (!on) return

    // What the room is on, and how far through it is.
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        val playing = state as? TogetherHost.TogetherState.Playing
        TrackCover(
            player.videoId.ifBlank { "room" },
            null,
            Modifier.size(88.dp),
            radius = 14.dp,
            shadow = 4.dp,
            stroke = 3.dp,
            dot = 10.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(
                when (val s = state) {
                    is TogetherHost.TogetherState.Empty -> "Nothing queued"
                    is TogetherHost.TogetherState.Finding -> "Finding \"${s.query}\""
                    is TogetherHost.TogetherState.Starting -> s.title
                    is TogetherHost.TogetherState.Playing -> s.title
                    is TogetherHost.TogetherState.Stuck -> s.reason
                    else -> "Off"
                },
                style = MaterialTheme.typography.titleLarge,
                fontSize = 17.sp,
                color = c.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                playing?.artist.orEmpty().ifBlank { "Search for something to play." },
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = c.ink.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            val fraction = if (player.durationMs > 0) {
                player.positionMs.toFloat() / player.durationMs
            } else {
                0f
            }
            NeoBar(fraction)
            Spacer(Modifier.height(7.dp))
            MonoText(
                "${formatClock(player.positionMs)} / ${formatClock(player.durationMs)}",
                size = 11,
                color = c.ink.copy(alpha = 0.7f),
            )
        }
    }

    val holding = state is TogetherHost.TogetherState.Playing ||
        state is TogetherHost.TogetherState.Starting
    if (holding) {
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoRound(
                NeoIcons.Previous, "Restart",
                onClick = { RoomPlayer.seekTo(0) },
                diameter = 44.dp, icon = 18.dp,
            )
            NeoRound(
                if (player.playing) NeoIcons.Pause else NeoIcons.Play,
                if (player.playing) "Pause" else "Play",
                onClick = { TogetherHost.toggle() },
                diameter = 68.dp, icon = 26.dp, rest = 5.dp,
                fill = c.lime, stroke = c.onAccent, content = c.onAccent,
            )
            NeoRound(
                NeoIcons.Next, "Skip",
                onClick = { TogetherHost.skip() },
                diameter = 44.dp, icon = 18.dp,
            )
        }
    }

    Shelf("Up next", action = "Add", onAction = onOpenQueue)
    Field(value = query, onChange = { query = it }, label = "Search for a song")
    Spacer(Modifier.height(9.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        NeoButton(
            "Play now",
            small = true,
            tone = NeoTone.Lime,
            enabled = query.isNotBlank(),
            onClick = { TogetherHost.playNow(query); query = "" },
        )
        NeoButton(
            "Queue it",
            small = true,
            tone = NeoTone.Paper,
            enabled = query.isNotBlank(),
            onClick = { TogetherHost.enqueue(query); query = "" },
        )
    }

    queue.forEachIndexed { index, item ->
        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            NeoButton(
                "Remove",
                small = true,
                tone = NeoTone.Paper,
                onClick = { TogetherHost.remove(index) },
            )
        }
    }
}

/**
 * The listener's side: whose room this is, what it is doing, and the one thing
 * there is to decide.
 *
 * Not a player. The joiner's music comes out of the same player the host's
 * does; a second set of transport controls here would be a listener steering a
 * room they are not hosting.
 */
@Composable
private fun GuestPanel() {
    val c = Neo.colors
    val room = FollowSession.following.collectAsStateWithLifecycle().value ?: return
    val player by RoomPlayer.snapshot.collectAsStateWithLifecycle()

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        TrackCover(
            room.title.ifBlank { room.hostId },
            null,
            Modifier.size(88.dp),
            radius = 14.dp,
            shadow = 4.dp,
            stroke = 3.dp,
            dot = 10.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(
                room.title.ifBlank { "Waiting for a song" },
                style = MaterialTheme.typography.titleLarge,
                fontSize = 17.sp,
                color = c.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                room.artist,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = c.ink.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            val fraction = if (player.durationMs > 0) {
                player.positionMs.toFloat() / player.durationMs
            } else {
                0f
            }
            NeoBar(fraction)
            Spacer(Modifier.height(7.dp))
            MonoText(
                "${formatClock(player.positionMs)} / ${formatClock(player.durationMs)}",
                size = 11,
                color = c.ink.copy(alpha = 0.7f),
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            when (val s = room.state) {
                is FollowState.Starting -> "Warming up the player"
                is FollowState.Finding -> "Finding the track"
                is FollowState.Loading -> "Loading ${s.title}"
                is FollowState.CatchingUp -> "Catching up"
                is FollowState.Waiting -> "Starting together"
                is FollowState.InStep ->
                    if (kotlin.math.abs(s.offMs) < 1000) {
                        // Worth saying which room this is. Three seconds behind
                        // a friend's Spotify and level with a room that is all
                        // on one clock are both "in step", and they do not
                        // sound the same in a kitchen with two phones out.
                        if (room.together) "In step — everyone together" else "In step"
                    } else {
                        "${if (s.offMs > 0) "behind" else "ahead"} by ${kotlin.math.abs(s.offMs) / 1000}s"
                    }
                is FollowState.Advert -> "Ad break — back in a moment"
                is FollowState.HostAdvert -> "Ad on their end — holding the track"
                is FollowState.Silent -> "The player will not start"
                is FollowState.HostQuiet -> "They stopped playing"
                is FollowState.Stuck -> s.reason
            },
            style = MaterialTheme.typography.bodySmall,
            color = c.ink.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        // The room is where a like actually means something: you came for their
        // taste, so say so without leaving.
        if (room.title.isNotBlank()) {
            LikeHeart(
                userId = room.hostId,
                title = room.title,
                artist = room.artist,
                durationMs = room.durationMs,
            )
        }
    }
}

/**
 * Who is in the room, and how far off they are.
 *
 * Lateness is stated even when it is fine, so the number that is not fine does
 * not arrive as a surprise. Which roster is shown depends on where you are: a
 * host sees their own, a joiner sees the host's, which is the room they are
 * actually in — they used to be told "nobody is in your room", which is true
 * and beside the point.
 */
@Composable
private fun Members(hosting: Boolean, hostId: String?) {
    val context = LocalContext.current
    val c = Neo.colors
    val friends = remember { FriendsRepository.get(context) }
    val session by remember { AuthRepository.get(context).session }
        .collectAsStateWithLifecycle()
    val mine by RoomPresence.members.collectAsStateWithLifecycle()

    var theirs by remember(hostId) { mutableStateOf<List<RoomMember>>(emptyList()) }
    Refreshing(hostId, everyMs = 20_000) {
        val room = hostId ?: return@Refreshing
        friends.roomMembersOf(room).onSuccess { theirs = it }
    }
    LaunchedEffect(Unit) { RoomPresence.start(context) }

    val members = if (hosting) mine else theirs
    val me = session?.userId

    Shelf("In the room", action = "${members.size}", onAction = {})
    if (members.isEmpty()) {
        Text(
            if (hosting) "Nobody has joined yet." else "Just you, so far.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.ink.copy(alpha = 0.6f),
        )
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val palette = listOf(c.lime, c.sky, c.pink, c.violet)
        members.take(4).forEachIndexed { index, member ->
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier
                    .weight(1f)
                    .hardShadow(3.dp, c.ink, shape)
                    .clip(shape)
                    .background(c.card)
                    .border(2.5.dp, c.ink, shape)
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val fill = palette[index % palette.size]
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(fill)
                        .border(2.5.dp, c.onAccent, RoundedCornerShape(percent = 50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        member.handle.take(1).uppercase(),
                        style = bangers(17).copy(
                            color = if (fill == c.violet) Color.White else c.onAccent,
                        ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (member.userId == me) "You" else "@${member.handle}",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Kicker(
                    if (member.userId == me && hosting) "host" else "in step",
                    color = c.ink,
                )
            }
        }
    }
}
