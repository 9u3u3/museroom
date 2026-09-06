package com.museroom.app.ui.kit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.museroom.app.ui.Archivo
import com.museroom.app.ui.Neo
import com.museroom.app.ui.bangers

/**
 * The pieces the design repeats on nearly every screen.
 *
 * They live here rather than being redrawn per screen because the whole point
 * of the kit is that a chip on Search and a chip on Library are the same
 * object. When one of them drifts, the page stops reading as one design.
 */

/** No ripple anywhere: the movement onto the shadow is the feedback. */
@Composable
internal fun Modifier.press(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)

/**
 * A word set in the display face over a hard coloured copy of itself.
 *
 * Compose's own text shadow is a blur, and a blur under a Bangers headline in a
 * kit built on flat offsets reads as a different app. So the word is drawn
 * twice: the accent first, pushed down and right by the offset, then the ink
 * over it.
 */
@Composable
fun DropTitle(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 34,
    drop: Color = Neo.colors.violet,
    ink: Color = Neo.colors.ink,
    offset: Dp = 4.dp,
) {
    val style = bangers(size)
    Box(modifier) {
        Text(
            text.uppercase(),
            style = style.copy(color = drop),
            modifier = Modifier.offset(x = offset, y = offset),
        )
        Text(text.uppercase(), style = style.copy(color = ink))
    }
}

/**
 * A small round icon button, the one the top bars and the transports are made
 * of. It presses down onto its own shadow like everything else.
 */
@Composable
fun NeoRound(
    path: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 42.dp,
    icon: Dp = 20.dp,
    fill: Color = Neo.colors.card,
    stroke: Color = Neo.colors.ink,
    content: Color = Neo.colors.ink,
    rest: Dp = 3.dp,
    weight: Float = 2.8f,
    iconFill: Color? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val drop by animateFloatAsState(if (pressed) 1f else 0f, tween(90), label = "press")
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier
            .size(diameter)
            .offset(x = rest * drop, y = rest * drop)
            .hardShadow(rest * (1f - drop), stroke, shape)
            .clip(shape)
            .background(fill)
            .border(3.dp, stroke, shape)
            .clickable(
                interactionSource = source,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        NeoIcon(path, size = icon, color = content, weight = weight, fill = iconFill)
    }
}

/**
 * A filter chip. Selected is the ink itself rather than an accent, so a row of
 * them has exactly one loud thing in it.
 */
@Composable
fun NeoChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caps: Boolean = true,
) {
    val c = Neo.colors
    val shape = RoundedCornerShape(percent = 50)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val drop by animateDpAsState(if (pressed) 2.dp else 0.dp, tween(90), label = "chipDrop")
    val shadow by animateDpAsState(if (pressed) 1.dp else 3.dp, tween(90), label = "chipShadow")

    Box(
        modifier
            .height(34.dp)
            .offset(x = drop, y = drop)
            .hardShadow(shadow, c.ink, shape)
            .clip(shape)
            .background(if (selected) c.ink else c.card)
            .border(2.5.dp, c.ink, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (caps) text.uppercase() else text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontFamily = Archivo,
                fontWeight = FontWeight.W900,
                fontSize = 11.sp,
                letterSpacing = if (caps) 1.1.sp else 0.2.sp,
                color = if (selected) c.paper else c.ink,
            ),
        )
    }
}

/**
 * The two-or-three-way switch: one pill, one lime thumb, no gaps.
 *
 * Used wherever the choice is between a fixed handful of things that are the
 * same kind of thing — a room's mode, a streaming quality, a board's period.
 */
@Composable
fun NeoSegment(
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 42.dp,
    fill: Color = Neo.colors.lime,
) {
    val c = Neo.colors
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(c.card)
            .border(3.dp, c.ink, shape),
    ) {
        options.forEachIndexed { index, option ->
            val on = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (on) fill else Color.Transparent)
                    .press { onPick(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.uppercase(),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = Archivo,
                        fontWeight = FontWeight.W900,
                        fontSize = 11.sp,
                        letterSpacing = 1.2.sp,
                        color = if (on) c.onAccent else c.ink,
                    ),
                )
            }
        }
    }
}

/**
 * The squarer sibling: separate buttons rather than one pill, for choices that
 * are settings rather than modes. Selected is the ink, as on the chips.
 */
@Composable
fun NeoTabs(
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    radius: Dp = 12.dp,
) {
    val c = Neo.colors
    val shape = RoundedCornerShape(radius)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEachIndexed { index, option ->
            val on = index == selected
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val drop by animateDpAsState(if (pressed) 2.dp else 0.dp, tween(90), label = "tabDrop")
            val shadow by animateDpAsState(if (pressed) 1.dp else 3.dp, tween(90), label = "tabShadow")
            Box(
                Modifier
                    .weight(1f)
                    .height(height)
                    .offset(x = drop, y = drop)
                    .hardShadow(shadow, c.ink, shape)
                    .clip(shape)
                    .background(if (on) c.ink else c.card)
                    .border(3.dp, c.ink, shape)
                    .clickable(interactionSource = source, indication = null) { onPick(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.uppercase(),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = Archivo,
                        fontWeight = FontWeight.W900,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = if (on) c.paper else c.ink,
                    ),
                )
            }
        }
    }
}

