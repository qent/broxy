package io.qent.broxy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.v2.maxScrollOffset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.theme.AppTheme
import kotlin.math.abs

@Composable
fun AppVerticalScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
    canScroll: Boolean = true,
    isScrollInProgress: Boolean = false,
    trackShape: CornerBasedShape = RoundedCornerShape(
        topStart = AppTheme.radii.sm,
        bottomStart = AppTheme.radii.sm
    ),
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
) {
    val style = rememberScrollbarStyle()
    val interactionSource = remember { MutableInteractionSource() }
    var hovered by remember { mutableStateOf(false) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is HoverInteraction.Enter -> hovered = true
                is HoverInteraction.Exit -> hovered = false
            }
        }
    }

    val shouldShow by remember(canScroll, isScrollInProgress, hovered) {
        derivedStateOf { canScroll && (isScrollInProgress || hovered) }
    }
    val targetAlpha = if (shouldShow) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 120),
        label = "ScrollbarAlpha"
    )

    Box(
        modifier = modifier
            .hoverable(interactionSource = interactionSource, enabled = canScroll)
            .width(AppTheme.layout.scrollbarThickness)
            .fillMaxHeight()
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(trackShape)
                .background(trackColor)
        )
        VerticalScrollbar(
            adapter = adapter,
            style = style,
            modifier = Modifier.matchParentSize()
        )
    }
}

/**
 * Provides a [`ScrollbarAdapter`][ScrollbarAdapter] for lazy lists that keeps the scrollbar thumb
 * size stable while the user scrolls through content with varying item heights.
 *
 * The implementation is adapted from Compose's internal lazy scrollbar adapter and locks the
 * average item size after the first reliable measurement, only refreshing it when item sizes
 * change drastically (e.g. after dataset updates). This prevents the thumb from shrinking or
 * growing on every scroll frame while preserving drag and track interactions.
 */
@Composable
fun rememberStableScrollbarAdapter(
    state: LazyListState,
    refreshThreshold: Double = 0.45
): ScrollbarAdapter {
    val threshold = refreshThreshold.coerceAtLeast(0.0)
    val adapter = remember(state, threshold) { StableLazyListScrollbarAdapter(state, threshold) }

    val layoutInfo = state.layoutInfo
    val firstVisibleIndex = state.firstVisibleItemIndex
    val firstVisibleOffset = state.firstVisibleItemScrollOffset
    val totalItems = layoutInfo.totalItemsCount
    val visibleCount = layoutInfo.visibleItemsInfo.size
    val spacing = layoutInfo.mainAxisItemSpacing

    DisposableEffect(adapter, firstVisibleIndex, firstVisibleOffset, totalItems, visibleCount, spacing) {
        adapter.updateAverageIfNeeded()
        onDispose { }
    }

    return adapter
}

private abstract class StableLazyLineContentAdapter : ScrollbarAdapter {

    protected class VisibleLine(val index: Int, val offset: Int)

    private var lockedAverage: Double = 1.0
    private var latestAverage: Double = 1.0
    private var averageLocked: Boolean = false

    fun updateAverageIfNeeded() {
        val average = computeAverageVisibleLineSize()
        if (average <= 0.0) return
        latestAverage = average
        if (!averageLocked || shouldRefreshAverage(lockedAverage, average)) {
            lockedAverage = average
            averageLocked = true
        }
    }

    protected open fun shouldRefreshAverage(current: Double, new: Double): Boolean = false

    private val averageVisibleLineSize: Double
        get() = if (averageLocked) lockedAverage else latestAverage

    private val averageVisibleLineSizeWithSpacing: Double
        get() = averageVisibleLineSize + lineSpacing

    override val scrollOffset: Double
        get() {
            val firstLine = firstVisibleLine() ?: return 0.0
            return firstLine.index * averageVisibleLineSizeWithSpacing - firstLine.offset
        }

    override val contentSize: Double
        get() {
            val total = totalLineCount()
            return averageVisibleLineSize * total +
                lineSpacing * (total - 1).coerceAtLeast(0) +
                contentPadding()
        }

    override suspend fun scrollTo(scrollOffset: Double) {
        val distance = scrollOffset - this.scrollOffset
        if (abs(distance) <= viewportSize) {
            scrollBy(distance.toFloat())
        } else {
            snapTo(scrollOffset)
        }
    }

