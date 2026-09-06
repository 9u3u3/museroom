package com.museroom.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.net.AuthRepository
import com.museroom.app.player.LocalPlayer
import com.museroom.app.player.Playback
import com.museroom.app.player.SleepTimer
import com.museroom.app.sync.TogetherHost
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoPill
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.ui.kit.hardShadow
import kotlinx.coroutines.delay

/**
 * The track, full screen.
 *
 * Every control here does something, which is the rule this surface has
 * followed since it was written: a button that silently does nothing is worse
 * than a button that is missing.
 *
 * The transport is five wide because shuffle and repeat belong beside the thing
 * they change rather than three screens away in settings, and the tray under it
 * is the four places this track can be taken: its words, the queue it sits in,
 * a list to keep it in, and the clock that will stop it.
 */
@Composable
fun PlayerScreen(
    onClose: () -> Unit,
    onOpenArtist: (String) -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onStartedRoom: () -> Unit = {},
) {
    val c = Neo.colors
    val track by Playback.current.collectAsStateWithLifecycle()
    val snapshot by Playback.snapshot.collectAsStateWithLifecycle()
    val from by Playback.from.collectAsStateWithLifecycle()
    val queue by Playback.queue.collectAsStateWithLifecycle()
    val index by Playback.index.collectAsStateWithLifecycle()
    val shuffle by Playback.shuffle.collectAsStateWithLifecycle()
    val repeat by Playback.repeat.collectAsStateWithLifecycle()
    val sleepEndsAt by SleepTimer.endsAt.collectAsStateWithLifecycle()
    val sleepAfterTrack by SleepTimer.afterThisTrack.collectAsStateWithLifecycle()
    val hosting by TogetherHost.on.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val signedIn by remember { AuthRepository.get(context).session }
        .collectAsStateWithLifecycle()

    var saving by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var timing by remember { mutableStateOf(false) }

    // The engine never polls, because nothing in it needs to know where it is
    // between events. A moving scrub bar does, so the screen showing one asks,
    // and stops asking the moment it goes away.
    LaunchedEffect(snapshot.playing) {
        while (snapshot.playing) {
            delay(500)
            LocalPlayer.tick()
        }
    }

    val song = track ?: return
    if (saving) {
        SaveToPlaylist(song, onDismiss = { saving = false })
        return
    }
    if (reading) {
        LyricsScreen(onClose = { reading = false })
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundIcon(NeoIcons.Chevron, "Close", onClick = onClose, diameter = 42.dp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (from.isBlank()) "PLAYING" else "PLAYING FROM",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.W900,
                    letterSpacing = 1.6.sp,
                    fontSize = 9.sp,
                    color = c.ink.copy(alpha = 0.5f),
                )
                Text(
                    from.ifBlank { "Museroom" },
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RoundIcon(
                NeoIcons.Queue,
                "Queue",
                onClick = onOpenQueue,
                diameter = 42.dp,
            )
        }

        Spacer(Modifier.height(10.dp))

        // The cover pops rather than fades when the track changes, because the
        // track changing is the only thing on this screen worth interrupting
        // somebody for.
        val pop = rememberPop(song.id)
        TrackCover(
            id = song.id,
            url = song.artworkUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .scale(pop),
            radius = 22.dp,
            shadow = 8.dp,
            stroke = 3.dp,
            dot = 11.dp,
        )

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.Top) {
            Text(
                song.title,
                style = MaterialTheme.typography.headlineLarge,
                fontSize = 26.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Heart(song, size = 26)
        }
        if (song.artist.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                song.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = if (song.artistId.isBlank()) c.ink.copy(alpha = 0.68f) else c.violet,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { if (song.artistId.isNotBlank()) onOpenArtist(song.artistId) },
            )
        }
        // Said here as well as on the bar, because this is the screen somebody
        // is looking at when they wonder why it went quiet.
        if (snapshot.detail.isNotBlank() && !snapshot.playing) {
            Spacer(Modifier.height(8.dp))
            NeoPill(snapshot.detail, fill = c.pink, accent = true)
        }

        Spacer(Modifier.height(18.dp))

        val duration = if (snapshot.durationMs > 0) snapshot.durationMs else song.durationMs
        Scrubber(
            positionMs = snapshot.positionMs,
            durationMs = duration,
            onSeek = { Playback.seekTo(it) },
        )

        Spacer(Modifier.height(20.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Shuffle and repeat sit on the ends rather than in a menu. They
            // are states rather than actions, so they are drawn lit when they
            // are on instead of announcing themselves only when pressed.
            RoundIcon(
                NeoIcons.Shuffle,
                if (shuffle) "Shuffle, on" else "Shuffle, off",
                onClick = { Playback.setShuffle(!shuffle) },
                diameter = 44.dp, icon = 18.dp, rest = 3.dp,
                fill = if (shuffle) c.lime else c.card,
                stroke = if (shuffle) c.onAccent else c.ink,
                content = if (shuffle) c.onAccent else c.ink,
            )
            Spacer(Modifier.size(12.dp))
            RoundIcon(
                NeoIcons.Previous, "Previous",
                onClick = { Playback.previous() },
                diameter = 54.dp, icon = 22.dp, rest = 4.dp,
            )
            Spacer(Modifier.size(14.dp))
            RoundIcon(
                if (snapshot.playing) NeoIcons.Pause else NeoIcons.Play,
                if (snapshot.playing) "Pause" else "Play",
                onClick = { Playback.toggle() },
                diameter = 76.dp, icon = 30.dp, rest = 5.dp, weight = 3.2f,
                fill = c.lime, stroke = c.onAccent, content = c.onAccent,
            )
            Spacer(Modifier.size(14.dp))
            RoundIcon(
                NeoIcons.Next, "Next",
                onClick = { Playback.next() },
                diameter = 54.dp, icon = 22.dp, rest = 4.dp,
            )
            Spacer(Modifier.size(12.dp))
            val repeating = repeat != Playback.Repeat.Off
            RoundIcon(
                if (repeat == Playback.Repeat.One) NeoIcons.RepeatOne else NeoIcons.Repeat,
                when (repeat) {
                    Playback.Repeat.Off -> "Repeat, off"
                    Playback.Repeat.All -> "Repeat the queue"
                    Playback.Repeat.One -> "Repeat this song"
                },
                onClick = { Playback.cycleRepeat() },
                diameter = 44.dp, icon = 18.dp, rest = 3.dp,
                fill = if (repeating) c.lime else c.card,
                stroke = if (repeating) c.onAccent else c.ink,
                content = if (repeating) c.onAccent else c.ink,
            )
        }

        Spacer(Modifier.height(18.dp))

        // The four places this track can be taken.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Tray(NeoIcons.Notes, "Lyrics", Modifier.weight(1f)) { reading = true }
            Tray(NeoIcons.Queue, "Queue", Modifier.weight(1f), onClick = onOpenQueue)
            Tray(NeoIcons.Plus, "Save", Modifier.weight(1f)) { saving = true }
            Tray(
                NeoIcons.Timer,
                if (sleepAfterTrack || sleepEndsAt > 0) "Timer on" else "Timer",
                Modifier.weight(1f),
                lit = sleepAfterTrack || sleepEndsAt > 0,
            ) { timing = true }
        }

        Spacer(Modifier.weight(1f))

        // Starting a room from the player is the shortest path there is between
        // hearing something and somebody else hearing it too.
        NeoButton(
            text = if (hosting) "Playing in your room" else "Start a room with this",
            tone = if (hosting) NeoTone.Paper else NeoTone.Violet,
            enabled = signedIn != null,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val query = listOf(song.title, song.artist)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                if (!hosting) TogetherHost.start(context)
                TogetherHost.playNow(query)
                onStartedRoom()
            },
        )
        if (signedIn == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Sign in on the You tab to start a room.",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = c.ink.copy(alpha = 0.55f),
            )
        }

        Spacer(Modifier.height(20.dp))
    }

    if (timing) {
        SleepSheet(onDismiss = { timing = false })
    }
}

