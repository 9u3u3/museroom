package com.museroom.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.museroom.app.ui.Neo
import com.museroom.app.ui.kit.NeoIcon
import com.museroom.app.ui.kit.hardShadow

/**
 * A round button with an icon in it, which the player surfaces are made of.
 *
 * It presses the same way everything else in the kit does: down onto its own
 * shadow, never a ripple and never a dim. The whole style rests on objects
 * behaving like objects.
 */
@Composable
fun RoundIcon(
    path: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 46.dp,
    icon: Dp = 20.dp,
    fill: Color = Neo.colors.card,
    stroke: Color = Neo.colors.ink,
    content: Color = Neo.colors.ink,
    rest: Dp = 3.dp,
    weight: Float = 2.8f,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val drop by animateFloatAsState(if (pressed) 1f else 0f, tween(90), label = "press")
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier
            // The description was being taken and dropped. Every round button
            // in the app is an icon with no text, so without this a screen
            // reader is handed a page of unlabelled circles.
            .semantics { this.contentDescription = contentDescription }
            .size(diameter)
            .offset(x = rest * drop, y = rest * drop)
            .hardShadow(rest * (1f - drop), stroke, shape)
            .clip(shape)
            .background(fill)
            .border(3.dp, stroke, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NeoIcon(path, size = icon, color = content, weight = weight)
    }
}

/**
 * Three bars keeping time.
 *
 * The only decoration in the app that moves on its own, and it earns it: it is
 * how a row says "this one" without a second colour or a label. It animates
 * whether or not sound is actually coming out, because it marks the track the
 * player is on rather than the state of the speaker.
 */
@Composable
fun Bars(
    modifier: Modifier = Modifier,
    color: Color = Neo.colors.violet,
    height: Dp = 18.dp,
) {
    val beat = rememberInfiniteTransition(label = "bars")
    Row(
        modifier.height(height),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(0 to 0.6f, 160 to 1f, 320 to 0.45f).forEach { (offset, tall) ->
            val scale by beat.animateFloat(
                initialValue = 0.35f,
                targetValue = tall,
                animationSpec = infiniteRepeatable(
                    tween(520, delayMillis = offset),
                    RepeatMode.Reverse,
                ),
                label = "bar",
            )
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight(scale)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}

/**
 * Slides and fades in, staggered by position, then never runs again.
 *
 * The stagger is capped: after eight rows the delay stops growing, because a
 * list of forty results should not take two seconds to finish arriving.
 */
fun Modifier.riseIn(index: Int): Modifier = composed {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        if (shown) 1f else 0f,
        tween(durationMillis = 320, delayMillis = index.coerceAtMost(8) * 45),
        label = "rise",
    )
    this
        .alpha(progress)
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, ((1f - progress) * 22.dp.roundToPx()).toInt())
            }
        }
}

/** Springs once when the value it is given changes. Used on the play button. */
@Composable
fun rememberPop(key: Any?): Float {
    var pop by remember { mutableStateOf(1f) }
    val scale by animateFloatAsState(pop, tween(160), label = "pop")
    LaunchedEffect(key) {
        pop = 0.88f
        pop = 1f
    }
    return scale
}

/** m:ss, or h:mm:ss when a track is long enough to need it. */
fun clockOf(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
