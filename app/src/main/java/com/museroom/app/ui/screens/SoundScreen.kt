package com.museroom.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.player.Downloads
import com.museroom.app.player.Effects
import com.museroom.app.player.Library
import com.museroom.app.player.SleepTimer
import com.museroom.app.player.Sound
import com.museroom.app.player.Streams
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.DropTitle
import com.museroom.app.ui.kit.Kicker
import com.museroom.app.ui.kit.NeoAccentCard
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoSegment
import com.museroom.app.ui.kit.NeoIcons
import com.museroom.app.ui.kit.NeoSwitch
import com.museroom.app.ui.kit.NeoTone
import kotlinx.coroutines.delay

/**
 * The choices about sound that Museroom actually honours.
 *
 * Every row here changes something audible, which is the rule this screen is
 * held to. Quality is what the extractor is asked for. Levelling and the bands
 * are Android's own audio effects on the session the player writes into. Skip
 * silence is ExoPlayer's own. The fade is drawn between one track and the next
 * by the queue.
 *
 * The one card that does not is the YouTube one, and it says so rather than
 * offering a button that would fail.
 */
@Composable
fun SoundScreen(onBack: () -> Unit) {
    val c = Neo.colors
    val quality by Sound.quality.collectAsStateWithLifecycle()
    val songs by Library.songCount.collectAsStateWithLifecycle()
    val offline by Library.offline.collectAsStateWithLifecycle()
    val offlineBytes by Library.offlineBytes.collectAsStateWithLifecycle()
    val skipSilence by Sound.skipSilence.collectAsStateWithLifecycle()
    val normalise by Sound.normalise.collectAsStateWithLifecycle()
    val fade by Sound.fadeSeconds.collectAsStateWithLifecycle()
    val bands by Effects.levels.collectAsStateWithLifecycle()
    val equalizer by Effects.available.collectAsStateWithLifecycle()
    val sleepEndsAt by SleepTimer.endsAt.collectAsStateWithLifecycle()
    val sleepAfterTrack by SleepTimer.afterThisTrack.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
    PageBar(crumb = "Settings", onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        DropTitle("Sound", size = 34, drop = c.pink)
        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------ the account --

        // Violet, because it is the only card on the page that is about an
        // account rather than about the sound, and the design puts the account
        // first for a reason: on most players nothing below it works well
        // without one. Here it does, and saying so is the point of the card.
        NeoAccentCard(fill = c.violet, radius = 16.dp, shadow = 5.dp, padding = 14.dp) {
            Kicker("YouTube Music", color = Color.White)
            Spacer(Modifier.height(3.dp))
            Text(
                "Not signed in",
                style = MaterialTheme.typography.titleLarge,
                fontSize = 16.sp,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Museroom plays without an account. Your library, your likes and " +
                    "your history are this phone's, and they work with the radio off.",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
        }

        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------- the quality --

        NeoCard(radius = 16.dp, shadow = 4.dp, padding = 14.dp) {
            Kicker("Streaming quality")
            Spacer(Modifier.height(10.dp))
            NeoSegment(
                options = Streams.Quality.entries.map { it.name },
                selected = Streams.Quality.entries.indexOf(quality),
                onPick = { Sound.choose(Streams.Quality.entries[it]) },
                height = 40.dp,
            )
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

        // ----------------------------------------------------- kept offline --

        NeoCard(radius = 16.dp, shadow = 4.dp, padding = 14.dp) {
            Label("Kept on this phone")
            Spacer(Modifier.height(6.dp))
            Note(
                if (offline.isEmpty()) {
                    "Nothing yet. The download button on an album, a playlist or a " +
                        "song puts the file here, and it plays with the radio off."
                } else {
                    "${offline.size} ${if (offline.size == 1) "song" else "songs"} · ${size(offlineBytes)}"
                },
            )
            if (offline.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                NeoButton(
                    text = "Remove all downloads",
                    tone = NeoTone.Pink,
                    small = true,
                    onClick = { Downloads.removeEverything() },
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // -------------------------------------------------------- the switches --

        NeoCard(radius = 16.dp, shadow = 4.dp, padding = 14.dp) {
            SwitchRow(
                title = "Normalise volume",
                note = "Levels quiet masters up to sit with the loud ones, using " +
                    "the loudness figure the recording ships with.",
                checked = normalise,
                onChange = Sound::setNormalise,
            )
            Spacer(Modifier.height(14.dp))
            SwitchRow(
                title = "Skip silence",
                note = "Jumps the long gaps some masters leave at the end of a track.",
                checked = skipSilence,
                onChange = Sound::setSkipSilence,
            )
        }

        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------------ the fade --

        NeoCard(radius = 16.dp, shadow = 4.dp, padding = 14.dp) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Label("Crossfade")
                Text(
                    if (fade == 0) "OFF" else "${fade}S",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.W900,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                    color = c.ink,
                )
            }
            Spacer(Modifier.height(6.dp))
            Note(
                "Museroom has one player rather than two, so this is a fade out " +
                    "and a fade in rather than an overlap. Always off in a room, " +
                    "because everybody there is steering by one position.",
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 3, 6, 9, 12).forEach { seconds ->
                    val chosen = seconds == fade
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(if (chosen) c.violet else c.card)
                            .border(2.5.dp, c.ink, RoundedCornerShape(percent = 50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { Sound.setFade(seconds) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (seconds == 0) "OFF" else "${seconds}S",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.W900,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            color = if (chosen) androidx.compose.ui.graphics.Color.White else c.ink,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ------------------------------------------------------ the equalizer --

        NeoCard(radius = 16.dp, shadow = 4.dp, padding = 14.dp) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Label("Equalizer")
                if (equalizer && bands.any { it != 0 }) {
                    Text(
                        "FLATTEN",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.W900,
                        fontSize = 10.sp,
                        letterSpacing = 1.2.sp,
                        color = c.violet,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { Sound.flattenBands() },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!equalizer) {
                Note("This phone does not offer one to apps, so there is nothing to draw.")
            } else {
                Row(
                    Modifier.fillMaxWidth().height(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Effects.bands.forEachIndexed { index, name ->
                        Fader(
                            label = name,
                            decibels = bands.getOrElse(index) { 0 },
                            onChange = { Sound.setBand(index, it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ----------------------------------------------------- the sleep timer --

        NeoCard(radius = 16.dp, shadow = 4.dp, padding = 14.dp) {
            Label("Sleep timer")
            Spacer(Modifier.height(6.dp))
            Note(countdown(sleepEndsAt, sleepAfterTrack))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { minutes ->
                    Box(Modifier.weight(1f)) {
                        NeoButton(
                            text = "${minutes}m",
                            tone = NeoTone.Paper,
                            small = true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { SleepTimer.set(minutes) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    NeoButton(
                        text = "End of track",
                        tone = NeoTone.Paper,
                        small = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { SleepTimer.afterTrack() },
                    )
                }
                Box(Modifier.weight(1f)) {
                    NeoButton(
                        text = "Off",
                        tone = NeoTone.Pink,
                        small = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { SleepTimer.cancel() },
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // -------------------------------------------------------- the history --

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

        Spacer(Modifier.height(28.dp))
    }
    }
}

/** A title, a sentence saying what it does, and the switch itself. */
@Composable
private fun SwitchRow(
    title: String,
    note: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Label(title)
            Spacer(Modifier.height(5.dp))
            Note(note)
        }
        NeoSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * One band, dragged up and down.
 *
 * A vertical drag rather than a Material slider, because a horizontal control
 * for a thing drawn as a column is the sort of mismatch people push against
 * once and then stop trusting.
 */
@Composable
private fun Fader(
    label: String,
    decibels: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Neo.colors
    val density = LocalDensity.current
    val travel = with(density) { 110.dp.toPx() }
    // Zero sits in the middle, so the knob's distance from centre is the whole
    // reading. Fifteen either way is the range the engine clamps to.
    val fraction = (decibels / 15f).coerceIn(-1f, 1f)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .weight(1f)
                .width(30.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(c.paper)
                .border(2.5.dp, c.ink, RoundedCornerShape(percent = 50))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        // Up is louder, and the screen's y axis points the other
                        // way, so the sign is flipped here rather than in the
                        // engine, where it would confuse the next reader.
                        val steps = (-drag.y / travel * 30).toInt()
                        if (steps != 0) onChange(decibels + steps)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // A hairline at zero, so a band that is doing nothing looks like it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(c.ink.copy(alpha = 0.25f)),
            )
            BoxWithConstraints(Modifier.fillMaxHeight().fillMaxWidth()) {
                val reach = (maxHeight - 26.dp) / 2
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset(y = -reach * fraction)
                        .size(24.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (decibels == 0) c.card else c.lime)
                        .border(2.5.dp, c.ink, RoundedCornerShape(percent = 50)),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.W900,
            fontSize = 9.sp,
            color = c.ink.copy(alpha = 0.6f),
        )
        Text(
            if (decibels > 0) "+$decibels" else "$decibels",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.W900,
            fontSize = 9.sp,
            color = c.ink,
        )
    }
}

/**
 * What the timer says, refreshed every second while one is running.
 *
 * The tick only exists while there is a deadline, so a settings screen sitting
 * open with no timer set costs nothing.
 */
@Composable
private fun countdown(endsAt: Long, afterTrack: Boolean): String {
    if (afterTrack) return "The music stops when this song ends."
    if (endsAt == 0L) return "Off. The music keeps going until you stop it."
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val left = (endsAt - now).coerceAtLeast(0)
    return "Stopping in ${clockOf(left)}."
}

/** Bytes, as a person would say them. */
private fun size(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes bytes"
}
