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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.player.Library
import com.museroom.app.player.Sound
import com.museroom.app.player.Streams
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.ui.kit.hardShadow

/**
 * The choices about sound that Museroom actually honours.
 *
 * Short on purpose. Skip silence, crossfade, normalisation and an equalizer
 * belong on this screen and are not built, and a screen of switches that do
 * nothing is worse than a short one that means what it says.
 */
@Composable
fun SoundScreen(onBack: () -> Unit) {
    val c = Neo.colors
    val quality by Sound.quality.collectAsStateWithLifecycle()
    val songs by Library.songCount.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundIcon(NeoIcons.Back, "Back", onClick = onBack, diameter = 42.dp)
            Text(
                "SETTINGS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.W900,
                letterSpacing = 1.6.sp,
                fontSize = 10.sp,
                color = c.ink.copy(alpha = 0.5f),
            )
        }

        Text("SOUND", style = bangers(34).copy(color = c.ink))
        Spacer(Modifier.height(14.dp))

        NeoCard(radius = 16.dp, shadow = 4.dp, padding = 14.dp) {
            Label("Streaming quality")
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(percent = 50))
                    .border(3.dp, c.ink, RoundedCornerShape(percent = 50)),
            ) {
                Streams.Quality.entries.forEach { option ->
                    val chosen = option == quality
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (chosen) c.lime else c.card)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { Sound.choose(option) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            option.name.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.W900,
                            letterSpacing = 1.2.sp,
                            fontSize = 11.sp,
                            color = if (chosen) c.onAccent else c.ink,
                        )
                    }
                }
            }
            Spacer(Modifier.height(9.dp))
            Note(
                when (quality) {
                    Streams.Quality.Low -> "For a metered connection. The cheapest thing that is still music."
                    Streams.Quality.High -> "The default, and hard to tell from Max on a phone."
                    Streams.Quality.Max -> "The best file offered, whatever it costs in data."
                },
            )
            Spacer(Modifier.height(6.dp))
            Note("Takes effect on the next track, not the one playing.")
        }

        Spacer(Modifier.height(14.dp))

        NeoCard(radius = 16.dp, shadow = 4.dp, padding = 14.dp) {
            Label("Recently played")
            Spacer(Modifier.height(6.dp))
            Note(
                "$songs remembered. Home builds its suggestions from these, so " +
                    "clearing them empties those shelves until you play something.",
            )
            Spacer(Modifier.height(12.dp))
            NeoButton(
                text = "Forget everything unliked",
                tone = NeoTone.Pink,
                small = true,
                onClick = { Library.forgetUnliked() },
            )
        }
    }
}
