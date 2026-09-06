package com.museroom.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.hardShadow

/**
 * People you could be listening with, in one place.
 *
 * Friends and Nearby were separate tabs and were never separate ideas: one is
 * people you chose and the other is people who happen to be in the room, and
 * both answer the same question. Making them chips of one tab freed the fifth
 * sticker for the library, and neither screen lost anything in the move.
 */
@Composable
fun RoomsScreen() {
    var nearby by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Where("Friends", !nearby) { nearby = false }
            Where("Nearby", nearby) { nearby = true }
        }
        Box(Modifier.fillMaxSize()) {
            if (nearby) NearbyScreen() else FriendsScreen()
        }
    }
}

@Composable
private fun Where(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Neo.colors
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .hardShadow(3.dp, c.ink, shape)
            .clip(shape)
            .background(if (selected) c.ink else c.card)
            .border(2.5.dp, c.ink, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 15.dp, vertical = 8.dp),
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.W900,
            letterSpacing = 1.2.sp,
            fontSize = 11.sp,
            color = if (selected) c.paper else c.ink,
        )
    }
}
