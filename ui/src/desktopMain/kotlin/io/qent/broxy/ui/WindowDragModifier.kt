package io.qent.broxy.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window

fun Modifier.windowDrag(window: Window): Modifier = pointerInput(window) {
    var startPointer: Point? = null
    var startWindow: Point? = null
    detectDragGestures(
        onDragStart = {
            val pointerInfo = runCatching { MouseInfo.getPointerInfo() }.getOrNull()
            startPointer = pointerInfo?.location
            startWindow = window.location
        },
        onDrag = { change, _ ->
            change.consumePositionChange()
            val pointerInfo = runCatching { MouseInfo.getPointerInfo() }.getOrNull()
            val pointer = pointerInfo?.location
            val initialPointer = startPointer
            val initialWindow = startWindow
            if (pointer != null && initialPointer != null && initialWindow != null) {
                val deltaX = pointer.x - initialPointer.x
                val deltaY = pointer.y - initialPointer.y
                val newX = initialWindow.x + deltaX
                val newY = initialWindow.y + deltaY
                window.setLocation(newX, newY)
            }
        },
        onDragEnd = {
            startPointer = null
            startWindow = null
        },
        onDragCancel = {
            startPointer = null
            startWindow = null
        }
    )
}