/** One square of the tray under the transport: an icon over a word. */
@Composable
private fun Tray(
    path: String,
    label: String,
    modifier: Modifier = Modifier,
    lit: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Neo.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .hardShadow(3.dp, c.ink, shape)
            .clip(shape)
            .background(if (lit) c.lime else c.card)
            .border(3.dp, c.ink, shape)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        com.museroom.app.ui.kit.NeoIcon(
            path,
            size = 19.dp,
            color = if (lit) c.onAccent else c.ink,
            weight = 2.6f,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.W900,
            fontSize = 9.sp,
            letterSpacing = 0.9.sp,
            maxLines = 1,
            color = if (lit) c.onAccent else c.ink,
        )
    }
}

/**
 * The sleep timer, as a sheet over the player.
 *
 * On this screen rather than only in settings because somebody setting a sleep
 * timer is already lying down with the music on, and making them find a
 * settings page at that moment is making them get up.
 */
@Composable
private fun SleepSheet(onDismiss: () -> Unit) {
    val c = Neo.colors
    val endsAt by SleepTimer.endsAt.collectAsStateWithLifecycle()
    val afterTrack by SleepTimer.afterThisTrack.collectAsStateWithLifecycle()

    Scrim(onDismiss) {
        com.museroom.app.ui.kit.NeoCard(radius = 22.dp, shadow = 8.dp, padding = 20.dp) {
        Text("SLEEP TIMER", style = com.museroom.app.ui.bangers(26).copy(color = c.ink))
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                afterTrack -> "The music stops when this song ends."
                endsAt > 0 -> "Stopping at " + timeOfDay(endsAt) + "."
                else -> "Off. The music keeps going until you stop it."
            },
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 13.sp,
            color = c.ink.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45, 60).forEach { minutes ->
                Box(Modifier.weight(1f)) {
                    NeoButton(
                        text = "${minutes}m",
                        tone = NeoTone.Paper,
                        small = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { SleepTimer.set(minutes); onDismiss() },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        NeoButton(
            text = "When this song ends",
            tone = NeoTone.Lime,
            modifier = Modifier.fillMaxWidth(),
            onClick = { SleepTimer.afterTrack(); onDismiss() },
        )
        Spacer(Modifier.height(10.dp))
        NeoButton(
            text = "Turn it off",
            tone = NeoTone.Pink,
            small = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = { SleepTimer.cancel(); onDismiss() },
        )
        }
    }
}

/** The wall clock, for saying when something will happen rather than in how long. */
private fun timeOfDay(atMs: Long): String {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = atMs }
    return "%d:%02d".format(
        calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.MINUTE),
    )
}

