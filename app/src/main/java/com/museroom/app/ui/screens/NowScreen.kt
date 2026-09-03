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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.museroom.app.net.AnsweredListenRequests
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.FriendsRepository
import com.museroom.app.net.ListenRepository
import com.museroom.app.net.ListenRequest
import com.museroom.app.net.PendingRequest
import com.museroom.app.net.RoomMember
import com.museroom.app.notify.Notifier
import com.museroom.app.privacy.PrivacyState
import com.museroom.app.sync.FollowSession
import com.museroom.app.sync.FollowState
import com.museroom.app.sync.RoomPresence
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoAccentCard
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoProgress
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.util.formatClock
import com.museroom.app.util.formatMinutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

            FollowBar()
            FriendRequests()
            ListenInbox()
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
 * Who is listening along with you.
 *
 * A joiner's music comes out of Museroom rather than out of a player, so there
 * is nothing on their phone for anybody to notice. They say they are here, and
 * this is where the host sees it.
 */
@Composable
private fun RoomMembers() {
    val context = LocalContext.current
    val c = Neo.colors
    val auth = remember { AuthRepository.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { RoomPresence.start(context) }
    val members by RoomPresence.members.collectAsStateWithLifecycle()
    if (session == null) return

    NeoAccentCard(fill = c.sky, radius = 18.dp, shadow = 6.dp, padding = 16.dp) {
        Label(
            when (members.size) {
                0 -> "Your room"
                1 -> "Listening with you"
                else -> "${members.size} listening with you"
            },
            color = c.onAccent,
        )
        Spacer(Modifier.size(8.dp))
        if (members.isEmpty()) {
            Text(
                "Nobody is in your room.",
                style = MaterialTheme.typography.titleMedium,
                color = c.onAccent.copy(alpha = 0.75f),
            )
        }
        members.forEach { member ->
            Text(
                member.handle,
                style = MaterialTheme.typography.titleMedium,
                color = c.onAccent,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Requests to listen along, from the host's side.
 *
 * Only the host's side. Being let in no longer needs a screen: the answer is
 * watched for in the background and the music simply starts, which is the
 * whole point of asking rather than being handed a link.
 */
@Composable
private fun ListenInbox() {
    val context = LocalContext.current
    val c = Neo.colors
    val scope = rememberCoroutineScope()
    val listen = remember { ListenRepository.get(context) }
    val auth = remember { AuthRepository.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()

    var inbox by remember { mutableStateOf<List<ListenRequest>>(emptyList()) }
    val answered by AnsweredListenRequests.ids.collectAsStateWithLifecycle()

    LaunchedEffect(session?.userId) {
        while (session != null) {
            listen.inbox().onSuccess { inbox = it }
            delay(12_000)
        }
    }

    // Anything answered from the shade drops out of here at once rather than
    // waiting for the next poll to notice.
    val waiting = inbox.filter { it.id !in answered }
    if (waiting.isEmpty()) return

    fun answer(request: ListenRequest, accept: Boolean) {
        AnsweredListenRequests.mark(request.id)
        Notifier.clearRequest(context, request.id)
        scope.launch {
            listen.respond(request.id, accept)
            listen.inbox().onSuccess { inbox = it }
        }
    }

    Label("Asking to join", color = c.ink)
    waiting.forEach { request ->
        NeoAccentCard(fill = c.sky, radius = 16.dp) {
            Text(
                "${request.handle} wants to listen along",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (request.title.isNotBlank()) {
                Text(
                    request.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onAccent.copy(alpha = 0.75f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                NeoButton("Let them in", small = true, tone = NeoTone.Lime, onClick = {
                    answer(request, true)
                })
                NeoButton("No", small = true, tone = NeoTone.Paper, onClick = {
                    answer(request, false)
                })
            }
        }
    }
}

/**
 * People asking to be friends, on the screen you actually open.
 *
 * It lived only on the Friends tab, which is a tab nobody opens unless they
 * already suspect there is something there. A request nobody sees is a request
 * nobody answers.
 */
@Composable
private fun FriendRequests() {
    val context = LocalContext.current
    val c = Neo.colors
    val scope = rememberCoroutineScope()
    val friends = remember { FriendsRepository.get(context) }
    val auth = remember { AuthRepository.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()

    var pending by remember { mutableStateOf<List<PendingRequest>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        friends.pending().onSuccess { pending = it }
    }

    LaunchedEffect(session?.userId) {
        while (session != null) {
            reload()
            delay(15_000)
        }
    }

    val incoming = pending.filter { it.incoming }
    if (incoming.isEmpty()) return

    Label("Wants to be friends", color = c.ink)
    incoming.forEach { request ->
        NeoAccentCard(fill = c.lime, radius = 16.dp) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Face(request.profile.handle, request.profile.avatarUrl, 38.dp)
                Text(
                    request.profile.handle,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.onAccent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                NeoButton("Accept", small = true, tone = NeoTone.Violet, enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        friends.accept(request.profile)
                        reload()
                        busy = false
                    }
                })
                NeoButton("Decline", small = true, tone = NeoTone.Paper, enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        friends.remove(request.profile)
                        reload()
                        busy = false
                    }
                })
            }
        }
    }
}

/**
 * A listening room: what Museroom is playing, and how far off the host it is.
 *
 * This is the joiner's whole player. There is nothing to press because there
 * is nothing to decide; the host decides, and this follows. The offset is
 * shown because "in sync" is a claim and a number is checkable.
 */
@Composable
private fun FollowBar() {
    val c = Neo.colors
    val following by FollowSession.following.collectAsStateWithLifecycle()
    val room = following ?: return
    val white = androidx.compose.ui.graphics.Color.White

    var cover by remember(room.title, room.artist) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(room.title, room.artist) {
        if (room.title.isNotBlank()) {
            cover = com.museroom.app.media.Artwork.fetch(room.title, room.artist)
        }
    }

    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val stampedAt = remember(room.positionMs) { SystemClock.elapsedRealtime() }
    LaunchedEffect(Unit) {
        while (true) {
            tick = SystemClock.elapsedRealtime()
            delay(400)
        }
    }
    val moving = room.state is FollowState.InStep
    val position = room.positionMs + if (moving) (tick - stampedAt).coerceAtLeast(0) else 0
    val fraction = if (room.durationMs > 0) position.toFloat() / room.durationMs else 0f

    NeoAccentCard(fill = c.violet, radius = 20.dp, shadow = 6.dp, padding = 18.dp) {
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
            NeoButton("Leave", small = true, tone = NeoTone.Paper, onClick = { FollowSession.stop() })
        }

        if (room.title.isNotBlank()) {
            Spacer(Modifier.size(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Box(
                    Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(white.copy(alpha = 0.18f))
                        .border(3.dp, c.onAccent, RoundedCornerShape(14.dp)),
                ) {
                    cover?.let {
                        Image(
                            it.asImageBitmap(), null, Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        room.title, style = MaterialTheme.typography.titleMedium,
                        color = white, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        room.artist.ifBlank { "Unknown artist" },
                        style = MaterialTheme.typography.bodySmall,
                        color = white.copy(alpha = 0.8f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.size(14.dp))
            NeoProgress(fraction, fill = c.lime)
            Spacer(Modifier.size(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MonoText(formatClock(position), color = white)
                MonoText(
                    if (room.durationMs > 0) formatClock(room.durationMs) else "--:--",
                    color = white,
                )
            }
        }

        Spacer(Modifier.size(10.dp))
        Text(
            when (val s = room.state) {
                is FollowState.Starting -> "Warming up the player"
                is FollowState.Finding -> "Finding the track"
                is FollowState.Loading -> "Loading ${s.title}"
                is FollowState.CatchingUp -> "Catching up"
                is FollowState.InStep ->
                    if (kotlin.math.abs(s.offMs) < 1000) "In step"
                    else "${if (s.offMs > 0) "behind" else "ahead"} by ${kotlin.math.abs(s.offMs) / 1000}s"
                is FollowState.Advert -> "Ad break — back in a moment"
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
