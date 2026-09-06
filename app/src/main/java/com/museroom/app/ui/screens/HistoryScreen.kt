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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.data.ListeningSessionEntity
import com.museroom.app.data.MuseroomDatabase
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.DropTitle
import com.museroom.app.ui.kit.Kicker
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoPill
import com.museroom.app.ui.kit.NeoTabs
import com.museroom.app.ui.kit.Shelf
import com.museroom.app.ui.kit.halftone
import com.museroom.app.ui.kit.hardShadow
import com.museroom.app.util.formatMinutes
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private enum class Span(val label: String, val days: Long) {
    Today("Today", 0),
    Week("Week", 7),
    All("All time", 3_650),
}

/**
 * The tally.
 *
 * Every play has been written down since the first day, and for a long time
 * none of it was shown back: the app knew your top artist of the year and would
 * only tell you how many minutes today had. All of this is read from the
 * phone's own database, so it works signed out and needs no network.
 *
 * Entries can be removed. It is the one place in the app that deletes
 * something, so the sentence saying what removal means is printed above the
 * list rather than hidden in a confirmation nobody reads.
 */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = Neo.colors
    val dao = remember { MuseroomDatabase.get(context).dao() }
    val scope = rememberCoroutineScope()
    var span by remember { mutableStateOf(Span.Today) }
    var pending by remember { mutableStateOf<ListeningSessionEntity?>(null) }

    val since = remember(span) {
        val day = LocalDate.now().minusDays(span.days)
        day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val total by dao.creditedSince(since).collectAsStateWithLifecycle(0L)
    val trackCount by dao.tracksSince(since).collectAsStateWithLifecycle(0)
    val recent by dao.recentSessions(60).collectAsStateWithLifecycle(emptyList())
    val artists by dao.topArtistsSince(since, 5).collectAsStateWithLifecycle(emptyList())
    val within = recent.filter { it.endedAtClock >= since }

    Column(Modifier.fillMaxSize()) {
        PageBar(
            crumb = "Your listening",
            onBack = onBack,
            action = { NeoPill("$trackCount tracks") },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            DropTitle("The tally", size = 32, drop = c.violet)

            // The one number the screen is for, given the loudest surface in
            // the kit. The sentence under it is the rule that produced it, and
            // it belongs on the number rather than in a settings screen.
            Spacer(Modifier.height(14.dp))
            Box {
                val shape = RoundedCornerShape(20.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .hardShadow(6.dp, c.onAccent, shape)
                        .clip(shape)
                        .background(c.lime)
                        .halftone(c.onAccent, alpha = 0.14f, step = 12.dp)
                        .border(3.dp, c.onAccent, shape)
                        .padding(18.dp),
                ) {
                    Kicker(
                        when (span) {
                            Span.Today -> "Listening today"
                            Span.Week -> "Listening this week"
                            Span.All -> "Listening, all of it"
                        },
                        color = c.onAccent,
                    )
                    Text(formatMinutes(total), style = bangers(74).copy(color = c.onAccent))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Counted only when a track finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onAccent.copy(alpha = 0.72f),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            NeoTabs(
                options = Span.entries.map { it.label },
                selected = Span.entries.indexOf(span),
                onPick = { span = Span.entries[it] },
                height = 38.dp,
                radius = 50.dp,
            )

            if (within.isEmpty()) {
                Spacer(Modifier.height(18.dp))
                NeoCard(radius = 16.dp, padding = 16.dp) {
                    Note("Nothing counted yet in this stretch. Play something and it turns up here.")
                }
                return@Column
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Tap an entry to remove it, here and on the server.",
                style = MaterialTheme.typography.bodySmall,
                color = c.ink.copy(alpha = 0.6f),
            )

            within.forEach { entry ->
                Entry(entry, onRemove = { pending = entry })
            }

            if (artists.isNotEmpty()) {
                Shelf("Most played")
                artists.forEachIndexed { index, artist ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        MonoText(
                            "%02d".format(index + 1),
                            size = 12,
                            color = c.ink.copy(alpha = 0.6f),
                        )
                        Text(
                            artist.artist.ifBlank { "Unknown artist" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        MonoText(formatMinutes(artist.creditedMs), size = 12, color = c.ink)
                    }
                }
            }
        }
    }

    pending?.let { entry ->
        ConfirmDialog(
            title = "Remove this?",
            body = "${entry.title} comes off your total, here and on the server.",
            confirm = "Remove",
            onConfirm = {
                scope.launch {
                    dao.deleteSession(entry.id)
                    dao.deleteEventsFor(entry.fingerprint)
                }
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
}

/** One thing you listened to, and how much of it counted. */
@Composable
private fun Entry(entry: ListeningSessionEntity, onRemove: () -> Unit) {
    val c = Neo.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .hardShadow(3.dp, c.ink, shape)
            .clip(shape)
            .background(c.card)
            .border(3.dp, c.ink, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onRemove,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        TrackCover(entry.fingerprint, null, Modifier.size(44.dp), radius = 10.dp)
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.artist.ifBlank { "Unknown artist" },
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = c.ink.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MonoText(formatMinutes(entry.creditedMs), size = 12, color = c.ink)
    }
}