/**
 * A section heading with the one thing you can do to the whole section.
 *
 * The heading is the display face because a page of Archivo headings has
 * nothing to hang the eye on, and the action is a quiet uppercase label because
 * it is a way out of the section rather than the point of it.
 */
@Composable
fun Shelf(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Neo.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title.uppercase(), style = bangers(22).copy(color = c.ink))
        if (action != null && onAction != null) {
            Text(
                action.uppercase(),
                style = TextStyle(
                    fontFamily = Archivo,
                    fontWeight = FontWeight.W900,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    color = c.ink.copy(alpha = 0.55f),
                ),
                modifier = Modifier.padding(bottom = 3.dp).press(onAction),
            )
        }
    }
}

/** The small caps kicker that sits over a value. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = Neo.colors.ink) = Text(
    text.uppercase(),
    modifier = modifier,
    style = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.W900,
        fontSize = 9.sp,
        letterSpacing = 1.5.sp,
        color = color.copy(alpha = 0.62f),
    ),
)

/**
 * The compact switch used inside settings rows, where the big one would be the
 * loudest thing on a list of eight quiet lines.
 */
@Composable
fun SmallSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Neo.colors
    val shape = RoundedCornerShape(percent = 50)
    val knob by animateDpAsState(
        if (checked) 22.dp else 0.dp,
        spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "smallKnob",
    )
    Box(
        modifier
            .size(width = 50.dp, height = 28.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .hardShadow(2.dp, c.ink, shape)
            .clip(shape)
            .background(if (checked) c.lime else c.card)
            .border(3.dp, c.ink, shape)
            .then(if (enabled) Modifier.press { onCheckedChange(!checked) } else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(start = 2.dp)
                .offset(x = knob)
                .size(18.dp)
                .clip(shape)
                .background(if (checked) c.onAccent else c.ink),
        )
    }
}

/**
 * A hard-edged level bar with no shadow, for the places a bar is a readout
 * rather than a control: a friend's position, a room's progress, a lyric's
 * place in a song.
 */
@Composable
fun NeoBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 11.dp,
    fill: Color = Neo.colors.violet,
    track: Color = Neo.colors.card,
    stroke: Color = Neo.colors.ink,
    animated: Boolean = true,
) {
    val shape = RoundedCornerShape(percent = 50)
    val target = fraction.coerceIn(0f, 1f)
    val f by animateFloatAsState(target, tween(if (animated) 420 else 0), label = "bar")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(track)
            .border(2.5.dp, stroke, shape),
    ) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(fill))
    }
}

/**
 * A sheet: the design's one way of putting a decision in front of somebody.
 *
 * It rises from the bottom edge, sits on its own shadow, and dims what is
 * behind it with the same Ben-Day dots the paper has, so the dimmed page still
 * reads as this app rather than as a grey rectangle.
 */
@Composable
fun NeoSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit,
) {
    val c = Neo.colors
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C14110D))
            .halftone(c.paper, alpha = 0.14f, step = 10.dp)
            .press(onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
                .fillMaxWidth()
                .hardShadow(8.dp, c.ink, shape)
                .clip(shape)
                .background(c.card)
                .border(3.dp, c.ink, shape)
                // Swallows the tap so pressing the sheet does not dismiss it.
                .press {}
                .padding(20.dp),
            content = body,
        )
    }
}

/** The sheet's own heading, in the display face. */
@Composable
fun SheetTitle(text: String) = Text(
    text.uppercase(),
    style = bangers(27).copy(color = Neo.colors.ink),
)

/**
 * One choice on a sheet: a colour swatch and a word, on a paper tile.
 */
@Composable
fun SheetOption(
    text: String,
    modifier: Modifier = Modifier,
    swatch: Color? = null,
    onClick: () -> Unit,
) {
    val c = Neo.colors
    val shape = RoundedCornerShape(14.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val drop by animateDpAsState(if (pressed) 2.dp else 0.dp, tween(90), label = "optDrop")
    val shadow by animateDpAsState(if (pressed) 1.dp else 3.dp, tween(90), label = "optShadow")
    Row(
        modifier
            .fillMaxWidth()
            .offset(x = drop, y = drop)
            .hardShadow(shadow, c.ink, shape)
            .clip(shape)
            .background(c.paper)
            .border(3.dp, c.ink, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (swatch != null) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(swatch)
                    .border(2.5.dp, c.ink, RoundedCornerShape(9.dp)),
            )
        }
        Text(
            text,
            style = TextStyle(
                fontFamily = Archivo,
                fontWeight = FontWeight.W900,
                fontSize = 15.sp,
                color = c.ink,
            ),
        )
    }
}

/**
 * The pink dot that means something is happening right now.
 *
 * It beats, which is the only decoration in the kit allowed to move on its own,
 * because "live" is a claim about this second rather than a label.
 */
@Composable
fun LiveDot(modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val c = Neo.colors
    val beat = rememberInfiniteTransition(label = "live")
    val pulse by beat.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "beat",
    )
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .size(size)
            .scale(pulse)
            .clip(shape)
            .background(c.pink)
            .border(2.dp, c.onAccent, shape),
    )
}
