package com.museroom.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.media.Avatars
import com.museroom.app.net.AuthRepository
import com.museroom.app.net.BoardEntry
import com.museroom.app.net.BoardPeriod
import com.museroom.app.net.BoardRepository
import com.museroom.app.net.BoardSort
import com.museroom.app.ui.Refreshing
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.DropTitle
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoChip
import com.museroom.app.ui.kit.NeoPill
import com.museroom.app.ui.kit.NeoTabs
import com.museroom.app.ui.kit.hardShadow
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.util.formatMinutes

/**
 * The top 100, read from precomputed ranks.
 *
 * The first three are staged and the rest are rows. A podium was argued
 * against once, on the grounds that it makes fourth place look like an
 * afterthought — which is true, and is also what a leaderboard is for. The
 * design settles it: three blocks of colour at different heights say who won
 * without anybody having to read a number, and your own row keeps the loud
 * violet at whatever depth it sits, so finding yourself is still one glance.
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
    var sort by remember { mutableStateOf(BoardSort.Minutes) }
    var entries by remember { mutableStateOf<List<BoardEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    // Half a minute is fine for a board that is recomputed in bulk anyway.
    // What matters more is that coming back to the app re-reads it, which is
    // when somebody is most likely to be looking for their own name.
    Refreshing(period, sort, session?.userId, everyMs = 30_000) {
        if (session == null) return@Refreshing
        loading = true
        repo.top(period, sort)
            .onSuccess { entries = it; error = null }
            .onFailure { error = it.message }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
    TabBar(
        left = {
            Crumb(
                when (period) {
                    BoardPeriod.Day -> "Today · resets at midnight"
                    BoardPeriod.Week -> "This week · resets Monday"
                    BoardPeriod.Month -> "This month"
                    BoardPeriod.All -> "Everything ever counted"
                },
            )
        },
        right = { NeoPill(sort.label, fill = c.lime, accent = true) },
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DropTitle("Top 100", size = 34, drop = c.lime)

        if (session == null) {
            SignInPanel("Sign in to be ranked.", heading = false)
            return@Column
        }

        NeoTabs(
            options = BoardPeriod.entries.map {
                when (it) {
                    BoardPeriod.Day -> "Day"
                    BoardPeriod.Week -> "Week"
                    BoardPeriod.Month -> "Month"
                    BoardPeriod.All -> "All"
                }
            },
            selected = BoardPeriod.entries.indexOf(period),
            onPick = { period = BoardPeriod.entries[it] },
            height = 42.dp,
            radius = 50.dp,
        )

        // Two questions, not one. Minutes say who sat there longest; likes say
        // whose taste other people actually turned up for.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoardSort.entries.forEach { option ->
                NeoChip(option.label, sort == option, { sort = option })
            }
        }

        val me = session?.userId
        val top = entries.filter { it.rank <= 3 }.sortedBy { it.rank }
        if (top.size == 3) Podium(top, sort)

        entries.filter { it.rank > 3 }.forEach { entry ->
            BoardRow(entry, mine = entry.userId == me, sort = sort)
        }

        entries.firstOrNull { it.userId == me }?.let {
            Spacer(Modifier.size(4.dp))
            BoardRow(it, mine = true, sort = sort)
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
}

/**
 * The first three, as three blocks of colour at three heights.
 *
 * First is in the middle and tallest, which is the shape everybody already
 * reads as a podium; second is on the left because that is the way a page is
 * read and it puts the winner between the two runners-up rather than beside
 * them. The colours are the kit's three accents, so the block itself says the
 * position and the number on it only confirms.
 */
@Composable
private fun Podium(top: List<BoardEntry>, sort: BoardSort) {
    val c = Neo.colors
    val order = listOfNotNull(
        top.getOrNull(1)?.let { it to (c.sky to 104.dp) },
        top.getOrNull(0)?.let { it to (c.lime to 132.dp) },
        top.getOrNull(2)?.let { it to (c.pink to 88.dp) },
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        order.forEach { (entry, look) ->
            val (fill, tall) = look
            val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
            Column(
                Modifier
                    .weight(if (entry.rank == 1) 1.15f else 1f)
                    .height(tall)
                    .hardShadow(4.dp, c.onAccent, shape)
                    .clip(shape)
                    .background(fill)
                    .border(3.dp, c.onAccent, shape)
                    .clickable { Person.show(entry.userId, entry.handle) }
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("${entry.rank}", style = bangers(30).copy(color = c.onAccent))
                Spacer(Modifier.size(5.dp))
                Text(
                    entry.handle,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 11.sp,
                    color = c.onAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (sort == BoardSort.Likes) {
                        "${entry.likes} " + if (entry.likes == 1L) "like" else "likes"
                    } else {
                        formatMinutes(entry.creditedMs)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = c.onAccent.copy(alpha = 0.72f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BoardRow(entry: BoardEntry, mine: Boolean, sort: BoardSort) {
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

    NeoCard(
        fill = if (mine) c.violet else lead ?: c.card,
        stroke = if (mine || onLead) c.onAccent else c.ink,
        content = ink,
        radius = 16.dp, shadow = if (mine || onLead) 5.dp else 3.dp, padding = 11.dp,
        // Everybody on this list is a person with a page, including the ones
        // you have never met and including you: your own page is the only
        // place your likes are counted up, and it was the one row that would
        // not open.
        modifier = Modifier.clickable { Person.show(entry.userId, entry.handle) },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            MonoText("%02d".format(entry.rank), size = 13, color = ink.copy(alpha = 0.7f))
            Face(entry.handle, entry.avatarUrl, 40.dp, border = ink)
            Text(
                entry.handle + if (mine) " · you" else "",
                style = MaterialTheme.typography.titleMedium,
                color = ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Whichever number the list is sorted by leads, and the other
            // rides underneath. A column sorted by likes that still shouts the
            // minutes is a column nobody can read the order of.
            Column(horizontalAlignment = Alignment.End) {
                if (sort == BoardSort.Likes) {
                    Text(
                        "${entry.likes} " + if (entry.likes == 1L) "like" else "likes",
                        style = MaterialTheme.typography.titleMedium,
                        color = ink,
                    )
                    MonoText(
                        formatMinutes(entry.creditedMs),
                        size = 10, color = ink.copy(alpha = 0.7f),
                    )
                } else {
                    Text(
                        formatMinutes(entry.creditedMs),
                        style = MaterialTheme.typography.titleMedium,
                        color = ink,
                    )
                    // Minutes alone said how long, not how much of it was music
                    // rather than one long track left running. The count answers
                    // that in a word, so it rides along underneath in small type
                    // rather than competing for the same line.
                    MonoText(
                        "${entry.trackCount} " + if (entry.trackCount == 1L) "track" else "tracks",
                        size = 10, color = ink.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}
