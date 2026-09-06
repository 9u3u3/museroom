package com.museroom.app.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.museroom.app.ui.Neo

/**
 * Icons as path data on a 24 grid, drawn rather than imported.
 *
 * They take their colour from whatever they sit on, so one drawing works on a
 * dark card and on a lime sticker without a second copy of the file.
 */
object NeoIcons {
    const val Now = "M4 14v-4M8.5 18V6M13 15V9M17.5 20V4M21.5 13v-2"
    const val Library = "M3.2 5.9a1.4 1.4 0 0 1 1.4-1.4h1.2a1.4 1.4 0 0 1 1.4 1.4v12.2a1.4 1.4 0 0 1-1.4 1.4H4.6a1.4 1.4 0 0 1-1.4-1.4z" +
        "M9 5.9a1.4 1.4 0 0 1 1.4-1.4h1.2A1.4 1.4 0 0 1 13 5.9v12.2a1.4 1.4 0 0 1-1.4 1.4h-1.2A1.4 1.4 0 0 1 9 18.1z" +
        "M15.6 6.2 19.4 5l2.1 13.4-3.8 1.2z"
    const val Home = "M3.5 10.5 12 3.5l8.5 7M5.5 9.6V20h13V9.6M9.8 20v-5.4h4.4V20"
    const val Tally = "M4.5 20V9M9.5 20V4.5M14.5 20v-8M19.5 20v-4.5M3 20h18"
    const val Friends =
        "M9 4.6a3.4 3.4 0 1 1 0 6.8 3.4 3.4 0 0 1 0-6.8M2.5 20c0-3.3 2.9-5.4 6.5-5.4s6.5 2.1 6.5 5.4" +
            "M16.5 5.2a3.4 3.4 0 0 1 0 6.4M18 14.9c2.1.6 3.5 2.3 3.5 5.1"
    const val Nearby =
        "M12 9.8a2.2 2.2 0 1 1 0 4.4 2.2 2.2 0 0 1 0-4.4M7.8 7.8a6 6 0 0 0 0 8.4" +
            "M16.2 16.2a6 6 0 0 0 0-8.4M4.6 4.6a10.4 10.4 0 0 0 0 14.8M19.4 19.4a10.4 10.4 0 0 0 0-14.8"
    const val Board = "M7 4h10v5a5 5 0 0 1-10 0zM7 5.5H4.2v1.6A3.4 3.4 0 0 0 7 10.4" +
        "M17 5.5h2.8v1.6a3.4 3.4 0 0 1-2.8 3.3M12 14v3.5M8.5 20.5h7"
    const val You = "M12 4.4a3.6 3.6 0 1 1 0 7.2 3.6 3.6 0 0 1 0-7.2M4.5 20.5c0-3.9 3.4-6.3 7.5-6.3s7.5 2.4 7.5 6.3"
    const val Check = "M20 6 9 17l-5-5"
    const val Close = "M6 6l12 12M18 6 6 18"
    const val Plus = "M5 12h14M12 5v14"
    const val Trash = "M6.5 5.5h11l-1 14h-9zM9.5 5.5V3.6h5v1.9M10.5 9.5v6M13.5 9.5v6"
    const val Lock = "M4 12.6a2.6 2.6 0 0 1 2.6-2.6h10.8a2.6 2.6 0 0 1 2.6 2.6v5.3a2.6 2.6 0 0 1-2.6 2.6H6.6A2.6 2.6 0 0 1 4 17.9zM8 10V7.2a4 4 0 0 1 8 0V10"
    const val Search = "M11 4.5a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13M15.8 15.8 20 20"