/**
 * The progress rail, which is also how you move.
 *
 * While a finger is down the bar follows the finger rather than the player, and
 * the seek happens on release. Seeking live would mean asking the player to
 * decode from twenty places on the way across.
 */
@Composable
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val c = Neo.colors
    var dragging by remember { mutableStateOf(false) }
    var held by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }

    val real = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown = if (dragging) held else real
    val eased by animateFloatAsState(
        shown,
        if (dragging) spring(stiffness = 4000f) else tween(420),
        label = "progress",
    )

    val shape = RoundedCornerShape(percent = 50)
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .hardShadow(3.dp, c.ink, shape)
                .clip(shape)
                .background(c.card)
                .border(3.dp, c.ink, shape)
                .pointerInput(durationMs) {
                    width = size.width.toFloat().coerceAtLeast(1f)
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            held = (offset.x / width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragging = false
                            if (durationMs > 0) onSeek((held * durationMs).toLong())
                        },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        held = (change.position.x / width).coerceIn(0f, 1f)
                    }
                }
                .pointerInput(durationMs) {
                    width = size.width.toFloat().coerceAtLeast(1f)
                    detectTapGestures { offset ->
                        if (durationMs > 0) {
                            onSeek(((offset.x / width).coerceIn(0f, 1f) * durationMs).toLong())
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth(eased)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(c.violet),
            )
            // The knob is only there while it is being used. A dot parked on a
            // bar reads as a control; a bar on its own reads as a reading.
            val knob by animateFloatAsState(if (dragging) 1f else 0f, tween(120), label = "knob")
            if (knob > 0f) {
                val density = LocalDensity.current
                Box(
                    Modifier
                        .offset(x = with(density) { (eased * width).toDp() } - 10.dp)
                        .size(20.dp)
                        .scale(knob)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(c.lime)
                        .border(3.dp, c.onAccent, RoundedCornerShape(percent = 50)),
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoText(clockOf(if (dragging) (held * durationMs).toLong() else positionMs), size = 11)
            MonoText(
                if (durationMs > 0) "-" + clockOf(durationMs - (shown * durationMs).toLong()) else "--:--",
                size = 11,
            )
        }
    }
}
