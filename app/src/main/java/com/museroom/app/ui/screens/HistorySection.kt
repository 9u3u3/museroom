package com.museroom.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.data.MuseroomDatabase
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.util.formatMinutes
import java.time.LocalDate
import java.time.ZoneId

private enum class Span(val label: String, val days: Long) {
    Week("Week", 7),
    Month("Month", 30),
    All("All", 3_650),
}

/**
 * What you have actually been listening to.
 *
 * Every play has been written down since the first day, and none of it was
 * ever shown back: the app knew your top artist of the year and would only
 * tell you how many minutes today had. All of this is read from the phone's
 * own database, so it works signed out and needs no network.
 */
@Composable
fun HistorySection() {
    val context = LocalContext.current
    val c = Neo.colors
    val dao = remember { MuseroomDatabase.get(context).dao() }
    var span by remember { mutableStateOf(Span.Month) }

    val since = remember(span) {
        LocalDate.now().minusDays(span.days)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val artists by dao.topArtistsSince(since, 5).collectAsStateWithLifecycle(emptyList())
    val tracks by dao.topTracksSince(since, 5).collectAsStateWithLifecycle(emptyList())
    val total by dao.creditedSince(since).collectAsStateWithLifecycle(0L)
    val trackCount by dao.tracksSince(since).collectAsStateWithLifecycle(0)

    Label("Your listening", color = c.ink)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Span.entries.forEach { option ->
            NeoButton(
                text = option.label,
                small = true,
                tone = if (option == span) NeoTone.Violet else NeoTone.Paper,
                onClick = { span = option },
            )
        }
    }

    if (artists.isEmpty() && tracks.isEmpty()) {
        NeoCard(radius = 16.dp, padding = 16.dp) {
            Note("Nothing counted yet in this stretch. Play something and it turns up here.")
        }
        return
    }

    NeoCard(radius = 18.dp, shadow = 5.dp, padding = 16.dp) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Label("Listened", color = c.ink)
                Text(formatMinutes(total), style = bangers(38).copy(color = c.ink))
            }
            Column(horizontalAlignment = Alignment.End) {
                Label("Tracks", color = c.ink)
                Text("$trackCount", style = bangers(38).copy(color = c.ink))
            }
        }
    }

    if (artists.isNotEmpty()) {
        NeoCard(radius = 18.dp, shadow = 5.dp, padding = 16.dp) {
            Label("Most played artists", color = c.ink)
            Spacer(Modifier.size(10.dp))
            artists.forEachIndexed { index, artist ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    MonoText("%02d".format(index + 1), size = 12, color = c.ink.copy(alpha = 0.6f))
                    Text(
                        artist.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    MonoText(formatMinutes(artist.creditedMs), size = 12, color = c.ink)
                }
                if (index != artists.lastIndex) Spacer(Modifier.size(9.dp))
            }
        }
    }

    if (tracks.isNotEmpty()) {
        NeoCard(radius = 18.dp, shadow = 5.dp, padding = 16.dp) {
            Label("Most played tracks", color = c.ink)
            Spacer(Modifier.size(10.dp))
            tracks.forEachIndexed { index, track ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    MonoText("%02d".format(index + 1), size = 12, color = c.ink.copy(alpha = 0.6f))
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            track.artist.ifBlank { "Unknown artist" },
                            style = MaterialTheme.typography.bodySmall,
                            color = c.ink.copy(alpha = 0.62f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        MonoText(formatMinutes(track.creditedMs), size = 12, color = c.ink)
                        MonoText(
                            "${track.plays}×",
                            size = 10,
                            color = c.ink.copy(alpha = 0.6f),
                        )
                    }
                }
                if (index != tracks.lastIndex) Spacer(Modifier.size(9.dp))
            }
        }
    }
}
