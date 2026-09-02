package com.museroom.app.ui

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.data.MuseroomDatabase
import com.museroom.app.media.NowPlaying
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.media.Consent
import com.museroom.app.media.SourceKey
import com.museroom.app.media.SourceRegistry
import com.museroom.app.tracking.PlaybackTracker
import com.museroom.app.media.pickActive
import com.museroom.app.util.NotificationAccess
import com.museroom.app.util.formatAgo
import com.museroom.app.util.formatClock
import com.museroom.app.util.formatMinutes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import com.museroom.app.data.ListeningSessionEntity
import com.museroom.app.privacy.PrivacyState
import com.museroom.app.sync.SyncEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun NowPlayingScreen() {
    val granted = rememberAccessGranted()
    val sessions by NowPlayingRepository.sessions.collectAsStateWithLifecycle()
    val lastEventAt by NowPlayingRepository.lastEventAt.collectAsStateWithLifecycle()
    val error by NowPlayingRepository.error.collectAsStateWithLifecycle()

    // Some players are lazy about pushing an update after a seek. A slow reconcile
    // tick re-reads what we already hold, without rebuilding any bindings.
    LaunchedEffect(granted) {
        while (granted) {
            delay(10_000)
            NowPlayingRepository.resync()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header()

        if (!granted) {
            PermissionGate()
        } else {
            val active = sessions.pickActive()
            if (active == null) {
                EmptyState()
            } else {
                NowPlayingCard(active)
                FingerprintCard(active)
            }
            ConsentCard(sessions)
            PrivacyCard()
            AccountCard()
            ListeningSoFar()
            SelfCheck(sessions = sessions, lastEventAt = lastEventAt, error = error)
            if (active != null) RawMetadata(active)
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Museroom",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Label("Phase 2 · syncing")
    }
}

@Composable
private fun PermissionGate() {
    val context = LocalContext.current
    Card {
        Text(
            text = "Museroom needs to see what is playing",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "Android keeps a media session for whatever app is playing music. " +
                "Reading it is the only way to know what you are listening to, and it " +
                "needs notification access.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = "We read Spotify and YouTube Music only. Every other notification on " +
                "this device is filtered out before it is looked at.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Button(onClick = { NotificationAccess.openSettings(context) }) {
            Text("Turn on notification access")
        }
    }
}

@Composable
private fun EmptyState() {
    Card {
        Text(
            text = "Nothing playing",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "Start a song in Spotify or YouTube Music. This screen updates the " +
                "moment the player publishes its session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NowPlayingCard(track: NowPlaying) {
    // The 4 Hz tick that drives the extrapolated timestamp. Nothing is polled from
    // the player here; we are just re-evaluating arithmetic against a moving clock.
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track.reportedAtElapsed, track.isPlaying) {
        while (true) {
            elapsed = SystemClock.elapsedRealtime()
            delay(250)
        }
    }

    val position = track.positionAt(elapsed)
    val fraction = if (track.durationMs > 0) {
        (position.toFloat() / track.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Artwork(track)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                SourceChip(track)
                Spacer(Modifier.size(3.dp))
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist.ifBlank { "Unknown artist" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (track.album.isNotBlank()) {
                    Text(
                        text = track.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.size(18.dp))

        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp)),
        )

        Spacer(Modifier.size(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Mono(formatClock(position))
            Mono(if (track.isPlaying) "playing" else "paused")
            Mono(if (track.durationMs > 0) formatClock(track.durationMs) else "--:--")
        }
    }
}

@Composable
private fun Artwork(track: NowPlaying) {
    val shape = RoundedCornerShape(8.dp)
    val art = track.artwork
    Box(
        modifier = Modifier
            .size(104.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = "Album artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(1f),
            )
        } else {
            Text(
                text = "no art",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceChip(track: NowPlaying) {
    val shape = RoundedCornerShape(4.dp)
    Text(
        text = track.sourceLabel.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun FingerprintCard(track: NowPlaying) {
    Card {
        Label("Fingerprint")
        Spacer(Modifier.size(6.dp))
        Mono(track.fingerprint, size = 12.sp)
        Spacer(Modifier.size(10.dp))
        Text(
            text = "Two plays of one song must produce this same string, in both apps. " +
                "Where it disagrees, the leaderboard would split the track in two.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the event trail adds up to. Credited on this phone for now, by the same
 * rules that will run server-side once there is a server.
 */
/**
 * Stopping the recording, rather than hiding it after the fact.
 */
@Composable
private fun PrivacyCard() {
    val context = LocalContext.current
    val privacy = remember { PrivacyState.get(context) }
    val isPrivate by privacy.privateSession.collectAsStateWithLifecycle()

    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Private session",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = if (isPrivate) {
                        "Nothing is being recorded. No minutes, no history, nothing shared."
                    } else {
                        "Listening is being counted and shared as your settings allow."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = isPrivate, onCheckedChange = privacy::setPrivate)
        }
    }
}

@Composable
private fun ListeningSoFar() {
    val context = LocalContext.current
    val dao = remember { MuseroomDatabase.get(context).dao() }
    val startOfToday = remember {
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    val todayMs by dao.creditedSince(startOfToday).collectAsStateWithLifecycle(0L)
    val recent by dao.recentSessions(12).collectAsStateWithLifecycle(emptyList())

    val sync = remember { SyncEngine.get(context) }
    val registry = remember { SourceRegistry.get(context) }
    val scope = rememberCoroutineScope()
    var pendingRemoval by remember { mutableStateOf<ListeningSessionEntity?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }

    pendingRemoval?.let { session ->
        RemoveDialog(
            session = session,
            appLabel = registry.label(SourceKey.parse(session.sourcePackage).packageName),
            onDismiss = { pendingRemoval = null },
            onRemove = { alsoStopCounting ->
                scope.launch {
                    if (alsoStopCounting) registry.block(SourceKey.parse(session.sourcePackage))
                    sync.deleteEverywhere(session)
                }
                pendingRemoval = null
            },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all history?") },
            text = {
                Text(
                    "Every track, every event and your minutes are removed from this " +
                        "phone and from the server. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { sync.deleteAllHistory() }
                    confirmWipe = false
                }) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("Cancel") }
            },
        )
    }

    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Label("Listening today")
            Label("${recent.size} tracks kept")
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = formatMinutes(todayMs),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (recent.isEmpty()) {
            Spacer(Modifier.size(10.dp))
            Text(
                text = "Minutes are counted when a track finishes, so the song " +
                    "playing right now is not in this total yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.size(14.dp))
            Text(
                text = "Tap an entry to remove it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            recent.forEach { session ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pendingRemoval = session }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = session.artist.ifBlank { "Unknown artist" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Mono(formatMinutes(session.creditedMs), size = 12.sp)
                }
            }
            Spacer(Modifier.size(10.dp))
            TextButton(onClick = { confirmWipe = true }) { Text("Delete all history") }
        }
    }
}

/**
 * Removing one entry. The second action exists because an unwanted entry is
 * usually a symptom: a player that should not have been counted in the first
 * place. Fixing the cause in the same tap saves finding this dialog again
 * tomorrow.
 */
@Composable
private fun RemoveDialog(
    session: ListeningSessionEntity,
    appLabel: String,
    onDismiss: () -> Unit,
    onRemove: (alsoStopCounting: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove this?") },
        text = {
            Column {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = session.artist.ifBlank { "Unknown artist" },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = "It is deleted from this phone and from the server, and its " +
                        "minutes come off your total.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRemove(false) }) { Text("Remove") }
        },
        dismissButton = {
            TextButton(onClick = { onRemove(true) }) { Text("Remove, stop counting $appLabel") }
        },
    )
}

@Composable
private fun SelfCheck(sessions: List<NowPlaying>, lastEventAt: Long, error: String?) {
    Card {
        Label("Self-check")
        Spacer(Modifier.size(8.dp))

        // A listener that has quietly stopped reporting looks exactly like a user
        // who is not playing anything. Name the difference on screen.
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.size(8.dp))
        } else if (lastEventAt == 0L) {
            Text(
                text = "Connected, but the player has not reported anything yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
        }

        KeyValue("Last event", formatAgo(lastEventAt))
        KeyValue("Sessions seen", sessions.size.toString())
        Spacer(Modifier.size(8.dp))
        if (sessions.isEmpty()) {
            Mono("no active media sessions", size = 12.sp)
        } else {
            sessions.forEach { session ->
                SourceRow(session)
            }
        }
    }
}

/**
 * One detected player. An unrecognised one is not a failure, it is a question:
 * package names differ across forks and regional builds, so the user decides.
 */
@Composable
private fun SourceRow(session: NowPlaying) {
    val context = LocalContext.current
    val registry = remember { SourceRegistry.get(context) }
    registry.decisions.collectAsStateWithLifecycle().value

    val key = session.sourceKey
    val consent = registry.consentFor(key)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (consent == Consent.ALLOWED) registry.block(key) else registry.allow(key)
                NowPlayingRepository.resync()
            }
            .padding(vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Mono(session.sourceLabel + (session.site?.let { "  ($it)" } ?: ""), size = 12.sp)
            Mono(
                when (consent) {
                    Consent.ALLOWED -> "counting"
                    Consent.BLOCKED -> "blocked"
                    Consent.UNDECIDED -> "not asked yet"
                },
                size = 12.sp,
            )
        }
        Text(
            text = "${session.packageName}  ·  ${session.contentKind}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The consent prompt.
 *
 * Deliberately shows the app and, for a browser, the site, but never the title of
 * what is playing. A prompt about something private should not itself put that
 * thing on screen, and the app plus site is enough to decide on.
 */
@Composable
private fun ConsentCard(sessions: List<NowPlaying>) {
    val context = LocalContext.current
    val registry = remember { SourceRegistry.get(context) }
    registry.decisions.collectAsStateWithLifecycle().value

    val undecided = sessions
        .map { it.sourceKey }
        .distinctBy { it.id }
        .filter { registry.consentFor(it) == Consent.UNDECIDED }

    if (undecided.isEmpty()) return

    Card {
        Text(
            text = if (undecided.size == 1) "New source detected" else "New sources detected",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "Nothing from these is being counted or shared until you say so.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(14.dp))

        undecided.forEach { key ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = registry.label(key.packageName),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = key.site ?: key.packageName,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        registry.allow(key)
                        NowPlayingRepository.resync()
                    }) {
                        Text(if (key.site != null) "Count this site" else "Count this app")
                    }
                    TextButton(onClick = {
                        registry.block(key)
                        NowPlayingRepository.resync()
                    }) { Text("Never") }
                }
            }
        }
    }
}

@Composable
private fun RawMetadata(track: NowPlaying) {
    var expanded by remember { mutableStateOf(false) }
    Card(onClick = { expanded = !expanded }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Label("Raw metadata · ${track.rawMetadata.size} keys")
            Label(if (expanded) "hide" else "show")
        }
        if (expanded) {
            Spacer(Modifier.size(10.dp))
            track.rawMetadata.forEach { (key, value) ->
                Mono("$key = $value", size = 11.sp)
                Spacer(Modifier.size(2.dp))
            }
        }
    }
}

// ---- small shared pieces ----

@Composable
private fun Card(
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text = text.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Mono(text: String, size: androidx.compose.ui.unit.TextUnit = 13.sp) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = size,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Mono(value, size = 12.sp)
    }
}

/**
 * Notification access can only change outside the app, so re-read it every time
 * we come back to the foreground.
 */
@Composable
private fun rememberAccessGranted(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = NotificationAccess.isGranted(context)
                if (granted) {
                    NowPlayingRepository.start(context)
                    PlaybackTracker.start(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}
