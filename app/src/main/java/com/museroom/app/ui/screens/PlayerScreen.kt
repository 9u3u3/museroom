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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.player.LocalPlayer
import com.museroom.app.player.Playback
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.NeoPill
import com.museroom.app.ui.kit.hardShadow
import kotlinx.coroutines.delay

/**
 * The track, full screen.
 *
 * Only the controls that do something are here. Lyrics, downloads and a sleep
 * timer belong on this surface and are not built, and a button that silently
 * does nothing is worse than a button that is missing — the same rule the
 * room's notification has followed since it was written.
 */
@Composable
fun PlayerScreen(onClose: () -> Unit, onOpenArtist: (String) -> Unit = {}) {
    val c = Neo.colors
    val track by Playback.current.collectAsStateWithLifecycle()
    val snapshot by Playback.snapshot.collectAsStateWithLifecycle()
    val from by Playback.from.collectAsStateWithLifecycle()
    val queue by Playback.queue.collectAsStateWithLifecycle()
    val index by Playback.index.collectAsStateWithLifecycle()

    var showQueue by remember { mutableStateOf(false) }

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
                if (showQueue) "Hide queue" else "Show queue",
                onClick = { showQueue = !showQueue },
                diameter = 42.dp,
                fill = if (showQueue) c.lime else c.card,
                stroke = if (showQueue) c.onAccent else c.ink,
                content = if (showQueue) c.onAccent else c.ink,
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
            RoundIcon(
                NeoIcons.Previous, "Previous",
                onClick = { Playback.previous() },
                diameter = 54.dp, icon = 22.dp, rest = 4.dp,
            )
            Spacer(Modifier.size(18.dp))
            RoundIcon(
                if (snapshot.playing) NeoIcons.Pause else NeoIcons.Play,
                if (snapshot.playing) "Pause" else "Play",
                onClick = { Playback.toggle() },
                diameter = 78.dp, icon = 30.dp, rest = 5.dp, weight = 3.2f,
                fill = c.lime, stroke = c.onAccent, content = c.onAccent,
            )
            Spacer(Modifier.size(18.dp))
            RoundIcon(
                NeoIcons.Next, "Next",
                onClick = { Playback.next() },
                diameter = 54.dp, icon = 22.dp, rest = 4.dp,
            )
        }

        AnimatedVisibility(
            visible = showQueue,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
        ) {
            Column {
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "UP NEXT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.W900,
                        letterSpacing = 1.6.sp,
                        fontSize = 9.sp,
                        color = c.ink.copy(alpha = 0.5f),
                    )
                    NeoPill("${(queue.size - index - 1).coerceAtLeast(0)} left")
                }
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    itemsIndexed(
                        queue.drop(index + 1),
                        key = { _, t -> t.id + "-next" },
                    ) { i, upcoming ->
                        TrackRow(
                            track = upcoming,
                            playing = false,
                            appearAfter = i,
                            onClick = { Playback.play(queue, index + 1 + i, from) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
    }
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
