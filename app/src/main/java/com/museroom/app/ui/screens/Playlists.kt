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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.data.PlaylistSummary
import com.museroom.app.player.Downloads
import com.museroom.app.player.Library
import com.museroom.app.player.LocalPlayer
import com.museroom.app.player.Playback
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoIcon
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.ui.kit.hardShadow

/**
 * The grid of lists on the library's Playlists shelf.
 *
 * [pinned] is for the lists nobody made — liked songs, and what is kept
 * offline. They are handed in rather than built here because they belong to the
 * library's idea of its shelves, not to playlists.
 */
@Composable
fun PlaylistGrid(
    onOpen: (Long) -> Unit,
    pinned: (androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit)? = null,
) {
    val c = Neo.colors
    val lists by Library.playlists.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }

    if (naming) {
        NameDialog(
            title = "New playlist",
            onDismiss = { naming = false },
            onDone = { Library.newPlaylist(it); naming = false },
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Their own cells rather than one shared item, so the standing lists
        // sit in the grid's columns instead of stacked inside one square.
        pinned?.invoke(this)
        item {
            // The way to make one sits with the ones you made, because that is
            // where somebody looks when they want another.
            Column(
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { naming = true },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .hardShadow(5.dp, c.onAccent, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.lime)
                        .padding(3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NeoIcon(NeoIcons.Plus, size = 44.dp, color = c.onAccent, weight = 3f)
                }
                Spacer(Modifier.height(9.dp))
                Text("New playlist", style = MaterialTheme.typography.titleMedium, fontSize = 13.sp)
                Text(
                    "Keep songs together",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = c.ink.copy(alpha = 0.6f),
                )
            }
        }
        items(lists, key = { it.id }) { list ->
            PlaylistTile(list) { onOpen(list.id) }
        }
    }
}

@Composable
private fun PlaylistTile(list: PlaylistSummary, onOpen: () -> Unit) {
    val c = Neo.colors
    Column(
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onOpen,
        ),
    ) {
        TrackCover(
            "playlist-${list.id}",
            list.cover.ifBlank { null },
            Modifier.fillMaxWidth().aspectRatio(1f),
            radius = 16.dp,
            shadow = 5.dp,
            stroke = 3.dp,
            dot = 11.dp,
        )
        Spacer(Modifier.height(9.dp))
        Text(
            list.name,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (list.songs == 1) "1 track" else "${list.songs} tracks",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 10.sp,
            color = c.ink.copy(alpha = 0.6f),
        )
    }
}

