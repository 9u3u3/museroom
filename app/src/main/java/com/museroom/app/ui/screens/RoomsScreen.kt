package com.museroom.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.net.RequestsRepository
import com.museroom.app.proximity.ProximityManager
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.DropTitle
import com.museroom.app.ui.kit.LiveDot
import com.museroom.app.ui.kit.NeoChip
import com.museroom.app.ui.kit.NeoPill

/**
 * People you could be listening with, in one place.
 *
 * Friends, Nearby and Requests were never three ideas: one is people you chose,
 * one is people who happen to be in the room, and one is people asking. All
 * three answer the same question. Making them chips of one tab freed the fifth
 * sticker for the library, and no screen lost anything in the move.
 *
 * The bar, the title and the chips are drawn here rather than three times over,
 * because they are the same bar: only the word in the display face and the
 * colour under it change, which is exactly what the design shows.
 */
private enum class Who(val label: String) {
    Friends("Friends"),
    Nearby("Nearby"),
    Requests("Requests"),
}

@Composable
fun RoomsScreen(onOpenRoom: () -> Unit = {}) {
    val c = Neo.colors
    val context = LocalContext.current
    var who by remember { mutableStateOf(Who.Friends) }
    val waiting by remember { RequestsRepository.get(context).count }
        .collectAsStateWithLifecycle()
    val inRange by remember { ProximityManager.get(context).nearby }
        .collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TabBar(
            left = { Crumb("Museroom") },
            right = {
                when (who) {
                    Who.Friends -> NeoPill("Rooms", leading = { LiveDot(size = 9.dp) })
                    Who.Nearby -> NeoPill(
                        if (inRange.isEmpty()) "Nobody in range" else "${inRange.size} in range",
                        fill = if (inRange.isEmpty()) c.card else c.lime,
                        accent = inRange.isNotEmpty(),
                    )
                    Who.Requests -> NeoPill(
                        if (waiting > 0) "$waiting waiting" else "Nothing waiting",
                        fill = if (waiting > 0) c.sky else c.card,
                        accent = waiting > 0,
                    )
                }
            },
        )

        Column(Modifier.padding(horizontal = 20.dp)) {
            DropTitle(
                who.label,
                drop = when (who) {
                    Who.Friends -> c.sky
                    Who.Nearby -> c.pink
                    Who.Requests -> c.violet
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Who.entries.forEach { option ->
                    NeoChip(option.label, who == option, { who = option })
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when (who) {
                Who.Friends -> FriendsScreen(onOpenRoom)
                Who.Nearby -> NearbyScreen()
                // The same screen the dot on the rail points at. It is here as
                // well because a request to listen is a person you could be
                // listening with, which is what this tab is.
                Who.Requests -> RequestsScreen()
            }
        }
    }
}
