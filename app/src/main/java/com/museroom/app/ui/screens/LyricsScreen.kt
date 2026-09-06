package com.museroom.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.player.LocalPlayer
import com.museroom.app.player.Lyrics
import com.museroom.app.player.Playback
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.NeoPill
import kotlinx.coroutines.delay

/**
 * The words, arriving as they are sung.
 *
 * The line being sung is the only thing at full weight. Everything above it
 * fades because it is spent and everything below is dimmed because it has not
 * happened, so the eye never has to hunt for where the song has got to. That is
 * the whole design; the rest is keeping up.
 */
@Composable
fun LyricsScreen(onClose: () -> Unit) {
    val c = Neo.colors
    val track by Playback.current.collectAsStateWithLifecycle()
    val snapshot by Playback.snapshot.collectAsStateWithLifecycle()

    var words by remember(track?.id) { mutableStateOf<Lyrics.Words?>(null) }
    var looked by remember(track?.id) { mutableStateOf(false) }

    LaunchedEffect(track?.id) {
        val song = track
        words = if (song == null) null else Playback.lyrics(song)
        looked = true
    }

    // The position is asked for while this screen is up and not otherwise, the
    // same bargain the player makes: the engine holds no timer of its own.
    LaunchedEffect(snapshot.playing, words?.timed) {
        while (snapshot.playing && words?.timed == true) {
            delay(200)
            LocalPlayer.tick()
        }
    }

    val song = track
    val found = words
    val lines = found?.lines.orEmpty()
    val current = if (found?.timed == true) Lyrics.lineAt(lines, snapshot.positionMs) else -1

    val scroll = rememberLazyListState()
    LaunchedEffect(current) {
        // Two lines above the sung one, so there is somewhere to have come from.
        if (current >= 0) scroll.animateScrollToItem((current - 2).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundIcon(NeoIcons.Chevron, "Close", onClick = onClose, diameter = 42.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    "LYRICS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.W900,
                    letterSpacing = 1.6.sp,
                    fontSize = 9.sp,
                    color = c.ink.copy(alpha = 0.5f),
                )
                Text(
                    listOfNotNull(
                        song?.title?.takeIf { it.isNotBlank() },
                        song?.artist?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                !looked -> Note("Looking for the words…")
                found == null -> Note("No words for this one.")
                else -> LazyColumn(state = scroll, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(lines) { i, line ->
                        val sung = found.timed && i == current
                        val spent = found.timed && i < current
                        val weight by animateFloatAsState(
                            if (sung) 1f else 0f,
                            tween(240),
                            label = "line",
                        )
                        val fade = when {
                            !found.timed -> 0.85f
                            sung -> 1f
                            spent -> 0.22f
                            else -> 0.38f
                        }
                        Text(
                            line.text,
                            fontSize = (23 + 8 * weight).sp,
                            color = c.ink.copy(alpha = fade),
                            // The sung line is the page's full ink. Setting it
                            // in an accent read as low contrast on the dark
                            // skin, and the accent does more work behind the
                            // letters than in them.
                            style = MaterialTheme.typography.headlineLarge.copy(
                                shadow = if (!sung) null else androidx.compose.ui.graphics.Shadow(
                                    color = c.lime,
                                    offset = androidx.compose.ui.geometry.Offset(3f, 3f),
                                    blurRadius = 0f,
                                ),
                            ),
                            modifier = Modifier.padding(vertical = 10.dp),
                            softWrap = true,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(0.dp))
                    }
                    item { Spacer(Modifier.height(240.dp)) }
                }
            }
        }

        if (found != null) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NeoPill(found.source)
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(c.ink.copy(alpha = 0.16f)),
                ) {
                    val duration = snapshot.durationMs.takeIf { it > 0 } ?: song?.durationMs ?: 0
                    val fraction =
                        if (duration > 0) (snapshot.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                        else 0f
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(percent = 50))
                            .background(c.violet),
                    )
                }
                MonoText(clockOf(snapshot.positionMs), size = 11)
            }
            if (!found.timed) {
                Text(
                    "These are not timed, so they will not follow along.",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = c.ink.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
    }
}
