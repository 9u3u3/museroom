package com.museroom.app.ui.kit

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.museroom.app.ui.Mono
import com.museroom.app.ui.Neo
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

/**
 * The one gesture the whole style rests on: an object sits above a hard offset
 * shadow, and pressing it moves it down onto that shadow. No dimming, no ripple,
 * no blur. It reads as a physical thing being pushed.
 */
@Composable
private fun rememberPress(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return source to pressed
}

/** A hard offset shadow, drawn rather than elevated. */
fun Modifier.hardShadow(
    offset: Dp,
    color: Color,
    shape: Shape,
): Modifier = drawBehind {
    if (offset <= 0.dp) return@drawBehind
    val px = offset.toPx()
    val outline = shape.createOutline(
        Size(size.width, size.height),
        layoutDirection,
        this,
    )
    translate(px, px) { drawOutline(outline, color) }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.translate(
    dx: Float,
    dy: Float,
    block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    drawContext.transform.translate(dx, dy)
    block()
    drawContext.transform.translate(-dx, -dy)
}

// ------------------------------------------------------------- surfaces ----

@Composable
fun NeoCard(
    modifier: Modifier = Modifier,
    fill: Color = Neo.colors.card,
    stroke: Color = Neo.colors.ink,
    content: Color = Neo.colors.ink,
    radius: Dp = 16.dp,
    shadow: Dp = 5.dp,
    padding: Dp = 16.dp,
    body: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(color = content)) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .hardShadow(shadow, stroke, shape)
                .clip(shape)
                .background(fill)
                .border(3.dp, stroke, shape)
                .padding(padding),
            content = body,
        )
    }
}

/**
 * An accent-filled card. Its ink stays dark in both themes, because a lime panel
 * is light on either ground and cream text on lime is unreadable.
 */
@Composable
fun NeoAccentCard(
    fill: Color,
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    shadow: Dp = 5.dp,
    padding: Dp = 16.dp,
    body: @Composable ColumnScope.() -> Unit,
) = NeoCard(
    modifier = modifier,
    fill = fill,
    stroke = Neo.colors.onAccent,
    content = Neo.colors.onAccent,
    radius = radius,
    shadow = shadow,
    padding = padding,
    body = body,
)

// -------------------------------------------------------------- buttons ----

enum class NeoTone { Violet, Lime, Pink, Paper }

@Composable
fun NeoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: NeoTone = NeoTone.Violet,
    enabled: Boolean = true,
    small: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = Neo.colors
    val fill = when (tone) {
        NeoTone.Violet -> c.violet
        NeoTone.Lime -> c.lime
        NeoTone.Pink -> c.pink
        NeoTone.Paper -> c.card
    }
    val onFill = when (tone) {
        NeoTone.Violet -> Color.White
        NeoTone.Lime, NeoTone.Pink -> c.onAccent
        NeoTone.Paper -> c.ink
    }
    val edge = if (tone == NeoTone.Lime || tone == NeoTone.Pink) c.onAccent else c.ink

    val (source, pressed) = rememberPress()
    val rest = if (small) 3.dp else 5.dp
    val drop by animateDpAsState(if (pressed) rest else 0.dp, tween(90), label = "drop")
    val shadow by animateDpAsState(if (pressed) 0.dp else rest, tween(90), label = "shadow")
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = if (small) 42.dp else 54.dp)
            .offset(x = drop, y = drop)
            .hardShadow(shadow, edge, shape)
            .clip(shape)
            .background(if (enabled) fill else fill.copy(alpha = 0.45f))
            .border(3.dp, edge.copy(alpha = if (enabled) 1f else 0.45f), shape)
            .then(
                if (enabled) {
                    Modifier.pressable(source, onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = if (small) 14.dp else 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            leading?.invoke()
            Text(
                text = text.uppercase(),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = com.museroom.app.ui.Archivo,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.W900,
                    fontSize = if (small) 12.sp else 14.sp,
                    letterSpacing = 1.1.sp,
                    color = if (enabled) onFill else onFill.copy(alpha = 0.6f),
                ),
            )
        }
    }
}

/** No ripple: the press is the movement onto the shadow, not a wash of colour. */
private fun Modifier.pressable(
    source: MutableInteractionSource,
    onClick: () -> Unit,
) = clickable(interactionSource = source, indication = null, onClick = onClick)

// ----------------------------------------------------------------- bits ----

@Composable
fun NeoPill(
    text: String,
    modifier: Modifier = Modifier,
    fill: Color = Neo.colors.card,
    accent: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = Neo.colors
    val edge = if (accent) c.onAccent else c.ink
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(fill)
            .border(2.5.dp, edge, RoundedCornerShape(percent = 50))
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(
            text = text.uppercase(),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontFamily = com.museroom.app.ui.Archivo,
                fontWeight = androidx.compose.ui.text.font.FontWeight.W900,
                fontSize = 10.sp,
                letterSpacing = 1.3.sp,
                color = edge,
            ),
        )
    }
}

@Composable
fun NeoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Neo.colors
    val shape = RoundedCornerShape(percent = 50)
    val knob by animateDpAsState(
        if (checked) 28.dp else 0.dp,
        spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "knob",
    )
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(width = 62.dp, height = 34.dp)
            .hardShadow(3.dp, c.onAccent, shape)
            .clip(shape)
            .background(if (checked) c.lime else Color.White)
            .border(3.dp, c.onAccent, shape)
            .pressable(source) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 2.dp)
                .offset(x = knob)
                .size(24.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (checked) Color.White else Color(0xFFFBF6EA))
                .border(2.5.dp, c.onAccent, RoundedCornerShape(percent = 50)),
        )
    }
}

/** The chunky level bar: a rail, a fill, and a hard edge between them. */
@Composable
fun NeoProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    fill: Color = Neo.colors.violet,
) {
    val c = Neo.colors
    val shape = RoundedCornerShape(percent = 50)
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400), label = "progress")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .size(height = 16.dp, width = 0.dp)
            .hardShadow(3.dp, c.ink, shape)
            .clip(shape)
            .background(c.card)
            .border(3.dp, c.ink, shape),
    ) {
        Box(
            Modifier
                .fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .fillMaxSize()
                    .background(fill),
            )
        }
    }
}

/**
 * Ben-Day dots over the whole page. Drawn as points rather than a bitmap so it
 * costs nothing and follows the ink colour into dark.
 */
fun Modifier.halftone(color: Color, alpha: Float = 0.07f, step: Dp = 9.dp): Modifier =
    drawBehind {
        val s = step.toPx()
        val cols = ceil(size.width / s).toInt() + 1
        val rows = ceil(size.height / s).toInt() + 1
        val points = ArrayList<Offset>(cols * rows)
        for (y in 0 until rows) {
            for (x in 0 until cols) points.add(Offset(x * s, y * s))
        }
        drawPoints(
            points = points,
            pointMode = PointMode.Points,
            color = color.copy(alpha = alpha),
            strokeWidth = 2.4f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }

@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 12,
    color: Color = Neo.colors.ink,
) = Text(
    text = text,
    modifier = modifier,
    style = TextStyle(fontFamily = Mono, fontSize = size.sp, color = color, letterSpacing = 0.4.sp),
)

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Neo.colors.ink) = Text(
    text = text.uppercase(),
    modifier = modifier,
    style = TextStyle(
        fontFamily = com.museroom.app.ui.Archivo,
        fontWeight = androidx.compose.ui.text.font.FontWeight.W900,
        fontSize = 10.sp,
        letterSpacing = 1.7.sp,
        color = color.copy(alpha = 0.62f),
    ),
)
