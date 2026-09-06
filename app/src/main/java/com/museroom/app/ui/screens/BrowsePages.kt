package com.museroom.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.museroom.app.player.InnerTube
import com.museroom.app.player.Playback
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.NeoPill
import com.museroom.app.ui.kit.NeoTone

/**
 * A record, with everything on it.
 *
 * The cover sits beside the title rather than above it. An album page that
 * opens with a full-width square pushes the first track off the screen, and the
 * track list is what the page is for.
 */
@Composable
fun AlbumScreen(browseId: String, onBack: () -> Unit, onOpenArtist: (String) -> Unit) {
    val c = Neo.colors
    var album by remember(browseId) { mutableStateOf<InnerTube.Album?>(null) }
    var looked by remember(browseId) { mutableStateOf(false) }
    val playing by Playback.current.collectAsStateWithLifecycle()

    LaunchedEffect(browseId) {
        album = Playback.album(browseId)
        looked = true
    }

    PageFrame(title = "Album", onBack = onBack) {
        val found = album
        if (found == null) {
            Note(if (looked) "That album would not load." else "Looking…")
            return@PageFrame
        }
        val tracks = remember(found) { Playback.tracksOf(found.tracks) }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TrackCover(
                        found.browseId,
                        found.artworkUrl,
                        Modifier.size(132.dp),
                        radius = 18.dp,
                        shadow = 6.dp,
                        stroke = 3.dp,
                        dot = 11.dp,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
                        Text(
                            found.title,
                            style = MaterialTheme.typography.headlineLarge,
                            fontSize = 24.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            found.artist,
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 14.sp,
                            color = c.violet,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { if (found.artistId.isNotBlank()) onOpenArtist(found.artistId) },
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            listOf(found.kind, found.detail)
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.W700,
                            fontSize = 10.sp,
                            color = c.ink.copy(alpha = 0.55f),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                NeoButton(
                    text = "Play",
                    tone = NeoTone.Lime,
                    onClick = { Playback.play(tracks, 0, from = found.title) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, track ->
                TrackRow(
                    track = track,
                    playing = playing?.id == track.id,
                    appearAfter = i,
                    onClick = { Playback.play(tracks, i, from = found.title) },
                    trailing = { Heart(track, size = 20) },
                    subtitle = if (track.durationMs > 0) clockOf(track.durationMs) else " ",
                )
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
}

/**
 * Somebody's whole output, which is two things: what people play, and what they
 * released. Both are read for what they contain rather than by their headings,
 * because the headings are translated and move about.
 */
@Composable
fun ArtistScreen(browseId: String, onBack: () -> Unit, onOpenAlbum: (String) -> Unit) {
    val c = Neo.colors
    var artist by remember(browseId) { mutableStateOf<InnerTube.Artist?>(null) }
    var looked by remember(browseId) { mutableStateOf(false) }
    val playing by Playback.current.collectAsStateWithLifecycle()

    LaunchedEffect(browseId) {
        artist = Playback.artist(browseId)
        looked = true
    }

    PageFrame(title = "Artist", onBack = onBack) {
        val found = artist
        if (found == null) {
            Note(if (looked) "That artist would not load." else "Looking…")
            return@PageFrame
        }
        val songs = remember(found) { Playback.tracksOf(found.songs) }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    found.name.uppercase(),
                    style = bangers(38).copy(color = c.ink),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (found.listeners.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    NeoPill(found.listeners)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeoButton(
                        text = "Play",
                        tone = NeoTone.Lime,
                        onClick = { if (songs.isNotEmpty()) Playback.play(songs, 0, from = found.name) },
                        modifier = Modifier.weight(1f),
                    )
                    NeoButton(
                        text = "Shuffle",
                        tone = NeoTone.Paper,
                        onClick = {
                            if (songs.isNotEmpty()) {
                                Playback.play(songs.shuffled(), 0, from = found.name)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (songs.isNotEmpty()) {
                item { Heading("Top songs") }
                itemsIndexed(songs, key = { _, t -> t.id }) { i, track ->
                    TrackRow(
                        track = track,
                        playing = playing?.id == track.id,
                        appearAfter = i,
                        onClick = { Playback.play(songs, i, from = found.name) },
                        trailing = { Heart(track, size = 20) },
                    )
                }
            }

            if (found.albums.isNotEmpty()) {
                item { Heading("Albums") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(found.albums, key = { it.browseId }) { card ->
                            Column(
                                Modifier
                                    .width(118.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onOpenAlbum(card.browseId) },
                            ) {
                                TrackCover(
                                    card.browseId,
                                    card.artworkUrl,
                                    Modifier.fillMaxWidth().aspectRatio(1f),
                                    radius = 14.dp,
                                    shadow = 4.dp,
                                    stroke = 3.dp,
                                    dot = 11.dp,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    card.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    card.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp,
                                    color = c.ink.copy(alpha = 0.6f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
}

/** Back, a name for where you are, and the room to be somewhere. */
@Composable
private fun PageFrame(title: String, onBack: () -> Unit, body: @Composable () -> Unit) {
    val c = Neo.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundIcon(NeoIcons.Back, "Back", onClick = onBack, diameter = 42.dp)
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.W900,
                letterSpacing = 1.6.sp,
                fontSize = 10.sp,
                color = c.ink.copy(alpha = 0.5f),
            )
        }
        Box(Modifier.fillMaxSize()) { body() }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = Neo.colors.ink,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}
