package com.museroom.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.player.Library
import com.museroom.app.player.LocalPlayer
import com.museroom.app.player.Playback
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.NeoChip
import com.museroom.app.ui.kit.NeoIcon
import com.museroom.app.ui.kit.NeoChip
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.Shelf

/**
 * Quick picks, held still.
 *
 * They were seeded from whatever was played last, which meant the shelf changed
 * under somebody every time they pressed a song — including when they pressed a
 * song *on the shelf*, so it rearranged itself as it was being used. A shelf
 * that will not sit still is not a shelf.
 *
 * They are decided once and then left alone, and change when the app is opened
 * again or when somebody asks for a new set. This lives outside the composable
 * so that moving between tabs does not count as asking.
 */
private object Picks {
    var seed: String? = null
    var tracks: List<LocalPlayer.Track> = emptyList()
    var looking = false

    /** Forget them, so the next look decides again. */
    fun again() {
        seed = null
        tracks = emptyList()
    }
}

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
fun HomeShelves(onOpenPlayer: () -> Unit) {
    val recent by Library.recent.collectAsStateWithLifecycle()
    val playing by Playback.current.collectAsStateWithLifecycle()
    var picks by remember { mutableStateOf(Picks.tracks) }
    var refreshing by remember { mutableStateOf(false) }

    // Decided once. The key is deliberately not the last track played: that is
    // exactly the thing that must not move the shelf.
    LaunchedEffect(refreshing, recent.isEmpty()) {
        if (Picks.tracks.isNotEmpty() && !refreshing) {
            picks = Picks.tracks
            return@LaunchedEffect
        }
        val chosen = Picks.seed ?: recent.firstOrNull()?.id ?: return@LaunchedEffect
        if (Picks.looking) return@LaunchedEffect
        Picks.looking = true
        val found = Playback.radio(chosen)
        Picks.looking = false
        if (found.isNotEmpty()) {
            Picks.seed = chosen
            Picks.tracks = found
            picks = found
        }
        refreshing = false
    }

    if (recent.isEmpty()) return

    if (picks.isNotEmpty()) {
        // Two things to do to the shelf, and they are different things: play
        // the whole of it, or be given a different one. The design shows only
        // the first, so the second is a small button rather than a second link.
        Shelf(
            title = "Quick picks",
            action = "Play all",
            onAction = { Playback.play(picks, 0, from = "Quick picks") },
        )
        picks.take(4).forEachIndexed { i, track ->
            TrackRow(
                track = track,
                playing = track.id == playing?.id,
                appearAfter = i,
                onClick = { Playback.play(picks, i, from = "Quick picks") },
                trailing = { RowMenu(track) },
            )
        }
        Spacer(Modifier.height(8.dp))
        NeoChip(
            text = if (refreshing) "Finding" else "Another set",
            selected = false,
            onClick = {
                Picks.again()
                refreshing = true
            },
        )
    }

    Shelf(title = "Listen again", action = "More", onAction = onOpenPlayer)
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

/**
 * The three dots at the end of a row.
 *
 * Every list in the design ends its rows with them, and they all open the same
 * sheet: this song, and the four things there are to do with one.
 */
@Composable
fun RowMenu(track: LocalPlayer.Track) {
    var open by remember { mutableStateOf(false) }
    NeoIcon(
        NeoIcons.Dots,
        size = 20.dp,
        color = Neo.colors.ink.copy(alpha = 0.45f),
        weight = 3.4f,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { open = true },
    )
    if (open) TrackSheet(track, onDismiss = { open = false })
}
