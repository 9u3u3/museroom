package com.museroom.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.museroom.app.player.Library
import com.museroom.app.player.Playback
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.NeoAccentCard
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoIcon
import com.museroom.app.ui.kit.Kicker
import com.museroom.app.ui.kit.NeoChip
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.NeoTone

/**
 * What is coming, as a place rather than a drawer.
 *
 * The queue lived folded into the player, which was fine for a glance and wrong
 * for anything else: taking three songs out of a list you can only see four of
 * at a time is a chore. It has the screen now, and with it the two things a
 * queue is actually for — deciding what order the rest goes in, and deciding
 * whether there is a rest at all.
 */
@Composable
fun QueueScreen(onBack: () -> Unit) {
    val c = Neo.colors
    val queue by Playback.queue.collectAsStateWithLifecycle()
    val index by Playback.index.collectAsStateWithLifecycle()
    val from by Playback.from.collectAsStateWithLifecycle()
    val playing by Playback.current.collectAsStateWithLifecycle()
    val shuffle by Playback.shuffle.collectAsStateWithLifecycle()
    val repeat by Playback.repeat.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    val order = rememberReorder { was, now -> Playback.move(was, now) }

    if (naming) {
        NameDialog(
            title = "Save queue as",
            onDismiss = { naming = false },
            onDone = { name ->
                Library.newPlaylistFrom(name, queue)
                naming = false
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        SheetBar(
            kicker = "Queue",
            value = listOfNotNull(
                from.takeIf { it.isNotBlank() },
                if (queue.size == 1) "1 track" else "${queue.size} tracks",
            ).joinToString(" · "),
            onClose = onBack,
            action = {
                if (queue.isNotEmpty()) {
                    RoundIcon(
                        NeoIcons.Close, "Clear the queue",
                        onClick = { Playback.stop() },
                        diameter = 42.dp, icon = 18.dp,
                    )
                }
            },
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {

        if (queue.isEmpty()) {
            Note("Nothing queued. Play an album or a search result and it lands here.")
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeoChip(
                text = if (shuffle) "Shuffle on" else "Shuffle",
                selected = shuffle,
                onClick = { Playback.setShuffle(!shuffle) },
            )
            NeoChip(
                text = when (repeat) {
                    Playback.Repeat.Off -> "Repeat"
                    Playback.Repeat.All -> "Repeat all"
                    Playback.Repeat.One -> "Repeat one"
                },
                selected = repeat != Playback.Repeat.Off,
                onClick = { Playback.cycleRepeat() },
            )
            NeoChip("Save as playlist", selected = false, onClick = { naming = true })
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            val now = queue.getOrNull(index)
            if (now != null) {
                item {
                    // The playing row is a card rather than a highlighted line,
                    // because it is a different kind of thing from the rows
                    // around it: they are what will happen, it is what is.
                    NeoAccentCard(fill = c.lime, radius = 16.dp, shadow = 4.dp, padding = 12.dp) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TrackCover(now.id, now.artworkUrl, Modifier.size(46.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    now.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    now.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Bars(color = c.onAccent)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Kicker("Up next")
                }
            }

            val upcoming = queue.drop(index + 1)
            itemsIndexed(upcoming, key = { _, t -> t.id + "-q" }) { i, track ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .reorderable(order, track.id),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The grip is its own target and nothing else on the row
                    // starts a drag, because the row is also a thing you tap to
                    // play and a thing you scroll past.
                    Box(
                        Modifier
                            .padding(end = 4.dp)
                            .size(30.dp)
                            .dragHandle(
                                state = order,
                                id = track.id,
                                // Looked up rather than captured: a row that has
                                // just been dragged past its neighbour is at a
                                // different index from the one this row was
                                // composed at, and moving it from the old one
                                // would swap the wrong pair.
                                indexOf = { queue.indexOfFirst { it.id == track.id } },
                                size = { queue.size },
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
                                val at = queue.indexOfFirst { it.id == track.id }
                                if (at >= 0) Playback.play(queue, at, from)
                            },
                            trailing = {
                                RoundIcon(
                                    NeoIcons.Close, "Take out of the queue",
                                    onClick = {
                                        val at = queue.indexOfFirst { it.id == track.id }
                                        if (at >= 0) Playback.removeAt(at)
                                    },
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
}
