package io.qent.broxy.ui.components

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun AppVerticalScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
    trackShape: CornerBasedShape = RoundedCornerShape(
        topStart = AppTheme.radii.sm,
        bottomStart = AppTheme.radii.sm
    ),
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
) {
    val style = rememberScrollbarStyle()

    Box(
        modifier = modifier
            .width(AppTheme.layout.scrollbarThickness)
            .fillMaxHeight(),
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