/** One list, with a way to play it and a way to take things out. */
@Composable
fun PlaylistScreen(id: Long, onBack: () -> Unit) {
    val c = Neo.colors
    val list by Library.playlist(id).collectAsStateWithLifecycle(null)
    val songs by Library.songsIn(id).collectAsStateWithLifecycle(emptyList())
    val playing by Playback.current.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }

    // The new order is written whole to the database on every step of a drag.
    // A playlist is tens of rows rather than thousands, and the alternative is
    // holding an order in memory that the database disagrees with.
    val order = rememberReorder { was, now ->
        val ids = songs.map { it.id }.toMutableList()
        if (was in ids.indices && now in ids.indices) {
            ids.add(now, ids.removeAt(was))
            Library.reorderPlaylist(id, ids)
        }
    }

    if (renaming) {
        NameDialog(
            title = "Rename",
            initial = list?.name.orEmpty(),
            onDismiss = { renaming = false },
            onDone = { Library.renamePlaylist(id, it); renaming = false },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundIcon(NeoIcons.Back, "Back", onClick = onBack, diameter = 42.dp)
            Text(
                "PLAYLIST",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.W900,
                letterSpacing = 1.6.sp,
                fontSize = 10.sp,
                color = c.ink.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
            )
            RoundIcon(
                NeoIcons.Trash, "Delete playlist",
                onClick = { Library.deletePlaylist(id); onBack() },
                diameter = 42.dp, icon = 18.dp,
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                // The cover beside the name rather than above it, the same way
                // an album page is laid out, because a list that somebody made
                // is the same kind of object as one a label put out.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TrackCover(
                        "playlist-$id",
                        songs.firstOrNull { it.cover.isNotBlank() }?.cover,
                        Modifier.size(118.dp),
                        radius = 18.dp,
                        shadow = 6.dp,
                        stroke = 3.dp,
                        dot = 11.dp,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
                        Text(
                            list?.name.orEmpty(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontSize = 22.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { renaming = true },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOf(
                                "Yours",
                                if (songs.size == 1) "1 track" else "${songs.size} tracks",
                                length(songs.sumOf { it.durationMs }),
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.W700,
                            fontSize = 10.sp,
                            color = c.ink.copy(alpha = 0.55f),
                        )
                    }
                }
                if (songs.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NeoButton(
                            text = "Play",
                            tone = NeoTone.Lime,
                            onClick = { Playback.play(songs, 0, from = list?.name.orEmpty()) },
                            modifier = Modifier.weight(1f),
                        )
                        RoundIcon(
                            NeoIcons.Shuffle, "Shuffle this list",
                            onClick = {
                                Playback.play(songs.shuffled(), 0, from = list?.name.orEmpty())
                            },
                            diameter = 48.dp, icon = 19.dp, rest = 4.dp,
                        )
                        RoundIcon(
                            NeoIcons.Download, "Keep this list on the phone",
                            onClick = { Downloads.startAll(songs) },
                            diameter = 48.dp, icon = 19.dp, rest = 4.dp,
                        )
                        RoundIcon(
                            NeoIcons.Chevron, "Rename this list",
                            onClick = { renaming = true },
                            diameter = 48.dp, icon = 19.dp, rest = 4.dp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (songs.isEmpty()) {
                item {
                    Note("Nothing in here yet. Play something and press Save on the player.")
                }
            }

            itemsIndexed(songs, key = { _, t -> t.id }) { i, track ->
                Row(
                    Modifier.fillMaxWidth().reorderable(order, track.id),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .padding(end = 4.dp)
                            .size(30.dp)
                            .dragHandle(
                                state = order,
                                id = track.id,
                                indexOf = { songs.indexOfFirst { it.id == track.id } },
                                size = { songs.size },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        NeoIcon(
                            NeoIcons.Grip,
                            size = 18.dp,
                            color = c.ink.copy(alpha = 0.4f),
                            weight = 2.6f,
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        TrackRow(
                            track = track,
                            playing = playing?.id == track.id,
                            appearAfter = i,
                            onClick = {
                                val at = songs.indexOfFirst { it.id == track.id }
                                if (at >= 0) Playback.play(songs, at, from = list?.name.orEmpty())
                            },
                            trailing = {
                                RoundIcon(
                                    NeoIcons.Close, "Remove",
                                    onClick = { Library.removeFromPlaylist(id, track.id) },
                                    diameter = 34.dp, icon = 14.dp, rest = 2.dp,
                                )
                            },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
}

/** How long a list runs, as a person would say it. */
private fun length(totalMs: Long): String {
    val minutes = totalMs / 60_000
    if (minutes <= 0) return ""
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}

/**
 * Where a song goes when somebody presses Save.
 *
 * Making a list is on the same sheet as choosing one, because the moment you
 * want a song kept is usually the moment you realise you need somewhere to keep
 * it, and sending somebody to another screen to make one loses the song.
 */
@Composable
fun SaveToPlaylist(track: LocalPlayer.Track, onDismiss: () -> Unit) {
    val c = Neo.colors
    val lists by Library.playlists.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }

    if (naming) {
        NameDialog(
            title = "New playlist",
            onDismiss = { naming = false },
            onDone = {
                Library.newPlaylist(it, andAdd = track)
                naming = false
                onDismiss()
            },
        )
        return
    }

    Scrim(onDismiss) {
        NeoCard(radius = 22.dp, shadow = 8.dp, padding = 20.dp) {
            Text("Save to", style = MaterialTheme.typography.headlineSmall, color = c.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            if (lists.isNotEmpty()) {
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(lists, key = { it.id }) { list ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    Library.addToPlaylist(list.id, track)
                                    onDismiss()
                                }
                                .padding(vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(list.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${list.songs}",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.ink.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            NeoButton(
                text = "New playlist",
                tone = NeoTone.Lime,
                onClick = { naming = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A name, typed. Used for making a list and for changing its mind. */
@Composable
fun NameDialog(
    title: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    // A dialog whose only job is to take a name should be ready to take one.
    // Opening with the field cold means a tap before a word, every time.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scrim(onDismiss) {
        NeoCard(radius = 22.dp, shadow = 8.dp, padding = 20.dp) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Neo.colors.ink)
            Spacer(Modifier.height(12.dp))
            Field(
                value = text,
                onChange = { text = it },
                label = "Name",
                modifier = Modifier.focusRequester(focus),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeoButton(
                    text = "Save",
                    tone = NeoTone.Lime,
                    onClick = { onDone(text) },
                    modifier = Modifier.weight(1f),
                )
                NeoButton(
                    text = "Cancel",
                    tone = NeoTone.Paper,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Everything behind goes dark and stops listening. */
@Composable
fun Scrim(onDismiss: () -> Unit, body: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Neo.colors.ink.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .padding(horizontal = 26.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        ) { body() }
    }
}
