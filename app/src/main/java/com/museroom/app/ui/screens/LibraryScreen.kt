package com.museroom.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.museroom.app.player.Downloads
import com.museroom.app.player.Library
import com.museroom.app.player.Playback
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.NeoIcon
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.hardShadow

/**
 * The shelves, and which of them get a chip.
 *
 * Liked has no chip because it has a tile, and two ways in for one shelf is
 * two things to keep in step. It is still a shelf rather than a special case,
 * so everything below it works the same as everywhere else.
 */
private enum class Shelf(val label: String, val chip: Boolean = true) {
    Playlists("Playlists"),
    Songs("Songs"),
    Albums("Albums"),
    Artists("Artists"),
    Offline("Offline"),
    Liked("Liked songs", chip = false),
}

/**
 * Everything this phone knows, which is a shorter list than it sounds.
 *
 * Five shelves, all of them this phone's rather than an account's. A record
 * saved here is a note that somebody wanted it; the record itself still lives
 * on YouTube's side and is fetched when the page opens, because a copy taken
 * the day it was saved would quietly go out of date.
 *
 * Offline is the exception and the point of it: those songs are files, and they
 * are the only shelf that still works with the radio off.
 */
@Composable
fun LibraryScreen(
    onOpenPlayer: () -> Unit,
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
) {
    val c = Neo.colors
    var shelf by remember { mutableStateOf(Shelf.Playlists) }

    val liked by Library.liked.collectAsStateWithLifecycle()
    val songs by Library.songs.collectAsStateWithLifecycle()
    val offline by Library.offline.collectAsStateWithLifecycle()
    val albums by Library.albums.collectAsStateWithLifecycle()
    val artists by Library.artists.collectAsStateWithLifecycle()
    val offlineBytes by Library.offlineBytes.collectAsStateWithLifecycle()
    val playing by Playback.current.collectAsStateWithLifecycle()

    val shown = when (shelf) {
        Shelf.Offline -> offline
        Shelf.Liked -> liked
        else -> songs
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("LIBRARY", style = bangers(34).copy(color = c.ink))
        Spacer(Modifier.height(12.dp))

        // Five chips is more than fits, so the row scrolls rather than
        // wrapping onto a second line that would push the shelf down the page.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val lists by Library.playlists.collectAsStateWithLifecycle()
            Shelf.entries.filter { it.chip }.forEach { option ->
                val count = when (option) {
                    Shelf.Playlists -> lists.size
                    Shelf.Songs -> songs.size
                    Shelf.Albums -> albums.size
                    Shelf.Artists -> artists.size
                    Shelf.Offline -> offline.size
                    Shelf.Liked -> liked.size
                }
                Chip(
                    text = if (count > 0) "${option.label} $count" else option.label,
                    selected = shelf == option,
                    onClick = { shelf = option },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Liked is reached from a tile rather than a chip, so it is the one
        // shelf with nothing lit in the row above it. It says where it is.
        if (shelf == Shelf.Liked) {
            Row(
                Modifier.padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RoundIcon(
                    NeoIcons.Back, "Back to playlists",
                    onClick = { shelf = Shelf.Playlists },
                    diameter = 34.dp, icon = 14.dp, rest = 2.dp,
                )
                Text(
                    "LIKED SONGS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.W900,
                    letterSpacing = 1.5.sp,
                    fontSize = 10.sp,
                    color = c.ink.copy(alpha = 0.6f),
                )
            }
        }

        if (shelf == Shelf.Playlists) {
            PlaylistGrid(
                onOpen = onOpenPlaylist,
                pinned = {
                    // Liked and Offline are lists too, they are just ones
                    // nobody had to make. Putting them in the same grid rather
                    // than above it means the shelf has one shape.
                    item {
                        Standing(
                            title = "Liked songs",
                            detail = if (liked.size == 1) "1 track" else "${liked.size} tracks",
                            fill = c.pink,
                            icon = NeoIcons.Heart,
                            filled = true,
                        ) { shelf = Shelf.Liked }
                    }
                    item {
                        Standing(
                            title = "Offline",
                            detail = if (offline.isEmpty()) {
                                "Nothing kept yet"
                            } else {
                                "${offline.size} · " + size(offlineBytes)
                            },
                            fill = c.sky,
                            icon = NeoIcons.Download,
                        ) { shelf = Shelf.Offline }
                    }
                },
            )
            return@Column
        }

        if (shelf == Shelf.Albums) {
            if (albums.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                Note("The plus on an album puts it here. It stays until you take it off.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(albums, key = { it.browseId }) { album ->
                        Tile(
                            id = album.browseId,
                            cover = album.cover,
                            title = album.title,
                            detail = listOf(album.artist, album.detail)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                        ) { onOpenAlbum(album.browseId) }
                    }
                    item { Spacer(Modifier.height(120.dp)) }
                }
            }
            return@Column
        }

        if (shelf == Shelf.Artists) {
            if (artists.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                Note("Follow somebody from their page and they turn up here.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(artists, key = { it.browseId }) { artist ->
                        Tile(
                            id = artist.browseId,
                            cover = artist.cover,
                            title = artist.name,
                            detail = "Following",
                            round = true,
                        ) { onOpenArtist(artist.browseId) }
                    }
                    item { Spacer(Modifier.height(120.dp)) }
                }
            }
            return@Column
        }

        if (shown.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Note(
                when (shelf) {
                    Shelf.Offline ->
                        "Nothing kept on this phone yet. The download button on " +
                            "an album, a playlist or a song puts the file here, " +
                            "and it plays with the radio off."
                    Shelf.Liked ->
                        "Tap the heart on anything you want to keep. Liked songs live here."
                    else -> "Everything you play in Museroom is remembered here."
                },
            )
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (shelf) {
                    Shelf.Offline -> "KEPT ON THIS PHONE"
                    Shelf.Liked -> "NEWEST FIRST"
                    else -> "RECENTLY PLAYED"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.W900,
                letterSpacing = 1.5.sp,
                fontSize = 9.sp,
                color = c.ink.copy(alpha = 0.5f),
            )
            Text(
                "PLAY ALL",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.W900,
                letterSpacing = 1.4.sp,
                fontSize = 10.sp,
                color = c.ink.copy(alpha = 0.55f),
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    Playback.play(shown, 0, from = shelf.label)
                    onOpenPlayer()
                },
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(shown, key = { _, t -> t.id }) { i, track ->
                TrackRow(
                    track = track,
                    playing = playing?.id == track.id,
                    appearAfter = i,
                    onClick = { Playback.play(shown, i, from = shelf.label) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Keep(track)
                            Heart(track)
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
}

/**
 * A tile for a list nobody had to make.
 *
 * Liked and Offline are drawn as flat colour with an icon rather than as a
 * cover, because they have no cover and a grid of four sleeves and two grey
 * squares reads as two broken images.
 */
@Composable
private fun Standing(
    title: String,
    detail: String,
    fill: androidx.compose.ui.graphics.Color,
    icon: String,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Neo.colors
    Column(
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .hardShadow(5.dp, c.onAccent, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(fill)
                .border(3.dp, c.onAccent, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            NeoIcon(
                icon,
                size = 44.dp,
                color = c.onAccent,
                fill = if (filled) c.onAccent else null,
                weight = 3f,
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontSize = 13.sp, maxLines = 1)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = c.ink.copy(alpha = 0.6f),
        )
    }
}

/** A saved record or a followed artist, as a square or a circle. */
@Composable
private fun Tile(
    id: String,
    cover: String,
    title: String,
    detail: String,
    round: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Neo.colors
    Column(
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        TrackCover(
            id,
            cover.ifBlank { null },
            Modifier.fillMaxWidth().aspectRatio(1f),
            // A person is a circle and a record is a square, which is the one
            // piece of shorthand that saves the tile from needing a word.
            radius = if (round) 999.dp else 16.dp,
            shadow = 5.dp,
            stroke = 3.dp,
            dot = 11.dp,
        )
        Spacer(Modifier.height(9.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = c.ink.copy(alpha = 0.6f),
        )
    }
}

/**
 * The download button on a row.
 *
 * Three states rather than two, because a download in flight is a different
 * thing from one that has finished and somebody watching a progress ring wants
 * to know which they are looking at. Pressing it while it runs cancels; pressing
 * a finished one gives the space back.
 */
@Composable
fun Keep(track: com.museroom.app.player.LocalPlayer.Track, size: Int = 20) {
    val c = Neo.colors
    val have by Downloads.have.collectAsStateWithLifecycle()
    val running by Downloads.running.collectAsStateWithLifecycle()
    val job = running[track.id]
    val kept = track.id in have

    Box(
        Modifier
            .size((size + 14).dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                when {
                    job != null -> Downloads.cancel(track.id)
                    kept -> Downloads.remove(track.id)
                    else -> Downloads.start(track)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            job != null -> Ring(job.fraction)
            kept -> NeoIcon(NeoIcons.Check, size = size.dp, color = c.sky, weight = 3f)
            else -> NeoIcon(
                NeoIcons.Download,
                size = size.dp,
                color = c.ink.copy(alpha = 0.4f),
                weight = 2.4f,
            )
        }
    }
}

/** How far a download has got, drawn as an arc rather than a number. */
@Composable
private fun Ring(fraction: Float) {
    val c = Neo.colors
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 3.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawArc(
            color = c.ink.copy(alpha = 0.18f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
        )
        drawArc(
            color = c.sky,
            startAngle = -90f,
            // A sliver even at zero, so the ring reads as started rather than
            // as a control that did nothing.
            sweepAngle = (fraction.coerceIn(0f, 1f) * 360f).coerceAtLeast(12f),
            useCenter = false,
            style = stroke,
        )
    }
}

/** Bytes, as a person would say them. */
private fun size(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/** The kit's pill, as a control rather than a label. */
@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Neo.colors
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .hardShadow(3.dp, c.ink, shape)
            .clip(shape)
            .background(if (selected) c.ink else c.card)
            .border(2.5.dp, c.ink, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.W900,
            letterSpacing = 1.2.sp,
            fontSize = 11.sp,
            color = if (selected) c.paper else c.ink,
        )
    }
}

/**
 * The heart, which is the only way anything gets kept.
 *
 * Filled when it is on, outline when it is not. Not a colour change: a heart
 * that is merely a different shade of pink reads as decoration, and this is the
 * one control on a row that changes anything.
 */
@Composable
fun Heart(track: com.museroom.app.player.LocalPlayer.Track, size: Int = 22) {
    val c = Neo.colors
    val liked by Library.likedFlow(track.id).collectAsStateWithLifecycle(false)
    Box(
        Modifier
            .size((size + 14).dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { Library.toggleLike(track) },
        contentAlignment = Alignment.Center,
    ) {
        NeoIcon(
            NeoIcons.Heart,
            size = size.dp,
            color = if (liked) c.pink else c.ink.copy(alpha = 0.45f),
            fill = if (liked) c.pink else null,
            weight = 2.4f,
        )
    }
}
