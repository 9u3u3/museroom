package com.museroom.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.player.Playback
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.NeoIcons

/**
 * The bar that says something is playing.
 *
 * It sits on the navigation rather than floating over the list, because a bar
 * that hovers hides the last row of every screen it appears on. There is no
 * scrub here and no queue: it is a reminder and a way back to the player, and
 * the two controls on it are the two anybody uses without looking.
 */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val c = Neo.colors
    val track by Playback.current.collectAsStateWithLifecycle()
    val snapshot by Playback.snapshot.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = track != null,
        // Rising from where the navigation is, so it reads as the nav growing a
        // row rather than as a panel arriving from somewhere else.
        enter = slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
    ) {
        val song = track ?: return@AnimatedVisibility
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.card)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpen,
                ),
        ) {
            // A three-pixel rule, the same weight as every other edge in the kit.
            Box(Modifier.fillMaxWidth().height(3.dp).background(c.ink))

            val duration = if (snapshot.durationMs > 0) snapshot.durationMs else song.durationMs
            val fraction = if (duration > 0) {
                (snapshot.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            } else {
                0f
            }
            val eased by animateFloatAsState(fraction, tween(500), label = "mini")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(c.ink.copy(alpha = 0.14f)),
            ) {
                Box(Modifier.fillMaxWidth(eased).fillMaxHeight().background(c.violet))
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                TrackCover(song.id, song.artworkUrl, Modifier.size(40.dp), radius = 9.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist.ifBlank { snapshot.detail },
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                        color = c.ink.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RoundIcon(
                    if (snapshot.playing) NeoIcons.Pause else NeoIcons.Play,
                    if (snapshot.playing) "Pause" else "Play",
                    onClick = { Playback.toggle() },
                    diameter = 38.dp, icon = 18.dp, rest = 3.dp,
                )
                RoundIcon(
                    NeoIcons.Next, "Next",
                    onClick = { Playback.next() },
                    diameter = 38.dp, icon = 18.dp, rest = 3.dp,
                )
            }
        }
    }
}
