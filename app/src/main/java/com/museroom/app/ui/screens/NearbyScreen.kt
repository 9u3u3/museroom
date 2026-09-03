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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.museroom.app.net.AuthRepository
import com.museroom.app.proximity.ProximityManager
import com.museroom.app.proximity.ProximityStatus
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

        Radar(active = wanted, blips = nearby.size)

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

/** A sweeping dial, because "listening" needs to look like something. */
@Composable
private fun Radar(active: Boolean, blips: Int) {
    val c = Neo.colors
    val spin = rememberInfiniteTransition(label = "radar")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "sweep",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .size(200.dp)
            .clip(CircleShape)
            .background(c.card)
            .border(3.dp, c.ink, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            listOf(0.32f, 0.62f, 0.92f).forEach { ring ->
                drawCircle(c.ink.copy(alpha = 0.3f), r * ring, centre, style = Stroke(width = 4f))
            }
            if (active) {
                rotate(angle, centre) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to c.violet.copy(alpha = 0.55f),
                            0.22f to c.violet.copy(alpha = 0f),
                            1f to c.violet.copy(alpha = 0f),
                            center = centre,
                        ),
                        startAngle = 0f, sweepAngle = 360f, useCenter = true,
                    )
                }
            }
            repeat(blips.coerceAtMost(4)) { i ->
                val a = Math.toRadians((40.0 + i * 78.0))
                val d = r * (0.42f + 0.16f * i)
                drawCircle(
                    c.pink,
                    9f,
                    Offset(centre.x + (d * kotlin.math.cos(a)).toFloat(), centre.y + (d * kotlin.math.sin(a)).toFloat()),
                )
            }
        }
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.rotate(
    degrees: Float,
    pivot: Offset,
    block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    drawContext.transform.rotate(degrees, pivot)
    block()
    drawContext.transform.rotate(-degrees, pivot)
}
