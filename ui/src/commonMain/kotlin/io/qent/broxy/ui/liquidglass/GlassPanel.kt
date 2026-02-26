@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.liquidglass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.theme.AppTheme

@Composable
@Suppress("LongParameterList")
fun GlassPanel(
    modifier: Modifier = Modifier,
    variant: GlassSurfaceVariant = GlassSurfaceVariant.Clear,
    padding: PaddingValues = PaddingValues(0.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        variant = variant,
        shape = AppTheme.shapes.card,
        contentPadding = padding,
        onClick = onClick,
        content = content,
    )
}

@Composable
@Suppress("LongParameterList")
fun GlassCard(
    modifier: Modifier = Modifier,
    variant: GlassSurfaceVariant = GlassSurfaceVariant.Regular,
    padding: PaddingValues = PaddingValues(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.md),
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        variant = variant,
        shape = AppTheme.shapes.card,
        contentPadding = padding,
        border =
            border
                ?: BorderStroke(
                    AppTheme.strokeWidths.thin,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                ),
        onClick = onClick,
        content = content,
    )
}

@Composable
fun GlassDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(AppTheme.strokeWidths.hairline)
                .background(color),
    )
}
