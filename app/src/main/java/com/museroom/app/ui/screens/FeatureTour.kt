package com.museroom.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers

/**
 * Whether the tour has been shown, so it is shown once and then never again
 * unless somebody asks for it.
 */
object TourState {

    private const val PREFS = "museroom.tour"
    private const val KEY_SEEN = "seen_v1"

    fun seen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SEEN, true).apply()
    }
}

private data class Feature(val tint: Color, val title: String, val body: String)

/**
 * What is actually in here.
 *
 * Half of this app is things that only happen if somebody switches them on:
 * a radio that finds people in the room, a door that decides who can walk into
 * your music, a switch that stops the recording entirely. None of that is
 * discoverable by pressing around a screen that shows one song, so it is said
 * plainly, once, and left somewhere it can be read again.
 */
@Composable
fun FeatureTour(onDismiss: () -> Unit) {
    val c = Neo.colors

    val features = listOf(
        Feature(
            c.violet,
            "It reads what's playing",
            "Spotify, YouTube Music, Apple Music and a fixed list of other music " +
                "apps. Nothing else on your phone is ever read.",
        ),
        Feature(
            c.sky,
            "Friends see it live",
            "Add somebody by username and their track shows up with a clock that " +
                "ticks. You pick who sees yours: everyone, friends only, or nobody.",
        ),
        Feature(
            c.lime,
            "Join what they're playing",
            "Ask, and the moment they say yes their song starts on your phone too. " +
                "They skip, you skip. No ads, nothing to press.",
        ),
        Feature(
            c.pink,
            "Choose who can walk in",
            "Under You, set your room to ask first or let anyone in. An open room " +
                "gets joined without a word.",
        ),
        Feature(
            c.violet,
            "Find people around you",
            "Turn On the air, on the Nearby tab, and Bluetooth finds other " +
                "listeners in the room with you. It never uses your location.",
        ),
        Feature(
            c.sky,
            "Minutes and tracks are counted",
            "A track counts when it finishes, and the totals are worked out on a " +
                "server so nobody can inflate them. Day, week, month, all time.",
        ),
        Feature(
            c.lime,
            "Friends listening, quietly",
            "You get a silent notification when a friend puts something on. It is " +
                "on by default; turn it off under You, or mute one friend from the " +
                "Friends tab.",
        ),
        Feature(
            c.pink,
            "A private session stops everything",
            "Nothing recorded, no minutes counted, nobody can see you, and Nearby " +
                "switches off with it.",
        ),
        Feature(
            c.violet,
            "You are a name you chose",
            "Not your email — it is never shown to anybody. Pick a username and add " +
                "a photo under You, and that is all anyone else sees.",
        ),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("What's in here", style = bangers(30).copy(color = c.ink)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                features.forEach { feature ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier
                                .padding(top = 3.dp)
                                .size(16.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(feature.tint)
                                .border(2.5.dp, c.onAccent, RoundedCornerShape(6.dp)),
                        )
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                feature.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = c.ink,
                            )
                            Spacer(Modifier.size(2.dp))
                            Text(
                                feature.body,
                                style = MaterialTheme.typography.bodySmall,
                                color = c.ink.copy(alpha = 0.72f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "GOT IT",
                    color = c.ink,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}
