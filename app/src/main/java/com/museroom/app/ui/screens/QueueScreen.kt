package com.museroom.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.NeoTone

/**
 * What is coming, as a place rather than a drawer.
 *
 * The queue lived folded into the player, which was fine for a glance and
 * wrong for anything else: taking three songs out of a list you can only see
 * four of at a time is a chore. It has the screen now.
 *
 * There is no repeat button. Repeat is not built, and the room's rule about
 * buttons that silently do nothing applies here as much as it does in a
 * notification.
 */
@Composable
fun QueueScreen(onBack: () -> Unit) {
    val c = Neo.colors
    val queue by Playback.queue.collectAsStateWithLifecycle()
    val index by Playback.index.collectAsStateWithLifecycle()
    val from by Playback.from.collectAsStateWithLifecycle()
    val playing by Playback.current.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }

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

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundIcon(NeoIcons.Chevron, "Close", onClick = onBack, diameter = 42.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    "QUEUE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.W900,
                    letterSpacing = 1.6.sp,
                    fontSize = 9.sp,
                    color = c.ink.copy(alpha = 0.5f),
                )
                Text(
                    listOfNotNull(
                        from.takeIf { it.isNotBlank() },
                        if (queue.size == 1) "1 track" else "${queue.size} tracks",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (queue.isEmpty()) {
            Note("Nothing queued. Play an album or a search result and it lands here.")
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeoButton(
                text = "Shuffle rest",
                tone = NeoTone.Paper,
                small = true,
                onClick = { Playback.shuffleRest() },
            )
            NeoButton(
                text = "Save as playlist",
                tone = NeoTone.Paper,
                small = true,
                onClick = { naming = true },
            )
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
                    Text(
                        "UP NEXT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.W900,
                        letterSpacing = 1.6.sp,
                        fontSize = 9.sp,
                        color = c.ink.copy(alpha = 0.5f),
                    )
                }
            }

            val upcoming = queue.drop(index + 1)
            itemsIndexed(upcoming, key = { _, t -> t.id + "-q" }) { i, track ->
                TrackRow(
                    track = track,
                    playing = playing?.id == track.id,
                    appearAfter = i,
                    onClick = { Playback.play(queue, index + 1 + i, from) },
                    trailing = {
                        RoundIcon(
                            NeoIcons.Close, "Take out of the queue",
                            onClick = { Playback.removeAt(index + 1 + i) },
                            diameter = 34.dp, icon = 14.dp, rest = 2.dp,
                        )
                    },
                )
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
}
