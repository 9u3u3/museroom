package com.museroom.app.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.data.ListeningSessionEntity
import com.museroom.app.data.MuseroomDatabase
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.media.Sources
import com.museroom.app.media.pickActive
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.ListenRepository
import com.museroom.app.net.ListenRequest
import com.museroom.app.media.PlayerCommands
import com.museroom.app.media.PlayerPreference
import com.museroom.app.privacy.PrivacyState
import com.museroom.app.sync.SyncEngine
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoAccentCard
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoPill
import com.museroom.app.ui.kit.NeoProgress
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.util.formatClock
import com.museroom.app.util.formatMinutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Now playing, today's tally, and the tracks behind it. */
@Composable
fun NowScreen() {
    val context = LocalContext.current
    val c = Neo.colors
    val scope = rememberCoroutineScope()

    val sessions by NowPlayingRepository.sessions.collectAsStateWithLifecycle()
    val privacy = remember { PrivacyState.get(context) }
    val isPrivate by privacy.privateSession.collectAsStateWithLifecycle()
    val auth = remember { AuthRepository.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()
    val sync = remember { SyncEngine.get(context) }
    val dao = remember { MuseroomDatabase.get(context).dao() }

    val startOfToday = remember {
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val todayMs by dao.creditedSince(startOfToday).collectAsStateWithLifecycle(0L)
    val recent by dao.recentSessions(8).collectAsStateWithLifecycle(emptyList())

    var pending by remember { mutableStateOf<ListeningSessionEntity?>(null) }
    val active = sessions.pickActive()
    ListenInbox()

    pending?.let { entry ->
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = c.card,
            titleContentColor = c.ink,
            textContentColor = c.ink,
            title = { Text("Remove this?", style = bangers(26).copy(color = c.ink)) },
            text = {
                Column {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Note(entry.artist.ifBlank { "Unknown artist" })
                    Spacer(Modifier.size(10.dp))
                    Note("Deleted from this phone and from the server. Its minutes come off your total.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { sync.deleteEverywhere(entry) }
                    pending = null
                }) { Text("REMOVE", color = c.pink, style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text("KEEP", color = c.ink, style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (isPrivate) {
            NeoAccentCard(fill = c.pink, radius = 16.dp) {
                Text("Private session — nothing is being recorded",
                    style = MaterialTheme.typography.titleMedium)
            }
        }

        if (active == null) {
            NeoCard(radius = 20.dp, shadow = 6.dp, padding = 20.dp) {
                Text("Nothing playing", style = MaterialTheme.typography.titleLarge, color = c.ink)
                Spacer(Modifier.size(4.dp))
                Note("Start a song in Spotify or YouTube Music.")
            }
        } else {
            NowPlayingCard(active)
        }

        NeoAccentCard(fill = c.lime, radius = 20.dp, shadow = 6.dp, padding = 18.dp) {
            Label("Listening today", color = c.onAccent)
            Text(formatMinutes(todayMs), style = bangers(56).copy(color = c.onAccent))
        }

        if (session == null) {
            SignInPanel("Your listening stays on this phone until you sign in.")
        }

        if (recent.isNotEmpty()) {
            Label("Recent — tap to remove", color = c.ink)
            recent.take(4).forEach { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.card)
                        .border(3.dp, c.ink, RoundedCornerShape(14.dp))
                        .clickable { pending = entry }
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title, style = MaterialTheme.typography.bodyMedium,
                            color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            entry.artist.ifBlank { "Unknown artist" },
                            style = MaterialTheme.typography.bodySmall,
                            color = c.ink.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    MonoText(formatMinutes(entry.creditedMs), size = 12, color = c.ink)
                }
            }
        }
    }
}

/**
 * Requests to listen along: the host's side, and the answer coming back.
 *
 * Both live here because both are about the same moment, and because a person
 * who just asked is most likely looking at this screen.
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
    var accepted by remember { mutableStateOf<ListenRequest?>(null) }
    var seenFrom by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(session?.userId) {
        while (session != null) {
            listen.inbox().onSuccess { inbox = it }
            listen.answered(seenFrom).onSuccess { answers ->
                answers.firstOrNull()?.let { accepted = it }
            }
            delay(12_000)
        }
    }

    accepted?.let { answer ->
        NeoAccentCard(fill = c.lime, radius = 16.dp) {
            Text("@${answer.handle} let you in", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                NeoButton("Play it", small = true, onClick = {
                    val target = PlayerPreference.get(context).preferred
                        ?.takeIf { PlayerCommands.isInstalled(context, it) }
                        ?: Sources.packages.firstOrNull { PlayerCommands.isInstalled(context, it) }
                    if (target != null) {
                        scope.launch {
                            PlayerCommands.play(context, target, answer.title, answer.artist, answer.sourceTrackId)
                        }
                    }
                    seenFrom = System.currentTimeMillis()
                    accepted = null
                })
                NeoButton("Later", small = true, tone = NeoTone.Paper, onClick = {
                    seenFrom = System.currentTimeMillis()
                    accepted = null
                })
            }
        }
        Spacer(Modifier.size(4.dp))
    }

    inbox.forEach { request ->
        NeoAccentCard(fill = c.sky, radius = 16.dp) {
            Text("@${request.handle} wants to listen along", style = MaterialTheme.typography.titleMedium)
            if (request.title.isNotBlank()) {
                Text(request.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                NeoButton("Let them in", small = true, tone = NeoTone.Lime, onClick = {
                    scope.launch {
                        listen.respond(request.id, true)
                        listen.inbox().onSuccess { inbox = it }
                    }
                })
                NeoButton("No", small = true, tone = NeoTone.Paper, onClick = {
                    scope.launch {
                        listen.respond(request.id, false)
                        listen.inbox().onSuccess { inbox = it }
                    }
                })
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun NowPlayingCard(track: com.museroom.app.media.NowPlaying) {
    val c = Neo.colors
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track.reportedAtElapsed, track.isPlaying) {
        while (true) {
            elapsed = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val position = track.positionAt(elapsed)
    val fraction = if (track.durationMs > 0) position.toFloat() / track.durationMs else 0f

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(c.violet)
                .border(3.dp, c.onAccent, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            track.artwork?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            NeoPill(
                text = Sources.label(track.packageName),
                fill = c.lime,
                accent = true,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp),
            )
        }
        Spacer(Modifier.size(18.dp))
        Text(
            track.title, style = MaterialTheme.typography.headlineLarge,
            color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(5.dp))
        Text(
            listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge,
            color = c.ink.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(16.dp))
        NeoProgress(fraction)
        Spacer(Modifier.size(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoText(formatClock(position), color = c.ink)
            MonoText(if (track.isPlaying) "playing" else "paused", color = c.ink)
            MonoText(if (track.durationMs > 0) formatClock(track.durationMs) else "--:--", color = c.ink)
        }
    }
}
