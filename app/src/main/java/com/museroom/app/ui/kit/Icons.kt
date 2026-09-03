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
}

@Composable
fun NeoIcon(
    path: String,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    color: Color = Neo.colors.ink,
    weight: Float = 2.6f,
) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(modifier.size(size)) {
        val scale = this.size.minDimension / 24f
        scale(scale) {
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
