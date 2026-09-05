package com.museroom.app.ui.screens

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.data.MuseroomDatabase
import com.museroom.app.media.NowPlaying
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.media.pickActive
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.FriendsRepository
import com.museroom.app.net.RoomMember
import com.museroom.app.net.Updates
import com.museroom.app.privacy.PrivacyState
import com.museroom.app.sync.FollowSession
import com.museroom.app.sync.FollowState
import com.museroom.app.sync.RoomPresence
import com.museroom.app.sync.RoomPlayer
import com.museroom.app.sync.TogetherHost
import com.museroom.app.ui.Refreshing
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoAccentCard
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoProgress
import com.museroom.app.ui.kit.NeoSwitch
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.util.formatClock
import com.museroom.app.util.formatMinutes
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/** What the player takes once it has folded: cover, bar, clock. */
private val FOLDED_HEIGHT = 108.dp

@Composable
fun NowScreen() {
    val context = LocalContext.current
    val c = Neo.colors
    val density = LocalDensity.current

    val sessions by NowPlayingRepository.sessions.collectAsStateWithLifecycle()
    val privacy = remember { PrivacyState.get(context) }
    val isPrivate by privacy.privateSession.collectAsStateWithLifecycle()
    val auth = remember { AuthRepository.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()
    val dao = remember { MuseroomDatabase.get(context).dao() }

    val startOfToday = remember {
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val todayMs by dao.creditedSince(startOfToday).collectAsStateWithLifecycle(0L)
    val todayTracks by dao.tracksSince(startOfToday).collectAsStateWithLifecycle(0)

    val active = sessions.pickActive()
    val following by FollowSession.following.collectAsStateWithLifecycle()

    val scroll = rememberScrollState()

    // The player is painted over the page rather than sitting above it in a
    // column, and the page reserves exactly the room the open player takes.
    // That is what keeps the two from ever drawing on top of each other: the
    // page slides underneath, and the player folds in place at the same rate.
    var openPx by remember { mutableIntStateOf(0) }
    var contentPx by remember { mutableIntStateOf(0) }
    val foldedPx = with(density) { FOLDED_HEIGHT.toPx() }
    val travel = (openPx - foldedPx).coerceAtLeast(1f)
    val collapse = if (openPx == 0) 0f else (scroll.value / travel).coerceIn(0f, 1f)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportPx = with(density) { maxHeight.toPx() }
        // Exactly enough slack to fold the player and not a pixel more. Too
        // little and it can never fold on a quiet day; too much and the page
        // keeps travelling after the fold is done, dragging the cards up
        // underneath and cutting them off.
        val runwayPx = (travel - (contentPx - viewportPx)).coerceIn(0f, travel)

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll),
        ) {
            Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { contentPx = it.height }
                .padding(horizontal = 20.dp)
                .padding(top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (active != null) {
                Spacer(Modifier.height(with(density) { openPx.toDp() }))
            }

            UpdateCard()
            FollowBar()
            TogetherCard()
            RoomMembers()

            if (isPrivate) {
                NeoAccentCard(fill = c.pink, radius = 16.dp) {
                    Text(
                        "Private session — nothing is being recorded",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // While a room is running, this phone playing nothing of its own is
            // the normal case, not news.
            if (active == null && following == null) {
                NeoCard(radius = 20.dp, shadow = 6.dp, padding = 20.dp) {
                    Text("Nothing playing", style = MaterialTheme.typography.titleLarge, color = c.ink)
                    Spacer(Modifier.size(4.dp))
                    Note("Start a song in Spotify, YouTube Music or another music app.")
                }
            }

            NeoAccentCard(fill = c.lime, radius = 20.dp, shadow = 6.dp, padding = 18.dp) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Label("Listening today", color = c.onAccent)
                        Text(formatMinutes(todayMs), style = bangers(52).copy(color = c.onAccent))
                    }
                    // The right half was empty, which on a card this loud reads
                    // as a missing number rather than as space.
                    Column(horizontalAlignment = Alignment.End) {
                        Label("Tracks", color = c.onAccent)
                        Text("$todayTracks", style = bangers(52).copy(color = c.onAccent))
                    }
                }
            }

            if (session == null) {
                SignInPanel("Your listening stays on this phone until you sign in.")
            }

        }

            if (active != null) {
                Spacer(Modifier.height(with(density) { runwayPx.toDp() }))
            }
        }

        if (active != null) {
            NowPlayingHeader(
                track = active,
                collapse = collapse,
                modifier = Modifier
                    .onSizeChanged { if (collapse < 0.02f && it.height > 0) openPx = it.height }
                    .background(c.paper)
                    .padding(horizontal = 20.dp)
                    .padding(top = 10.dp, bottom = 12.dp),
            )
        }
    }
}

