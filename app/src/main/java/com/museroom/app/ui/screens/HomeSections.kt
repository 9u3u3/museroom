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
import com.museroom.app.player.LocalPlayer
import com.museroom.app.player.Playback
import com.museroom.app.player.Recent
import com.museroom.app.ui.Neo

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
fun HomeSections(onOpenPlayer: () -> Unit) {
    val recent by Recent.tracks.collectAsStateWithLifecycle()
    val seed = recent.firstOrNull()
    var picks by remember { mutableStateOf<List<LocalPlayer.Track>>(emptyList()) }

    // Suggestions follow whatever was played last, so the shelf is different
    // tomorrow without anybody maintaining it.
    LaunchedEffect(seed?.id) {
        picks = seed?.id?.let { Playback.radio(it) }.orEmpty()
    }

    if (recent.isEmpty()) return

    if (picks.isNotEmpty()) {
        Shelf(
            title = "Quick picks",
            action = "Play all",
            onAction = {
                Playback.play(picks, 0, from = "Quick picks")
                onOpenPlayer()
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
