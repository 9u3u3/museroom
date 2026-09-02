package com.museroom.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.net.AuthRepository
import com.museroom.app.proximity.ProximityManager
import com.museroom.app.proximity.ProximityStatus
import com.museroom.app.util.formatAgo

/**
 * People in the room.
 *
 * Off by default, and honest about what switching it on means. It says the phone
 * broadcasts, because that is what the user is agreeing to, and hiding it behind
 * "discover people nearby" would not be.
 */
@Composable
fun ProximityCard() {
    val context = LocalContext.current
    val auth = remember { AuthRepository.get(context) }
    val manager = remember { ProximityManager.get(context) }

    val session by auth.session.collectAsStateWithLifecycle()
    if (session == null) return

    val status by manager.state.collectAsStateWithLifecycle()
    val nearby by manager.nearby.collectAsStateWithLifecycle()
    val diag by manager.diagnostics.collectAsStateWithLifecycle()
    var wanted by remember { mutableStateOf(status != ProximityStatus.Off) }

    val askPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) manager.start() else wanted = false
    }

    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "People nearby",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = "Your phone broadcasts a short code over Bluetooth that " +
                        "changes every fifteen minutes. It is not your name and cannot " +
                        "be traced back to you by anyone but Museroom.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = wanted,
                onCheckedChange = { on ->
                    wanted = on
                    if (!on) {
                        manager.stop()
                    } else if (manager.hasPermissions()) {
                        manager.start()
                    } else {
                        askPermissions.launch(manager.requiredPermissions())
                    }
                },
            )
        }

        if (!wanted) return@Panel

        Spacer(Modifier.size(12.dp))
        Small(
            when (val s = status) {
                is ProximityStatus.Off -> "Off"
                is ProximityStatus.NeedsPermission -> "Bluetooth permission is needed"
                is ProximityStatus.BluetoothOff -> "Turn Bluetooth on to find anyone"
                is ProximityStatus.PausedForPrivacy -> "Paused: your session is private"
                is ProximityStatus.Searching ->
                    if (nearby.isEmpty()) "Listening for people around you" else "${nearby.size} nearby"
                is ProximityStatus.Failed -> s.reason
            },
        )

        // Six different things can produce an empty list, and they want different
        // answers. Say which one it is rather than leaving the user guessing.
        Spacer(Modifier.size(10.dp))
        Small(if (diag.advertising) "broadcasting: yes" else "broadcasting: no")
        Small(if (diag.scanning) "listening: yes" else "listening: no")
        Small(
            "beacons heard: ${diag.beaconsHeard}" +
                if (diag.lastHeardAtMs > 0) "  (${formatAgo(diag.lastHeardAtMs)})" else "",
        )
        Small(
            when {
                diag.lastResolveError != null -> "lookup failed: ${diag.lastResolveError}"
                diag.lastResolveAtMs == 0L -> "lookup: not run yet"
                else -> "lookup: ${diag.lastResolveCount} matched, ${formatAgo(diag.lastResolveAtMs)}"
            },
        )

        if (diag.beaconsHeard > 0 && nearby.isEmpty() && diag.lastResolveAtMs > 0) {
            Spacer(Modifier.size(8.dp))
            Small(
                "A phone is in range but not showing. They need to be signed in, " +
                    "have this switched on, and be playing something right now.",
            )
        }

        if (nearby.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            nearby.forEach { person ->
                ListenerRow(
                    handle = person.handle,
                    title = person.title,
                    artist = person.artist,
                    durationMs = person.durationMs,
                    positionMs = person.positionMs,
                    isPlaying = person.isPlaying,
                    updatedAt = person.updatedAt,
                )
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun Small(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
