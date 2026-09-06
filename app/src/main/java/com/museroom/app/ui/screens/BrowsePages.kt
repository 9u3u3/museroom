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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.museroom.app.player.Downloads
import com.museroom.app.player.InnerTube
import com.museroom.app.player.Library
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
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NeoButton(
                        text = "Play",
                        tone = NeoTone.Lime,
                        onClick = { Playback.play(tracks, 0, from = found.title) },
                        modifier = Modifier.weight(1f),
                    )
                    RoundIcon(
                        NeoIcons.Shuffle, "Shuffle this album",
                        onClick = {
                            if (tracks.isNotEmpty()) {
                                Playback.play(tracks.shuffled(), 0, from = found.title)
                            }
                        },
                        diameter = 48.dp, icon = 19.dp, rest = 4.dp,
                    )
                    KeepAll(tracks, "album")
                    Save(found)
                }
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, track ->
                TrackRow(
                    track = track,
                    playing = playing?.id == track.id,
                    appearAfter = i,
                    onClick = { Playback.play(tracks, i, from = found.title) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Keep(track, size = 18)
                            Heart(track, size = 20)
                        }
                    },
                    subtitle = if (track.durationMs > 0) clockOf(track.durationMs) else " ",
                    number = i + 1,
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
    val scope = rememberCoroutineScope()

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
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (found.listeners.isNotBlank()) NeoPill(found.listeners)
                    Follow(found)
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
                    // A radio is a different promise from a shuffle: shuffle is
                    // these songs in another order, radio is songs that are not
                    // on this page at all.
                    RoundIcon(
                        NeoIcons.Radio, "Start a radio from this artist",
                        onClick = {
                            val seed = songs.firstOrNull() ?: return@RoundIcon
                            scope.launch {
                                val more = Playback.radio(seed.id)
                                Playback.play(
                                    listOf(seed) + more,
                                    0,
                                    from = found.name + " radio",
                                )
                            }
                        },
                        diameter = 48.dp, icon = 19.dp, rest = 4.dp,
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
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Keep(track, size = 18)
                                Heart(track, size = 20)
                            }
                        },
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

/**
 * The plus that puts a record on the shelf, or takes it off.
 *
 * It reads the database rather than remembering what it drew, so the same album
 * open in two places cannot disagree about whether it is saved.
 */
@Composable
private fun Save(album: InnerTube.Album) {
    val c = Neo.colors
    val saved by Library.savedFlow(album.browseId).collectAsStateWithLifecycle(false)
    RoundIcon(
        if (saved) NeoIcons.Check else NeoIcons.Plus,
        if (saved) "Take off your shelf" else "Add to your library",
        onClick = { Library.toggleSaved(album) },
        diameter = 48.dp, icon = 19.dp, rest = 4.dp,
        fill = if (saved) c.lime else c.card,
        stroke = if (saved) c.onAccent else c.ink,
        content = if (saved) c.onAccent else c.ink,
    )
}

/** Following somebody, which is this phone's list and says so on the shelf. */
@Composable
private fun Follow(artist: InnerTube.Artist) {
    val followed by Library.followedFlow(artist.browseId).collectAsStateWithLifecycle(false)
    NeoButton(
        text = if (followed) "Following" else "Follow",
        tone = if (followed) NeoTone.Lime else NeoTone.Paper,
        small = true,
        leading = {
            com.museroom.app.ui.kit.NeoIcon(
                NeoIcons.Bell,
                size = 14.dp,
                color = if (followed) Neo.colors.onAccent else Neo.colors.ink,
                weight = 2.4f,
            )
        },
        onClick = { Library.toggleFollowed(artist) },
    )
}

/**
 * Downloading a whole page at once.
 *
 * Lit when every track on it is already here, which is the only reading of "is
 * this album downloaded" that does not lie: one missing track means the album
 * does not work on a train.
 */
@Composable
private fun KeepAll(tracks: List<com.museroom.app.player.LocalPlayer.Track>, what: String) {
    val c = Neo.colors
    val have by Downloads.have.collectAsStateWithLifecycle()
    val running by Downloads.running.collectAsStateWithLifecycle()
    val all = tracks.isNotEmpty() && tracks.all { it.id in have }
    val busy = tracks.any { it.id in running }

    RoundIcon(
        if (all) NeoIcons.Check else NeoIcons.Download,
        when {
            all -> "Remove this $what from the phone"
            busy -> "Downloading"
            else -> "Keep this $what on the phone"
        },
        onClick = {
            if (all) tracks.forEach { Downloads.remove(it.id) } else Downloads.startAll(tracks)
        },
        diameter = 48.dp, icon = 19.dp, rest = 4.dp,
        fill = if (all) c.sky else c.card,
        stroke = if (all) c.onAccent else c.ink,
        content = if (all) c.onAccent else c.ink,
    )
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

/**
 * Somebody else's playlist, which is a page rather than a list on this phone.
 *
 * The same shape as an album, and the differences are the ones that matter:
 * every row carries its own artist, and there is nothing to rename, reorder or
 * delete because none of it is ours. Saving it means saving the songs.
 */
@Composable
fun ListingScreen(browseId: String, onBack: () -> Unit, onOpenArtist: (String) -> Unit) {
    val c = Neo.colors
    var listing by remember(browseId) { mutableStateOf<InnerTube.Album?>(null) }
    var looked by remember(browseId) { mutableStateOf(false) }
    val playing by Playback.current.collectAsStateWithLifecycle()

    LaunchedEffect(browseId) {
        listing = Playback.playlistPage(browseId)
        looked = true
    }

    PageFrame(title = "Playlist", onBack = onBack) {
        val found = listing
        if (found == null) {
            Note(if (looked) "That playlist would not load." else "Looking…")
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
                            listOf(found.artist, found.kind, found.detail)
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.W700,
                            fontSize = 10.sp,
                            color = c.ink.copy(alpha = 0.55f),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NeoButton(
                        text = "Play",
                        tone = NeoTone.Lime,
                        onClick = { Playback.play(tracks, 0, from = found.title) },
                        modifier = Modifier.weight(1f),
                    )
                    RoundIcon(
                        NeoIcons.Shuffle, "Shuffle this playlist",
                        onClick = {
                            if (tracks.isNotEmpty()) {
                                Playback.play(tracks.shuffled(), 0, from = found.title)
                            }
                        },
                        diameter = 48.dp, icon = 19.dp, rest = 4.dp,
                    )
                    KeepAll(tracks, "playlist")
                }
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, track ->
                TrackRow(
                    track = track,
                    playing = playing?.id == track.id,
                    appearAfter = i,
                    onClick = { Playback.play(tracks, i, from = found.title) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Keep(track, size = 18)
                            Heart(track, size = 20)
                        }
                    },
                    onOpenArtist = onOpenArtist,
                )
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
}