/**
 * What is playing here, folding down as the page moves under it.
 *
 * Everything travels rather than swapping: the cover shrinks into a thumbnail
 * and slides left, the big title fades as a small one appears beside the cover,
 * and the progress bar simply rides along. Nothing is ever cut off mid-word,
 * because the two titles are separate pieces of text with their own line
 * limits rather than one piece being squeezed.
 */
@Composable
private fun NowPlayingHeader(track: NowPlaying, collapse: Float, modifier: Modifier = Modifier) {
    val c = Neo.colors
    val density = LocalDensity.current

    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track.reportedAtElapsed, track.isPlaying) {
        while (true) {
            elapsed = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val position = track.positionAt(elapsed)
    val fraction = if (track.durationMs > 0) position.toFloat() / track.durationMs else 0f

    // Measured once while open, then used to fold. Guessing it would either
    // clip a two-line title or leave a gap under a one-line one.
    var openTextPx by remember { mutableIntStateOf(0) }

    // One title hands over to the other rather than both being half there.
    val small = ((collapse - 0.55f) / 0.4f).coerceIn(0f, 1f)
    val large = (1f - collapse / 0.45f).coerceIn(0f, 1f)

    // The cover was as wide as the screen, which on a tall phone left the card
    // underneath hanging off the bottom edge. A square that fills the width is
    // only right on a screen wide enough to spare the height.
    val tallest = (LocalConfiguration.current.screenHeightDp * 0.40f).dp

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val open = if (maxWidth < tallest) maxWidth else tallest
        val cover = lerp(open, 56.dp, collapse)
        val corner = lerp(22.dp, 14.dp, collapse)
        val shape = RoundedCornerShape(corner)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(cover)
                        .clip(shape)
                        .background(c.violet)
                        .border(3.dp, c.onAccent, shape),
                ) {
                    track.artwork?.let {
                        Image(
                            it.asImageBitmap(), null, Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Spacer(Modifier.width(lerp(0.dp, 13.dp, collapse)))
                Column(
                    Modifier
                        .weight(1f)
                        .alpha(small),
                ) {
                    Text(
                        track.title, style = MaterialTheme.typography.titleMedium,
                        color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artist.ifBlank { "Unknown artist" },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.ink.copy(alpha = 0.62f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (openTextPx == 0) Modifier
                        else Modifier.height(with(density) { (openTextPx * (1f - collapse)).toDp() })
                    )
                    .clipToBounds(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        // Unbounded so the fold never changes what was measured.
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .onSizeChanged { if (collapse < 0.02f && it.height > 0) openTextPx = it.height }
                        .alpha(large),
                ) {
                    Spacer(Modifier.size(16.dp))
                    Text(
                        track.title, style = MaterialTheme.typography.headlineLarge,
                        color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(5.dp))
                    Text(
                        listOf(track.artist, track.album).filter { it.isNotBlank() }
                            .joinToString(" · ").ifBlank { "Unknown artist" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.ink.copy(alpha = 0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.size(lerp(16.dp, 11.dp, collapse)))
            NeoProgress(fraction)
            Spacer(Modifier.size(lerp(8.dp, 5.dp, collapse)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MonoText(formatClock(position), color = c.ink)
                MonoText(
                    if (track.isPlaying) "playing" else "paused",
                    color = c.ink,
                    modifier = Modifier.alpha(large),
                )
                MonoText(
                    if (track.durationMs > 0) formatClock(track.durationMs) else "--:--",
                    color = c.ink,
                )
            }
        }
    }
}

/**
 * The two kinds of room, and the switch between them.
 *
 * Broadcast is a room around whatever the host already had on: Museroom reads
 * their Spotify and plays a copy for everybody else, three seconds behind,
 * because a listener told about a song at the instant it starts cannot have
 * fetched it yet. That distance is what stops anybody losing the opening, and
 * it is the right default.
 *
 * It is also exactly wrong when two phones are on the same table. Then the
 * host being three seconds early is the whole problem, and no amount of
 * chasing on the listener's side fixes it, because the thing that is ahead is
 * a player Museroom does not own. So together mode moves the host onto the
 * same player as everybody else. Nobody's music app is the speaker, and being
 * level stops being a trick.
 *
 * The cost is stated rather than discovered: the music comes out of Museroom
 * now, so the other app has to be told to stop, and Museroom can only ask.
 */
@Composable
private fun TogetherCard() {
    val context = LocalContext.current
    val c = Neo.colors
    val auth = remember { AuthRepository.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()
    val following by FollowSession.following.collectAsStateWithLifecycle()
    val on by TogetherHost.on.collectAsStateWithLifecycle()
    val state by TogetherHost.state.collectAsStateWithLifecycle()
    val queue by TogetherHost.queue.collectAsStateWithLifecycle()
    val elsewhere by TogetherHost.playingElsewhere.collectAsStateWithLifecycle()
    val player by RoomPlayer.snapshot.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    // Somebody in a friend's room is not hosting one. The switch would be a
    // second player fighting the first for the same page.
    if (session == null || following != null) return

    NeoCard(radius = 18.dp, shadow = 6.dp, padding = 16.dp) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Label("Your room")
                Spacer(Modifier.size(3.dp))
                Text(
                    if (on) "Together" else "Broadcast",
                    style = MaterialTheme.typography.titleLarge,
                    color = c.ink,
                )
            }
            NeoSwitch(
                checked = on,
                onCheckedChange = { wanted ->
                    if (wanted) TogetherHost.start(context) else TogetherHost.stop()
                },
            )
        }

        Spacer(Modifier.size(8.dp))
        Note(
            if (on) {
                "Everyone, including you, plays in Museroom. Pause Spotify or both will sound."
            } else {
                "Your room plays a copy of whatever your music app is playing, a few seconds behind you."
            },
        )

        if (!on) return@NeoCard

        if (elsewhere) {
            Spacer(Modifier.size(8.dp))
            Text(
                "Another app is still playing here. Pause it, or you will hear two songs.",
                style = MaterialTheme.typography.bodySmall,
                color = c.pink,
            )
        }

        Spacer(Modifier.size(10.dp))
        Text(
            when (val s = state) {
                is TogetherHost.TogetherState.Off -> ""
                is TogetherHost.TogetherState.Empty -> "Nothing queued. Search for something to play."
                is TogetherHost.TogetherState.Finding -> "Finding \"${s.query}\""
                // Not a countdown against named people. Everybody is fetching
                // the same song and the room lets go on one moment; who is
                // ready is not something this promises to know.
                is TogetherHost.TogetherState.Starting -> "Starting together — ${s.title}"
                is TogetherHost.TogetherState.Playing ->
                    listOf(s.title, s.artist).filter { it.isNotBlank() }.joinToString(" · ")
                is TogetherHost.TogetherState.Stuck -> s.reason
            },
            style = MaterialTheme.typography.titleMedium,
            color = c.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.size(12.dp))
        Field(value = query, onChange = { query = it }, label = "Search for a song")
        Spacer(Modifier.size(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            NeoButton(
                "Play now",
                small = true,
                enabled = query.isNotBlank(),
                onClick = {
                    TogetherHost.playNow(query)
                    query = ""
                },
            )
            NeoButton(
                "Add",
                small = true,
                tone = NeoTone.Paper,
                enabled = query.isNotBlank(),
                onClick = {
                    TogetherHost.enqueue(query)
                    query = ""
                },
            )
        }

        // Only once there is something to press these on. A pause button over
        // an empty room is a button that does nothing.
        val holding = state is TogetherHost.TogetherState.Playing ||
            state is TogetherHost.TogetherState.Starting
        if (holding) {
            Spacer(Modifier.size(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                NeoButton(
                    if (player.playing) "Pause" else "Play",
                    small = true,
                    tone = NeoTone.Lime,
                    onClick = { TogetherHost.toggle() },
                )
                NeoButton(
                    "Skip",
                    small = true,
                    tone = NeoTone.Paper,
                    onClick = { TogetherHost.skip() },
                )
            }
        }

        if (queue.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            Label("Up next")
            queue.forEachIndexed { index, item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
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

    NeoAccentCard(fill = c.pink, radius = 18.dp, shadow = 6.dp, padding = 16.dp) {
        Label("Update", color = c.onAccent)
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

/**
 * Who is in the room.
 *
 * Which room depends on where you are. A host sees their own: people say they
 * are there on a stamp of their own, because a joiner's music comes out of
 * Museroom rather than out of a player and there is nothing on their phone to
 * notice. A joiner sees the host's, which is the room they are actually in —
 * they used to be told "nobody is in your room", which is true and beside the
 * point.
 */
@Composable
private fun RoomMembers() {
    val context = LocalContext.current
    val c = Neo.colors
    val auth = remember { AuthRepository.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()
    val friends = remember { FriendsRepository.get(context) }
    val following by FollowSession.following.collectAsStateWithLifecycle()
    val host = following?.hostId

    LaunchedEffect(Unit) { RoomPresence.start(context) }
    val mine by RoomPresence.members.collectAsStateWithLifecycle()

    var theirs by remember(host) { mutableStateOf<List<RoomMember>>(emptyList()) }
    Refreshing(host, everyMs = 20_000) {
        val inRoom = host ?: return@Refreshing
        friends.roomMembersOf(inRoom).onSuccess { theirs = it }
    }

    if (session == null) return
    val me = session?.userId
    val members = if (host != null) theirs else mine

    NeoAccentCard(fill = c.sky, radius = 18.dp, shadow = 6.dp, padding = 16.dp) {
        Label(
            when {
                host != null -> "In ${following?.handle.orEmpty()}'s room"
                members.isEmpty() -> "Your room"
                members.size == 1 -> "Listening with you"
                else -> "${members.size} listening with you"
            },
            color = c.onAccent,
        )
        Spacer(Modifier.size(8.dp))
        if (members.isEmpty()) {
            Text(
                if (host != null) "Just you, so far." else "Nobody is in your room.",
                style = MaterialTheme.typography.titleMedium,
                color = c.onAccent.copy(alpha = 0.75f),
            )
        }
        members.forEach { member ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Face(member.handle, member.avatarUrl, 28.dp, border = c.onAccent)
                Text(
                    member.handle + if (member.userId == me) " · you" else "",
                    style = MaterialTheme.typography.titleMedium,
                    color = c.onAccent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The strip that says whose room you are in.
 *
 * Deliberately not a player. It used to carry a cover, a title, a progress bar
 * and a clock, all of which the card above it was already showing — the same
 * track drawn twice, once with artwork and once without. The joiner gets the
 * same one player the host gets; this only says whose music it is, offers the
 * one thing there is to decide, and reports how the following is going.
 */
@Composable
private fun FollowBar() {
    val c = Neo.colors
    val following by FollowSession.following.collectAsStateWithLifecycle()
    val room = following ?: return
    val white = androidx.compose.ui.graphics.Color.White

    NeoAccentCard(fill = c.violet, radius = 20.dp, shadow = 6.dp, padding = 16.dp) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Listening with ${room.handle}",
                style = MaterialTheme.typography.titleMedium,
                color = white,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // The room is where a like actually means something: you came for
            // their taste, so say so without leaving.
            if (room.title.isNotBlank()) {
                LikeHeart(
                    userId = room.hostId,
                    title = room.title,
                    artist = room.artist,
                    durationMs = room.durationMs,
                    tint = c.pink,
                    edge = c.onAccent,
                )
            }
            NeoButton("Leave", small = true, tone = NeoTone.Paper, onClick = { FollowSession.stop() })
        }

        Spacer(Modifier.size(9.dp))
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
            color = white.copy(alpha = 0.85f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // What the player says about itself, shown only when it is not doing
        // the one thing it is for. Unlovely, and better than a guess.
        (room.state as? FollowState.Silent)?.detail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.size(4.dp))
            MonoText(it, size = 10, color = white.copy(alpha = 0.7f))
        }
    }
}
