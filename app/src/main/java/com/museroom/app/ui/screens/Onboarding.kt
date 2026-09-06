package com.museroom.app.ui.screens

import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers
import com.museroom.app.ui.kit.MuseroomMark
import com.museroom.app.ui.kit.NeoButton
import com.museroom.app.ui.kit.NeoCard
import com.museroom.app.ui.kit.NeoIcon
import com.museroom.app.ui.kit.NeoIcons
import android.content.Context
import android.os.Build
import com.museroom.app.ui.kit.NeoTone
import com.museroom.app.util.NotificationAccess

/**
 * Whether somebody has said they would rather get on without the permission.
 *
 * It used to be that Museroom without notification access could do nothing at
 * all, so the gate was the app. Since the player landed, the opposite is true:
 * search, the library, downloads, playlists and rooms all work with the switch
 * off, and only reading what *other* apps play needs it. Holding a music player
 * shut behind a permission it does not need is how somebody who cannot get past
 * Android's restricted-setting dialog ends up with an app that does nothing.
 */
object AccessGate {

    private const val PREFS = "museroom.access"
    private const val KEY_SKIPPED = "skipped"

    fun skipped(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SKIPPED, false)

    fun skip(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SKIPPED, true).apply()
    }
}

/**
 * The permission gate. It explains before it asks, because dropping someone cold
 * into Android's notification-access list loses them, and because the promise
 * about what is read has to be made before the permission, not after.
 *
 * It asks rather than insists. [onSkip] is the way past it, and what is lost by
 * taking that way is said plainly rather than discovered later.
 */
@Composable
fun OnboardingScreen(onSkip: () -> Unit = {}) {
    val context = LocalContext.current
    val c = Neo.colors

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        // The one screen where the wordmark is allowed to be the largest thing
        // in the app, set on two lines and tipped off true, with the sticker
        // that says the thing everybody's first question is about.
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.rotate(-3f).padding(top = 18.dp)) {
                Text(
                    "MUSE\nROOM",
                    style = bangers(62).copy(color = c.violet),
                    modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                )
                Text("MUSE\nROOM", style = bangers(62).copy(color = c.ink))
            }
            Starburst(
                "NO\nPLAY STORE\nNEEDED",
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 30.dp),
            )
        }

        Spacer(Modifier.size(30.dp))

        // A speech bubble rather than a plain card, because this paragraph is
        // the app saying something to the person rather than a label on a
        // setting, and the tail is what makes the difference readable.
        NeoCard(radius = 20.dp, shadow = 6.dp, padding = 20.dp) {
            Text(
                "First, let it hear the music.",
                style = MaterialTheme.typography.titleLarge,
                color = c.ink,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "Android keeps a media session for whatever app is playing. Reading " +
                    "it is the only way to know what you are listening to.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink.copy(alpha = 0.8f),
            )
        }
        Tail()

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
            text = "Turn on notification access",
            onClick = { NotificationAccess.openSettings(context) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.size(10.dp))
        Text(
            "Takes you to Android settings. Come straight back.",
            style = MaterialTheme.typography.bodySmall,
            color = c.ink.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(10.dp))

        NeoButton(
            text = "Not now",
            tone = NeoTone.Paper,
            onClick = {
                AccessGate.skip(context)
                onSkip()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "Everything Museroom plays itself works without this. What needs it " +
                "is reading what your other music apps are playing, which is how " +
                "friends see it and how those minutes get counted. You can turn " +
                "it on later from the You tab.",
            style = MaterialTheme.typography.bodySmall,
            color = c.ink.copy(alpha = 0.7f),
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

/**
 * The comic sticker: a lime star with hard ink edges and a word inside it.
 *
 * It drifts, slowly, because the one thing on this screen that nobody asked to
 * read is the one that has to catch the eye on its own.
 */
@Composable
private fun Starburst(text: String, modifier: Modifier = Modifier) {
    val c = Neo.colors
    val float = rememberInfiniteTransition(label = "drift")
    val lift by float.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "lift",
    )
    val points = remember {
        PathParser().parsePathString(
            "M60 3 L69 27 L94 17 L88 43 L115 47 L96 63 L116 82 L90 85 L96 111 " +
                "L71 100 L60 118 L49 100 L24 111 L30 85 L4 82 L24 63 L5 47 " +
                "L32 43 L26 17 L51 27 Z",
        ).toPath()
    }
    Box(
        modifier
            .size(118.dp)
            .offset(y = lift.dp)
            .rotate(-3f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val scale = size.minDimension / 120f
            withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                drawPath(points, c.lime)
                drawPath(
                    points,
                    Color(0xFF14110D),
                    style = Stroke(width = 3f, join = StrokeJoin.Round),
                )
            }
        }
        Text(
            text,
            style = bangers(15).copy(color = Color(0xFF14110D)),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.rotate(9f),
        )
    }
}

/**
 * The bubble's tail, drawn under the card it belongs to.
 *
 * Two triangles rather than one: the ink first, then the card colour inset by
 * the stroke width, which is how the kit draws every other edge.
 */
@Composable
private fun Tail() {
    val c = Neo.colors
    Canvas(
        Modifier
            .padding(start = 34.dp)
            .size(width = 26.dp, height = 19.dp),
    ) {
        val ink = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width * 0.36f, size.height)
            close()
        }
        val fill = Path().apply {
            moveTo(4.dp.toPx(), 0f)
            lineTo(size.width - 4.dp.toPx(), 0f)
            lineTo(size.width * 0.36f, size.height - 6.dp.toPx())
            close()
        }
        drawPath(ink, c.ink)
        drawPath(fill, c.card)
    }
}
