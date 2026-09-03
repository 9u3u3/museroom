package com.museroom.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.museroom.app.net.PeopleRepository
import com.museroom.app.net.PublicProfile
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoPill
import com.museroom.app.util.formatMinutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Which person's page is open, if any.
 *
 * Held here rather than passed down, because a name is tappable on five
 * different screens and threading a callback through all of them to reach one
 * dialog is more plumbing than the feature is worth. Any row can ask for a
 * page; the page is drawn once, at the top.
 */
object Person {

    private val _open = MutableStateFlow<Pair<String, String>?>(null)

    /** The user id and the handle to show while the rest is still loading. */
    val open: StateFlow<Pair<String, String>?> = _open.asStateFlow()

    fun show(userId: String, handle: String) {
        if (userId.isBlank()) return
        _open.value = userId to handle
    }

    fun dismiss() {
        _open.value = null
    }
}

/**
 * Somebody else's page.
 *
 * Only what was already public: the name and picture anybody signed in can
 * see, the totals that are already on the leaderboard, and the likes other
 * people gave them. What they have listened to is not here and is not meant to
 * be — the privacy policy promises history stays theirs, and one convenient
 * screen is not a reason to go back on it.
 */
@Composable
fun PersonCard() {
    val open by Person.open.collectAsStateWithLifecycle()
    val (userId, handle) = open ?: return
    val context = LocalContext.current
    val c = Neo.colors
    val people = remember { PeopleRepository.get(context) }

    var profile by remember(userId) { mutableStateOf<PublicProfile?>(null) }
    var failed by remember(userId) { mutableStateOf<String?>(null) }

    LaunchedEffect(userId) {
        people.profile(userId)
            .onSuccess { profile = it; if (it == null) failed = "This page is not available." }
            .onFailure { failed = it.message }
    }

    AlertDialog(
        onDismissRequest = { Person.dismiss() },
        containerColor = c.card,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Face(handle, profile?.avatarUrl, 52.dp, border = c.ink)
                Column(Modifier.weight(1f)) {
                    Text(
                        profile?.handle?.ifBlank { handle } ?: handle,
                        style = bangers(26).copy(color = c.ink),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    profile?.let { Note(joinedLine(it)) }
                }
            }
        },
        text = {
            val p = profile
            when {
                failed != null -> Note(failed!!)
                p == null -> Note("Looking them up.")
                else -> PersonBody(p)
            }
        },
        confirmButton = {
            TextButton(onClick = { Person.dismiss() }) {
                Text("CLOSE", color = c.ink, style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

@Composable
private fun PersonBody(p: PublicProfile) {
    val c = Neo.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.isFriend) NeoPill("Friends", fill = c.lime)
            if (p.openToAll) NeoPill("Room open to all", fill = c.sky)
            if (!p.sharesWithMe) NeoPill("Not sharing", fill = c.card)
        }

        // Likes first. Minutes say how long somebody sat there; this is the
        // only number on the page that other people decided.
        NeoCard(fill = c.pink, stroke = c.onAccent, content = c.onAccent, radius = 16.dp, padding = 14.dp) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Label("Likes received", color = c.onAccent)
                    Text("${p.likesReceived}", style = bangers(44).copy(color = c.onAccent))
                }
                if (p.likesFromMe > 0) {
                    MonoText("${p.likesFromMe} from you", size = 11, color = c.onAccent)
                }
            }
        }

        if (p.creditedMs != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Listening", formatMinutes(p.creditedMs), Modifier.weight(1f))
                Stat("Tracks", "${p.trackCount ?: 0}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("By minutes", p.rank?.let { "#$it" } ?: "—", Modifier.weight(1f))
                Stat("By likes", p.likeRank?.let { "#$it" } ?: "—", Modifier.weight(1f))
            }
        } else {
            // Opting out of the board means the totals are nobody's business,
            // and this page has to hold to that or the opt-out is decorative.
            Note("They have kept their totals off the leaderboard.")
        }

        Note(
            "What they have listened to stays private. This page shows only their " +
                "name, their board totals and the likes other people gave them.",
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    val c = Neo.colors
    NeoCard(modifier = modifier, radius = 14.dp, shadow = 3.dp, padding = 12.dp) {
        Label(label, color = c.ink.copy(alpha = 0.7f))
        Spacer(Modifier.size(2.dp))
        Text(value, style = bangers(28).copy(color = c.ink))
    }
}

private val JOINED = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun joinedLine(p: PublicProfile): String {
    val when_ = runCatching {
        Instant.parse(p.createdAt).atZone(ZoneId.systemDefault()).format(JOINED)
    }.getOrNull() ?: return ""
    return "Here since $when_"
}
