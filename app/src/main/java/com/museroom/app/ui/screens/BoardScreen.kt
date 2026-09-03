package com.museroom.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.media.Avatars
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.BoardEntry
import com.museroom.app.net.BoardPeriod
import com.museroom.app.net.BoardRepository
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.util.formatMinutes

/**
 * The top 100, read from precomputed ranks.
 *
 * Rows all the way down, including the first three. A podium spends the top of
 * the screen on three names and makes the fourth look like an afterthought,
 * which is the wrong shape for a list somebody is scrolling to find themselves
 * in. The leaders are marked instead of staged.
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val c = Neo.colors
    val auth = remember { AuthRepository.get(context) }
    val repo = remember { BoardRepository.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()

    // All time by default: it is the number that means something the first
    // time somebody opens this, and the only one that is never empty.
    var period by remember { mutableStateOf(BoardPeriod.All) }
    var entries by remember { mutableStateOf<List<BoardEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(period, session?.userId) {
        if (session == null) return@LaunchedEffect
        loading = true
        repo.top(period)
            .onSuccess { entries = it; error = null }
            .onFailure { error = it.message }
        loading = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Top 100", drop = c.lime)

        if (session == null) {
            SignInPanel("Sign in to be ranked.")
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoardPeriod.entries.forEach { p ->
                NeoButton(
                    text = when (p) {
                        BoardPeriod.Day -> "Day"
                        BoardPeriod.Week -> "Week"
                        BoardPeriod.Month -> "Month"
                        BoardPeriod.All -> "All"
                    },
                    small = true,
                    tone = if (p == period) NeoTone.Violet else NeoTone.Paper,
                    onClick = { period = p },
                )
            }
        }

        val me = session?.userId
        entries.forEach { entry ->
            BoardRow(entry, mine = entry.userId == me)
        }

        entries.firstOrNull { it.userId == me }?.takeIf { it.rank > 3 }?.let {
            Spacer(Modifier.size(4.dp))
            BoardRow(it, mine = true)
        }

        when {
            error != null -> NeoCard { Note(error!!) }
            loading && entries.isEmpty() -> NeoCard { Note("Working out the ranks.") }
            entries.isEmpty() -> NeoCard {
                Note("Nobody has finished a track yet this period.")
            }
        }
    }
}

@Composable
private fun BoardRow(entry: BoardEntry, mine: Boolean) {
    val c = Neo.colors
    // Your own row is the one you came to find, so it keeps the loud colour.
    // The leaders get a tint, which is enough to read as a top three without
    // taking a third of the screen to say so.
    val lead = when (entry.rank) {
        1 -> c.lime
        2 -> c.sky
        3 -> c.pink
        else -> null
    }
    val onLead = lead != null && !mine
    val ink = if (mine) Color.White else if (onLead) c.onAccent else c.ink

    var face by remember(entry.avatarUrl) { mutableStateOf(Avatars.cached(entry.avatarUrl)) }
    LaunchedEffect(entry.avatarUrl) {
        if (face == null) face = Avatars.fetch(entry.avatarUrl)
    }

    NeoCard(
        fill = if (mine) c.violet else lead ?: c.card,
        stroke = if (mine || onLead) c.onAccent else c.ink,
        content = ink,
        radius = 16.dp, shadow = if (mine || onLead) 5.dp else 3.dp, padding = 11.dp,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            MonoText("%02d".format(entry.rank), size = 13, color = ink.copy(alpha = 0.7f))
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(ink.copy(alpha = 0.15f))
                    .border(2.dp, ink, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val picture = face
                if (picture != null) {
                    Image(
                        picture.asImageBitmap(), null, Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        entry.handle.take(1).uppercase().ifBlank { "?" },
                        style = MaterialTheme.typography.titleMedium, color = ink,
                    )
                }
            }
            Text(
                entry.handle + if (mine) " · you" else "",
                style = MaterialTheme.typography.titleMedium,
                color = ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatMinutes(entry.creditedMs),
                style = MaterialTheme.typography.titleMedium,
                color = ink,
            )
        }
    }
}
