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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.player.Library
import com.museroom.app.player.Playback
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.NeoIcon
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.hardShadow

private enum class Shelf(val label: String) { Liked("Liked"), Songs("Songs") }

/**
 * Everything this phone knows, which is a shorter list than it sounds.
 *
 * Two shelves, both real. Playlists, albums and artists belong here and are not
 * built, and a chip that opens onto nothing is worse than a chip that is not
 * there. They arrive with the pages behind them.
 */
@Composable
fun LibraryScreen(onOpenPlayer: () -> Unit) {
    val c = Neo.colors
    var shelf by remember { mutableStateOf(Shelf.Liked) }

    val liked by Library.liked.collectAsStateWithLifecycle()
    val songs by Library.songs.collectAsStateWithLifecycle()
    val playing by Playback.current.collectAsStateWithLifecycle()

    val shown = if (shelf == Shelf.Liked) liked else songs

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("LIBRARY", style = bangers(34).copy(color = c.ink))
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Shelf.entries.forEach { option ->
                val count = if (option == Shelf.Liked) liked.size else songs.size
                Chip(
                    text = if (count > 0) "${option.label} $count" else option.label,
                    selected = shelf == option,
                    onClick = { shelf = option },
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        if (shown.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Note(
                if (shelf == Shelf.Liked) {
                    "Tap the heart on anything you want to keep. Liked songs live here."
                } else {
                    "Everything you play in Museroom is remembered here."
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
                if (shelf == Shelf.Liked) "NEWEST FIRST" else "RECENTLY PLAYED",
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
                    trailing = { Heart(track) },
                )
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
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
