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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.MuseroomMark
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoIcon
import com.museroom.app.ui.kit.NeoIcons
import android.os.Build
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.util.NotificationAccess

/**
 * The permission gate. It explains before it asks, because dropping someone cold
 * into Android's notification-access list loses them, and because the promise
 * about what is read has to be made before the permission, not after.
 */
@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val c = Neo.colors

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(74.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(c.violet)
                    .border(3.dp, c.onAccent, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                MuseroomMark(size = 48.dp, note = c.onAccent, ghost = c.lime)
            }
            Text("MUSEROOM", style = bangers(38).copy(color = c.ink), maxLines = 1)
        }

        Spacer(Modifier.size(26.dp))

        NeoCard(radius = 20.dp, shadow = 6.dp, padding = 20.dp) {
            Text(
                "First, let it hear the music.",
                style = MaterialTheme.typography.titleLarge,
                color = c.ink,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "Museroom reads the media session Android keeps for whatever is playing.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink.copy(alpha = 0.8f),
            )
        }

        Spacer(Modifier.size(22.dp))

        listOf(
            "Music apps only, from a fixed list",
            "Every other notification is ignored",
            "Nothing is shared until you sign in",
        ).forEach { promise ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 9.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.card)
                    .border(2.5.dp, c.ink, RoundedCornerShape(12.dp))
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(c.lime)
                        .border(2.5.dp, c.onAccent, RoundedCornerShape(percent = 50)),
                    contentAlignment = Alignment.Center,
                ) {
                    NeoIcon(NeoIcons.Check, size = 13.dp, color = c.onAccent, weight = 4f)
                }
                Text(promise, style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.size(20.dp))

        NeoButton(
            text = "Allow access",
            onClick = { NotificationAccess.openSettings(context) },
            modifier = Modifier.fillMaxWidth(),
        )

        // Android 13 and later hide this switch for apps installed outside an
        // app store, behind a dialog that explains nothing about how to proceed.
        // Saying it here is the difference between working and looking broken.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(Modifier.size(14.dp))
            NeoCard(radius = 14.dp, shadow = 3.dp, padding = 14.dp) {
                Text(
                    "If Settings says \"Restricted setting\"",
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                )
                Spacer(Modifier.size(5.dp))
                Text(
                    "Android blocks this for apps installed outside the Play Store, and " +
                        "nothing in Museroom can unblock it. Open App info below, then find " +
                        "Allow restricted settings. It is behind the three dots on most " +
                        "phones, under Advanced or More on some, and near the bottom of the " +
                        "page on others. If it is not there at all, uninstall Museroom, " +
                        "install it again, and open this within the next few minutes: the " +
                        "option only appears for a short while after installing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.ink.copy(alpha = 0.75f),
                )
                Spacer(Modifier.size(10.dp))
                NeoButton(
                    text = "Open app info",
                    small = true,
                    tone = NeoTone.Paper,
                    onClick = { NotificationAccess.openAppInfo(context) },
                )
            }
        }
    }
}
