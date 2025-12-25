package io.qent.broxy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import io.qent.broxy.ui.theme.AppTheme
import androidx.compose.foundation.v2.ScrollbarAdapter as V2ScrollbarAdapter

@Composable
actual fun AppVerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier,
) {
    val isScrollable by remember(scrollState) {
        derivedStateOf { scrollState.maxValue > 0 }
    }
    if (!isScrollable) return

    AppVerticalScrollbarInternal(
        adapter = rememberScrollbarAdapter(scrollState),
        isScrollInProgress = scrollState.isScrollInProgress,
        modifier = modifier,
    )
}

@Composable
actual fun AppVerticalScrollbar(
    listState: LazyListState,
    modifier: Modifier,
) {
    val isScrollable by remember(listState) {
        derivedStateOf {
            listState.layoutInfo.totalItemsCount > 0 &&
                (listState.canScrollForward || listState.canScrollBackward)
        }
    }
    if (!isScrollable) return

    AppVerticalScrollbarInternal(
        adapter = rememberScrollbarAdapter(listState),
        isScrollInProgress = listState.isScrollInProgress,
        modifier = modifier,
    )
}

@Composable
private fun AppVerticalScrollbarInternal(
    adapter: V2ScrollbarAdapter,
    isScrollInProgress: Boolean,
    modifier: Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val targetAlpha = if (isScrollInProgress || isHovered) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 150),
        label = "scrollbarAlpha",
    )

    val style =
        ScrollbarStyle(
            minimalHeight = AppTheme.spacing.sm,
            thickness = AppTheme.spacing.md - AppTheme.strokeWidths.thick,
            shape = RoundedCornerShape(AppTheme.radii.pill),
            hoverDurationMillis = 120,
            unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            hoverColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
        )

    VerticalScrollbar(
        adapter = adapter,
        modifier = modifier.alpha(alpha),
        style = style,
        interactionSource = interactionSource,
    )
}
