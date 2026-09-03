package com.museroom.app.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * The top 100, read from precomputed ranks. The podium is the first three, given
 * the room they earn; everyone else is a row.
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val c = Neo.colors
    val auth = remember { AuthRepository.get(context) }
    val repo = remember { BoardRepository.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()

    var period by remember { mutableStateOf(BoardPeriod.Week) }
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
                        BoardPeriod.Week -> "Week"
                        BoardPeriod.Month -> "Month"
                        BoardPeriod.All -> "All time"
                    },
                    small = true,
                    tone = if (p == period) NeoTone.Violet else NeoTone.Paper,
                    onClick = { period = p },
                )
            }
        }

        val me = session?.userId
        val top3 = entries.take(3)
        if (top3.size == 3) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Podium(top3[1], c.sky, 104.dp, Modifier.weight(1f))
                Podium(top3[0], c.lime, 132.dp, Modifier.weight(1.15f))
                Podium(top3[2], c.pink, 88.dp, Modifier.weight(1f))
            }
        }

        entries.drop(if (top3.size == 3) 3 else 0).forEach { entry ->
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
private fun Podium(entry: BoardEntry, fill: Color, height: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val c = Neo.colors
    val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    Column(
        modifier
            .height(height)
            .clip(shape)
            .background(fill)
            .border(3.dp, c.onAccent, shape)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${entry.rank}", style = bangers(30).copy(color = c.onAccent))
        Spacer(Modifier.size(4.dp))
        Text(
            "@${entry.handle}",
            style = MaterialTheme.typography.bodySmall,
            color = c.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            formatMinutes(entry.creditedMs),
            style = MaterialTheme.typography.bodySmall,
            color = c.onAccent.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun BoardRow(entry: BoardEntry, mine: Boolean) {
    val c = Neo.colors
    NeoCard(
        fill = if (mine) c.violet else c.card,
        stroke = if (mine) c.onAccent else c.ink,
        content = if (mine) Color.White else c.ink,
        radius = 14.dp, shadow = if (mine) 5.dp else 3.dp, padding = 12.dp,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            MonoText(
                "%02d".format(entry.rank),
                size = 13,
                color = if (mine) Color.White else c.ink,
            )
            Text(
                "@${entry.handle}" + if (mine) " · you" else "",
                style = MaterialTheme.typography.titleMedium,
                color = if (mine) Color.White else c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatMinutes(entry.creditedMs),
                style = MaterialTheme.typography.titleMedium,
                color = if (mine) Color.White else c.ink,
            )
        }
    }
}