    private suspend fun snapTo(scrollOffset: Double) {
        val clamped = scrollOffset.coerceIn(0.0, maxScrollOffset)
        val averageWithSpacing = averageVisibleLineSizeWithSpacing
        val total = totalLineCount()
        val index = (clamped / averageWithSpacing)
            .toInt()
            .coerceAtLeast(0)
            .coerceAtMost((total - 1).coerceAtLeast(0))
        val offset = (clamped - index * averageWithSpacing)
            .toInt()
            .coerceAtLeast(0)
        snapToLine(index, offset)
    }

    protected abstract fun firstVisibleLine(): VisibleLine?
    protected abstract fun totalLineCount(): Int
    protected abstract fun contentPadding(): Int
    protected abstract suspend fun snapToLine(lineIndex: Int, scrollOffset: Int)
    protected abstract suspend fun scrollBy(value: Float)
    protected abstract fun computeAverageVisibleLineSize(): Double
    protected abstract val lineSpacing: Int
    abstract override val viewportSize: Double
}

private class StableLazyListScrollbarAdapter(
    private val scrollState: LazyListState,
    private val refreshThreshold: Double
) : StableLazyLineContentAdapter() {

    override val viewportSize: Double
        get() = with(scrollState.layoutInfo) {
            if (orientation == Orientation.Vertical) {
                viewportSize.height
            } else {
                viewportSize.width
            }
        }.toDouble()

    override fun computeAverageVisibleLineSize(): Double {
        val layoutInfo = scrollState.layoutInfo
        val items = layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return 0.0
        val spacing = layoutInfo.mainAxisItemSpacing
        val firstIndex = firstFloatingVisibleItemIndex(items, spacing) ?: return 0.0
        val first = items[firstIndex]
        val last = items.last()
        val count = items.size - firstIndex
        if (count <= 0) return 0.0
        val occupied = last.offset + last.size - first.offset - (count - 1) * spacing
        return occupied.toDouble() / count
    }

    override fun shouldRefreshAverage(current: Double, new: Double): Boolean {
        if (current <= 0.0) return true
        val delta = abs(new - current) / current
        return delta > refreshThreshold
    }

    override fun firstVisibleLine(): VisibleLine? {
        val layoutInfo = scrollState.layoutInfo
        val spacing = layoutInfo.mainAxisItemSpacing
        val index = firstFloatingVisibleItemIndex(layoutInfo.visibleItemsInfo, spacing) ?: return null
        val item = layoutInfo.visibleItemsInfo[index]
        return VisibleLine(item.index, item.offset)
    }

    override fun totalLineCount(): Int = scrollState.layoutInfo.totalItemsCount

    override fun contentPadding(): Int = with(scrollState.layoutInfo) {
        beforeContentPadding + afterContentPadding
    }

    override suspend fun snapToLine(lineIndex: Int, scrollOffset: Int) {
        scrollState.scrollToItem(lineIndex, scrollOffset)
    }

    override suspend fun scrollBy(value: Float) {
        scrollState.scrollBy(value)
    }

    override val lineSpacing: Int
        get() = scrollState.layoutInfo.mainAxisItemSpacing

    private fun firstFloatingVisibleItemIndex(
        items: List<LazyListItemInfo>,
        spacing: Int
    ): Int? = when (items.size) {
        0 -> null
        1 -> 0
        else -> {
            val first = items[0]
            val second = items[1]
            if ((first.index < second.index - 1) ||
                (first.offset + first.size + spacing > second.offset)
            ) {
                1
            } else {
                0
            }
        }
    }
}

@Composable
private fun rememberScrollbarStyle(): ScrollbarStyle {
    val colors = MaterialTheme.colorScheme
    val baseThumb = remember(colors) {
        lerp(colors.surfaceVariant, Color.White, 0.65f).copy(alpha = 0.9f)
    }
    val hoverThumb = remember(baseThumb) {
        lerp(baseThumb, Color.White, 0.35f).copy(alpha = 0.95f)
    }
    val thickness = AppTheme.layout.scrollbarThickness
    val radius = AppTheme.radii.sm
    val minimalHeight = 24.dp

    return remember(colors, baseThumb, hoverThumb, thickness, radius, minimalHeight) {
        ScrollbarStyle(
            thickness = thickness,
            minimalHeight = minimalHeight,
            shape = RoundedCornerShape(radius),
            hoverDurationMillis = 150,
            hoverColor = hoverThumb,
            unhoverColor = baseThumb
        )
    }
}
