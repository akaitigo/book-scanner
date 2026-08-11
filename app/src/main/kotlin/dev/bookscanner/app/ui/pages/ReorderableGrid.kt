package dev.bookscanner.app.ui.pages

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Long-press drag reordering for a [androidx.compose.foundation.lazy.grid.LazyVerticalGrid].
 *
 * The list is reordered live as the finger crosses item centres, and the new
 * order is persisted once — on drop — rather than on every crossing, so a drag
 * across twenty pages is one write instead of twenty.
 *
 * Auto-scroll runs while the finger is held near an edge; without it a page
 * could only be moved as far as one screenful.
 */
class GridReorderState(
    private val gridState: LazyGridState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: () -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    var dragOffset by mutableStateOf(Offset.Zero)
        private set

    private var draggingItemIndex: Int? = null
    private var draggingItemInitialOffset: Offset = Offset.Zero
    private val autoScrollRequests = Channel<Float>(Channel.CONFLATED)

    val isDragging: Boolean get() = draggingKey != null

    fun onDragStart(offset: Offset) {
        val item = gridState.itemAt(offset) ?: return
        draggingKey = item.key
        draggingItemIndex = item.index
        draggingItemInitialOffset = Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
        dragOffset = Offset.Zero
    }

    fun onDrag(
        change: Offset,
        viewportHeight: Int,
    ) {
        val currentIndex = draggingItemIndex ?: return
        dragOffset += change

        val centre = draggingItemInitialOffset + dragOffset + currentItemCentreOffset()
        gridState.itemAt(centre)?.let { target ->
            if (target.index != currentIndex) {
                onMove(currentIndex, target.index)
                draggingItemIndex = target.index
                // The dragged item is now where the target was, so the visual
                // offset must be rebased or the item would jump.
                draggingItemInitialOffset = Offset(target.offset.x.toFloat(), target.offset.y.toFloat())
                dragOffset = centre - draggingItemInitialOffset - currentItemCentreOffset()
            }
        }

        autoScrollAmount(centre.y, viewportHeight)?.let { autoScrollRequests.trySend(it) }
    }

    fun onDragEnd() {
        if (draggingKey != null) onDrop()
        reset()
    }

    fun onDragCancel() {
        // A cancelled gesture still leaves the list reordered in the UI, so the
        // order must be persisted rather than silently diverging from storage.
        if (draggingKey != null) onDrop()
        reset()
    }

    private fun reset() {
        draggingKey = null
        draggingItemIndex = null
        dragOffset = Offset.Zero
    }

    private fun currentItemCentreOffset(): Offset {
        val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingKey } ?: return Offset.Zero
        return Offset(item.size.width / 2f, item.size.height / 2f)
    }

    private fun autoScrollAmount(
        pointerY: Float,
        viewportHeight: Int,
    ): Float? {
        val threshold = viewportHeight * AUTO_SCROLL_EDGE_FRACTION
        return when {
            pointerY < threshold -> -AUTO_SCROLL_SPEED
            pointerY > viewportHeight - threshold -> AUTO_SCROLL_SPEED
            else -> null
        }
    }

    internal fun startAutoScroll() {
        scope.launch {
            for (amount in autoScrollRequests) {
                if (!isDragging) continue
                gridState.scrollBy(amount)
            }
        }
    }

    private companion object {
        const val AUTO_SCROLL_EDGE_FRACTION = 0.12f
        const val AUTO_SCROLL_SPEED = 24f
    }
}

private fun LazyGridState.itemAt(offset: Offset): LazyGridItemInfo? =
    layoutInfo.visibleItemsInfo.firstOrNull { item ->
        offset.x >= item.offset.x &&
            offset.x <= item.offset.x + item.size.width &&
            offset.y >= item.offset.y &&
            offset.y <= item.offset.y + item.size.height
    }

@Composable
fun rememberGridReorderState(
    gridState: LazyGridState,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
): GridReorderState {
    val scope = rememberCoroutineScope()
    return remember(gridState) {
        GridReorderState(gridState, scope, onMove, onDrop).also { it.startAutoScroll() }
    }
}

fun Modifier.reorderable(
    state: GridReorderState,
    viewportHeight: () -> Int,
): Modifier =
    pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset -> state.onDragStart(offset) },
            onDrag = { change, amount ->
                change.consume()
                state.onDrag(amount, viewportHeight())
            },
            onDragEnd = { state.onDragEnd() },
            onDragCancel = { state.onDragCancel() },
        )
    }