    // Transport. Drawn as outlines like everything else in the kit, because a
    // solid glyph beside a three-pixel stroke reads as a different app.
    const val Play = "M7.5 5.2 18.5 12 7.5 18.8z"
    const val Pause = "M8.6 4.8v14.4M15.4 4.8v14.4"
    const val Next = "M6.5 5.2 15.5 12l-9 6.8zM18.5 5v14"
    const val Previous = "M17.5 5.2 8.5 12l9 6.8zM5.5 5v14"
    const val Shuffle = "M3.5 6.5h3.6c3.4 0 3.4 11 6.8 11h4.4M3.5 17.5h3.6c3.4 0 3.4-11 6.8-11h4.4" +
        "M16.6 3.6l2.9 2.9-2.9 2.9M16.6 14.6l2.9 2.9-2.9 2.9"
    const val Repeat = "M7 5.5h9a4 4 0 0 1 4 4v1M17 18.5H8a4 4 0 0 1-4-4v-1" +
        "M9.6 2.9 6.8 5.5l2.8 2.6M14.4 21.1l2.8-2.6-2.8-2.6"
    const val Chevron = "M6 9.5 12 15.5 18 9.5"
    const val Back = "M15 4.5 7.5 12l7.5 7.5"
    const val Dots = "M12 5.6v.1M12 12v.1M12 18.4v.1"
    const val Queue = "M4 6h12M4 12h12M4 18h8M17.5 15l3 3-3 3"

    /**
     * A tray: a box with a slot cut out of the top edge, which is the shape
     * that reads as "things are waiting in here" at 24 pixels without needing
     * an envelope's diagonals to survive the same stroke width as everything
     * else on the bar.
     */
    const val Requests = "M3.5 13.5h5l1.2 2.2h4.6l1.2-2.2h5" +
        "M3.5 13.5 6.2 5.2a1.6 1.6 0 0 1 1.5-1.1h8.6a1.6 1.6 0 0 1 1.5 1.1l2.7 8.3" +
        "v4.7a1.8 1.8 0 0 1-1.8 1.8H5.3a1.8 1.8 0 0 1-1.8-1.8Z"
    const val Heart = "M12 20.4C12 20.4 3.2 14.1 3.2 8.9C3.2 6.1 5.4 4 8.1 4" +
        "C9.9 4 11.4 5 12 6.5C12.6 5 14.1 4 15.9 4C18.6 4 20.8 6.1 20.8 8.9" +
        "C20.8 14.1 12 20.4 12 20.4Z"
}

@Composable
fun NeoIcon(
    path: String,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    color: Color = Neo.colors.ink,
    weight: Float = 2.6f,
    /** Painted inside the outline. A heart is empty or it is not. */
    fill: Color? = null,
) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(modifier.size(size)) {
        val scale = this.size.minDimension / 24f
        scale(scale) {
            if (fill != null) drawPath(path = parsed, color = fill)
            drawPath(
                path = parsed,
                color = color,
                style = Stroke(width = weight, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/**
 * Scales about the origin, not the centre.
 *
 * Compose's transform pivots on the canvas centre by default, which for a path
 * authored from 0,0 pushes the drawing up and left until only a corner of it is
 * still on the canvas. That is exactly what happened to the tab icons.
 */
private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.scale(
    factor: Float,
    block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    drawContext.transform.scale(factor, factor, Offset.Zero)
    block()
    drawContext.transform.scale(1f / factor, 1f / factor, Offset.Zero)
}

/** The Museroom mark: a fat comic quaver, printed twice out of register. */
object Mark {
    private const val HEAD =
        "M69.37,84.77 A27.0,21.0 -20.0 0 1 18.63,103.23 A27.0,21.0 -20.0 0 1 69.37,84.77 Z"
    private const val STEM = "M64,96 L64,22 L80,22 L80,96 Z"
    private const val FLAG = "M80,22 C104,26 118,46 110,74 C109,52 98,40 80,42 Z"
    val parts = listOf(HEAD, STEM, FLAG)
    const val nudgeX = -4f
    const val nudgeY = -7.75f
    const val offset = 6f
}

@Composable
fun MuseroomMark(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    note: Color = Neo.colors.onAccent,
    ghost: Color? = Neo.colors.lime,
) {
    val paths = remember { Mark.parts.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 128f
        scale(s) {
            drawContext.transform.translate(Mark.nudgeX, Mark.nudgeY)
            if (ghost != null) {
                drawContext.transform.translate(Mark.offset, Mark.offset)
                paths.forEach { drawPath(it, ghost) }
                drawContext.transform.translate(-Mark.offset, -Mark.offset)
            }
            paths.forEach { drawPath(it, note) }
            drawContext.transform.translate(-Mark.nudgeX, -Mark.nudgeY)
        }
    }
}
