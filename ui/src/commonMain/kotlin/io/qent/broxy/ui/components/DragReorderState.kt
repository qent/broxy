package io.qent.broxy.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

@Stable
class DragReorderState<K>(
    keysProvider: () -> List<K>,
    onMove: (from: Int, to: Int) -> Unit,
    onDragStopped: () -> Unit,
) {
    private var keysProvider: () -> List<K> = keysProvider
    private var onMove: (from: Int, to: Int) -> Unit = onMove
    private var onDragStopped: () -> Unit = onDragStopped
    private val itemHeights = mutableStateMapOf<K, Int>()
    private var draggingKey: K? by mutableStateOf(null)
    private var dragOffsetY: Float by mutableFloatStateOf(0f)

    fun bind(
        keysProvider: () -> List<K>,
        onMove: (from: Int, to: Int) -> Unit,
        onDragStopped: () -> Unit,
    ) {
        this.keysProvider = keysProvider
        this.onMove = onMove
        this.onDragStopped = onDragStopped
    }

    fun updateItemHeight(
        key: K,
        heightPx: Int,
    ) {
        if (heightPx > 0) {
            itemHeights[key] = heightPx
        }
    }

    fun offsetFor(key: K): Float = if (draggingKey == key) dragOffsetY else 0f

    fun isDragging(key: K): Boolean = draggingKey == key

    fun startDrag(key: K) {
        val keys = keysProvider()
        if (keys.size < 2 || key !in keys) return
        draggingKey = key
        dragOffsetY = 0f
    }

    fun dragBy(deltaY: Float) {
        val activeKey = draggingKey ?: return
        if (deltaY == 0f) return
        dragOffsetY += deltaY
        while (swapWithNextIfNeeded(activeKey)) {
            // keep swapping while cursor crosses neighbors
        }
        while (swapWithPreviousIfNeeded(activeKey)) {
            // keep swapping while cursor crosses neighbors
        }
    }

    fun stopDrag() {
        if (draggingKey == null) return
        draggingKey = null
        dragOffsetY = 0f
        onDragStopped()
    }

    @Suppress("ReturnCount")
    private fun swapWithNextIfNeeded(activeKey: K): Boolean {
        val keys = keysProvider()
        val fromIndex = keys.indexOf(activeKey)
        if (fromIndex < 0 || fromIndex >= keys.lastIndex) return false
        val nextKey = keys[fromIndex + 1]
        val nextHeight = itemHeights[nextKey]?.toFloat() ?: return false
        if (dragOffsetY <= nextHeight / 2f) return false
        onMove(fromIndex, fromIndex + 1)
        dragOffsetY -= nextHeight
        return true
    }

    @Suppress("ReturnCount")
    private fun swapWithPreviousIfNeeded(activeKey: K): Boolean {
        val keys = keysProvider()
        val fromIndex = keys.indexOf(activeKey)
        if (fromIndex <= 0) return false
        val previousKey = keys[fromIndex - 1]
        val previousHeight = itemHeights[previousKey]?.toFloat() ?: return false
        if (dragOffsetY >= -previousHeight / 2f) return false
        onMove(fromIndex, fromIndex - 1)
        dragOffsetY += previousHeight
        return true
    }
}

@Composable
fun <K> rememberDragReorderState(
    keysProvider: () -> List<K>,
    onMove: (from: Int, to: Int) -> Unit,
    onDragStopped: () -> Unit,
): DragReorderState<K> {
    val state = remember { DragReorderState(keysProvider, onMove, onDragStopped) }
    state.bind(keysProvider, onMove, onDragStopped)
    return state
}

fun <K> Modifier.dragReorderHandle(
    key: K,
    enabled: Boolean,
    state: DragReorderState<K>,
): Modifier {
    if (!enabled) return this
    return pointerInput(state, key) {
        detectDragGestures(
            onDragStart = { state.startDrag(key) },
            onDragEnd = { state.stopDrag() },
            onDragCancel = { state.stopDrag() },
            onDrag = { change, dragAmount ->
                change.consume()
                state.dragBy(dragAmount.y)
            },
        )
    }
}

fun <T> MutableList<T>.moveItem(
    fromIndex: Int,
    toIndex: Int,
) {
    if (fromIndex == toIndex) return
    if (fromIndex !in indices || toIndex !in indices) return
    val item = removeAt(fromIndex)
    add(toIndex, item)
}
