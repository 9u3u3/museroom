package com.museroom.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.net.AuthRepository
import com.museroom.app.proximity.ProximityManager
import com.museroom.app.proximity.ProximityStatus
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import com.museroom.app.ui.bangers
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.Label
import com.museroom.app.ui.kit.MonoText
import com.museroom.app.ui.kit.NeoAccentCard
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoSwitch
import com.museroom.app.util.formatAgo

@Composable
fun NearbyScreen() {
    val context = LocalContext.current
    val c = Neo.colors
    val auth = remember { AuthRepository.get(context) }
    val manager = remember { ProximityManager.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()
    val status by manager.state.collectAsStateWithLifecycle()
    val nearby by manager.nearby.collectAsStateWithLifecycle()
    val diag by manager.diagnostics.collectAsStateWithLifecycle()

    var wanted by remember { mutableStateOf(status != ProximityStatus.Off) }
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> if (granted.values.all { it }) manager.start() else wanted = false }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Nearby", drop = c.pink)

        if (session == null) {
            SignInPanel("Nearby needs an account, so a code in the air can become a person.")
            return@Column
        }

        Pulse(active = wanted, blips = nearby.size)

        NeoAccentCard(fill = c.violet, radius = 18.dp, shadow = 6.dp, padding = 16.dp) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Broadcasting",
                        style = MaterialTheme.typography.titleMedium,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        "A code that changes every 15 minutes. Not your name, and only " +
                            "Museroom can tell whose it is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                    )
                }
                NeoSwitch(checked = wanted, onCheckedChange = { on ->
                    wanted = on
                    when {
                        !on -> manager.stop()
                        manager.hasPermissions() -> manager.start()
                        else -> ask.launch(manager.requiredPermissions())
                    }
                })
            }
        }

        if (wanted) {
            NeoCard(radius = 14.dp, shadow = 3.dp, padding = 14.dp) {
                Label("Self-check", color = c.ink)
                Spacer(Modifier.size(6.dp))
                MonoText(
                    when (val s = status) {
                        is ProximityStatus.Off -> "off"
                        is ProximityStatus.NeedsPermission -> "bluetooth permission needed"
                        is ProximityStatus.BluetoothOff -> "turn bluetooth on"
                        is ProximityStatus.PausedForPrivacy -> "paused: private session"
                        is ProximityStatus.Searching ->
                            if (nearby.isEmpty()) "listening" else "${nearby.size} nearby"
                        is ProximityStatus.Failed -> s.reason
                    },
                    size = 11, color = c.ink,
                )
                MonoText("broadcasting: ${if (diag.advertising) "yes" else "no"}", size = 11, color = c.ink)
                MonoText("listening: ${if (diag.scanning) "yes" else "no"}", size = 11, color = c.ink)
                MonoText(
                    "beacons heard: ${diag.beaconsHeard}" +
                        if (diag.lastHeardAtMs > 0) "  (${formatAgo(diag.lastHeardAtMs)})" else "",
                    size = 11, color = c.ink,
                )
                if (diag.beaconsHeard > 0 && nearby.isEmpty() && diag.lastResolveAtMs > 0) {
                    Spacer(Modifier.size(6.dp))
                    Note("A phone is in range but not showing. They need to be signed in, switched on, and playing something right now.")
                }
            }
        }

        nearby.forEach { person ->
            ListenerRow(
                handle = person.handle,
                title = person.title,
                artist = person.artist,
                durationMs = person.durationMs,
                positionMs = person.positionMs,
                isPlaying = person.isPlaying,
                updatedAt = person.updatedAt,
                sourceTrackId = person.sourceTrackId,
                tint = c.pink,
            )
        }
    }
}

/**
 * Rings pushing outward, not a sweeping dial.
 *
 * The sweep was a gradient smeared over a circle, which is exactly the sort of
 * soft effect this design has none of anywhere else. Three hard rings expanding
 * and fading is the same idea drawn in the same ink as everything around it, and
 * it reads as broadcasting rather than as radar.
 */
@Composable
private fun Pulse(active: Boolean, blips: Int) {
    val c = Neo.colors
    val beat = rememberInfiniteTransition(label = "pulse")
    val phase by beat.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "phase",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(168.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.minDimension / 2f - 8.dp.toPx()
            if (active) {
                repeat(3) { i ->
                    val t = (phase + i / 3f) % 1f
                    drawCircle(
                        color = c.violet.copy(alpha = (1f - t) * 0.9f),
                        radius = 26.dp.toPx() + t * (maxR - 26.dp.toPx()),
                        center = centre,
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
        }

        Box(
            Modifier
                .size(74.dp)
                .clip(CircleShape)
                .background(if (active) c.violet else c.card)
                .border(3.dp, c.onAccent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (active) "$blips" else "off",
                style = bangers(if (active) 34 else 20).copy(
                    color = if (active) Color.White else c.ink,
                ),
            )
        }
    }
}
