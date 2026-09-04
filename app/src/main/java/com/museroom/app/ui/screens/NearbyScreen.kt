package com.museroom.app.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.museroom.app.net.NearbyListener
import com.museroom.app.proximity.ProximityManager
import com.museroom.app.proximity.ProximityStatus
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import com.museroom.app.ui.bangers
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.NeoAccentCard
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoSwitch
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI

@Composable
fun NearbyScreen() {
    val context = LocalContext.current
    val c = Neo.colors
    val auth = remember { AuthRepository.get(context) }
    val manager = remember { ProximityManager.get(context) }
    val session by auth.session.collectAsStateWithLifecycle()
    val status by manager.state.collectAsStateWithLifecycle()
    val nearby by manager.nearby.collectAsStateWithLifecycle()

    // The switch reads the real state rather than remembering what was asked
    // for. Bluetooth being off, or a permission declined, used to leave it
    // sitting on "Broadcasting" while nothing was being broadcast.
    // Only actually searching counts as on. Bluetooth switched off, or a
    // permission declined, used to leave the switch sitting on "Broadcasting"
    // while nothing was being broadcast, which is the one thing it must never
    // say.
    val running = status is ProximityStatus.Searching || status is ProximityStatus.PausedForPrivacy
    var wanted by remember(running) { mutableStateOf(running) }
    var selected by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> if (granted.values.all { it }) manager.start() else wanted = false }

    // Turning the radio on is part of turning this on. Being told "turn
    // Bluetooth on" and left to find Settings is a step nobody should have to
    // take, so the system's own dialog is raised instead.
    val turnOnBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) manager.start() else wanted = false
    }
    var radioNote by remember { mutableStateOf<String?>(null) }

    /**
     * Ask for the radio, and survive being refused.
     *
     * From Android 12 this dialog needs the connect permission, and without it
     * the platform does not decline — it throws, which took the whole app down
     * the moment somebody with Bluetooth off tapped the switch. The permission
     * is asked for now, and this can no longer be the thing that crashes:
     * anything unexpected falls back to the Bluetooth settings screen.
     */
    fun askForBluetooth() {
        runCatching { turnOnBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            .onFailure {
                wanted = false
                radioNote = "Museroom could not open the Bluetooth switch. Turn Bluetooth " +
                    "on and try again."
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }

    fun goOnAir() {
        radioNote = null
        when {
            !manager.hasPermissions() ->
                runCatching { ask.launch(manager.requiredPermissions()) }
                    .onFailure { wanted = false }
            !manager.bluetoothReady() -> askForBluetooth()
            else -> manager.start()
        }
    }

    // A permission granted is only half of it: the radio may still be off.
    LaunchedEffect(status) {
        if (wanted && status is ProximityStatus.BluetoothOff) askForBluetooth()
    }

    // Both radios run flat out only while this screen is the one being looked
    // at. Somebody waiting for a friend's name to appear is exactly who the
    // battery is worth spending on, and nobody else is waiting for anything.
    DisposableEffect(Unit) {
        manager.setForeground(true)
        onDispose { manager.setForeground(false) }
    }

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
            SignInPanel("Sign in so a code in the air can become a person.")
            return@Column
        }

        // Never yourself, whatever the radio thinks it heard. Some Android
        // radios report their own advertisement back as a sighting, and the
        // server's guard cannot help when the token really does belong to the
        // person asking.
        val me = session?.userId
        val others = nearby.filterNot { it.userId == me }

        Radar(
            active = status is ProximityStatus.Searching,
            people = others,
            selected = selected,
            onPick = { selected = if (selected == it) null else it },
        )

        NeoAccentCard(fill = c.violet, radius = 18.dp, shadow = 6.dp, padding = 16.dp) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "On the air",
                    style = MaterialTheme.typography.titleLarge,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                NeoSwitch(checked = wanted, onCheckedChange = { on ->
                    wanted = on
                    if (on) goOnAir() else manager.stop()
                })
            }
        }

        // One line, and only when there is something to do about it. A person
        // does not need to know whether the radio is advertising; they need to
        // know why nobody is showing up.
        val trouble = radioNote ?: when (val s = status) {
            is ProximityStatus.NeedsPermission -> "Bluetooth permission is needed."
            is ProximityStatus.BluetoothOff -> "Bluetooth is off."
            is ProximityStatus.PausedForPrivacy -> "Paused while your session is private."
            is ProximityStatus.Failed -> s.reason
            is ProximityStatus.Searching ->
                if (others.isEmpty()) "Nobody nearby yet. They need Museroom open too." else null
            is ProximityStatus.Off -> null
        }
        trouble?.let {
            NeoCard(radius = 14.dp, shadow = 3.dp, padding = 14.dp) { Note(it) }
        }

        val shown = others.filter { selected == null || it.userId == selected }
        shown.forEach { person ->
            ListenerRow(
                handle = person.handle,
                title = person.title,
                artist = person.artist,
                durationMs = person.durationMs,
                positionMs = person.positionMs,
                isPlaying = person.isPlaying,
                updatedAt = person.updatedAt,
                sourceTrackId = person.sourceTrackId,
                hostId = person.userId,
                fingerprint = person.title,
                avatarUrl = person.avatarUrl,
                openToAll = person.openToAll,
                tint = c.pink,
            )
        }
    }
}

/**
 * Rings pushing outward, with the people in them.
 *
 * A number in a circle told you how many were around and nothing about who. The
 * faces sit on the ring, evenly spaced, and tapping one narrows the list below
 * to that person; tapping again lets everybody back in. Three hard rings
 * expanding and fading is the same idea drawn in the same ink as everything
 * around it, and it reads as broadcasting rather than as radar.
 */
@Composable
private fun Radar(
    active: Boolean,
    people: List<NearbyListener>,
    selected: String?,
    onPick: (String) -> Unit,
) {
    val c = Neo.colors
    val beat = rememberInfiniteTransition(label = "pulse")
    val phase by beat.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "phase",
    )

    val ring = 108.dp
    Box(
        Modifier
            .fillMaxWidth()
            .height(ring * 2 + 76.dp),
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
                if (active) "${people.size}" else "off",
                style = bangers(if (active) 34 else 20).copy(
                    color = if (active) Color.White else c.ink,
                ),
            )
        }

        people.take(8).forEachIndexed { index, person ->
            val turn = (index.toFloat() / people.take(8).size.coerceAtLeast(1)) * 2f * PI - PI / 2
            val chosen = selected == person.userId
            Box(
                Modifier
                    .offset(
                        x = (cos(turn).toFloat() * ring.value).dp,
                        y = (sin(turn).toFloat() * ring.value).dp,
                    )
                    .clickable { onPick(person.userId) },
            ) {
                Face(
                    person.handle,
                    person.avatarUrl,
                    if (chosen) 56.dp else 46.dp,
                    border = if (chosen) c.pink else c.onAccent,
                )
            }
        }
    }
}
