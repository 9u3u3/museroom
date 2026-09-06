package com.museroom.app.ui.screens

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex

/**
 * Dragging a row to a different place in a list.
 *
 * Written rather than pulled in, because what a reorderable list actually needs
 * here is small: the rows are all the same height, so where a finger has got to
 * is one division, and a swap happens the moment the finger has travelled one
 * row. The alternative is a library that measures every item to support ragged
 * lists Museroom does not have.
 *
 * The drag is keyed by the song's id rather than by its position, and this is
 * the whole reason it works. A gesture detector keyed on an index is torn down
 * and rebuilt the instant the row moves, which cancels the drag that caused the
 * move — the row jumps one place and stops dead under a finger still going.
 */
class ReorderState internal constructor(private val onMove: (Int, Int) -> Unit) {

    /** The row under the finger, or null. Its id, because its index moves. */
    var dragging by mutableStateOf<String?>(null)
        private set

    /** How far past its own place the dragged row is drawn, in pixels. */
    var offset by mutableFloatStateOf(0f)
        private set

    private var rowHeight by mutableIntStateOf(0)

    internal fun measured(height: Int) {
        if (height > 0) rowHeight = height
    }

    internal fun begin(id: String) {
        dragging = id
        offset = 0f
    }

    internal fun end() {
        dragging = null
        offset = 0f
    }

    /**
     * One drag event: move the row on screen, and swap it when it has gone far
     * enough that it is drawn over its neighbour.
     *
     * One step per event rather than a loop, because the index it would need to
     * loop against is the caller's and goes stale the moment a swap happens. At
     * sixty events a second a finger cannot outrun it.
     */
    internal fun drag(delta: Float, indexNow: Int, size: Int) {
        val height = rowHeight.toFloat()
        if (indexNow < 0 || height <= 0f) return
        offset += delta
        when {
            offset >= height && indexNow + 1 < size -> {
                onMove(indexNow, indexNow + 1)
                offset -= height
            }
            offset <= -height && indexNow > 0 -> {
                onMove(indexNow, indexNow - 1)
                offset += height
            }
            // At either end there is nowhere to go, so the row stops rather
            // than sliding away from the list it belongs to.
            else -> offset = offset.coerceIn(-height, height)
        }
    }
}

@Composable
fun rememberReorder(onMove: (Int, Int) -> Unit): ReorderState =
    remember { ReorderState(onMove) }

/**
 * Put on the row: lifts it while it is being dragged and reports its height.
 *
 * The height is taken from every row rather than just the first, which costs
 * nothing and means a list whose rows change size is still measured correctly.
 */
fun Modifier.reorderable(state: ReorderState, id: String): Modifier =
    this
        .onSizeChanged { state.measured(it.height) }
        .zIndex(if (state.dragging == id) 1f else 0f)
        .graphicsLayer {
            translationY = if (state.dragging == id) state.offset else 0f
        }

/**
 * Put on the grip: the only part of a row that starts a drag.
 *
 * A handle rather than the whole row, because a queue is also a list you scroll
 * and a list you tap, and a row that both scrolls and reorders has to guess
 * which one somebody meant.
 */
fun Modifier.dragHandle(
    state: ReorderState,
    id: String,
    indexOf: () -> Int,
    size: () -> Int,
): Modifier = this.pointerInput(id) {
    detectDragGestures(
        onDragStart = { state.begin(id) },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
    ) { change, drag ->
        change.consume()
        state.drag(drag.y, indexOf(), size())
    }
}
