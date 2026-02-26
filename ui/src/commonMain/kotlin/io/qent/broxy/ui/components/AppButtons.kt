@file:Suppress("FunctionNaming", "MatchingDeclarationName", "MagicNumber")

package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.qent.broxy.ui.liquidglass.GlassSurface
import io.qent.broxy.ui.liquidglass.GlassSurfaceVariant
import io.qent.broxy.ui.theme.AppTheme

private val DangerRed = Color(0xFFDC2626)

@Composable
fun AppPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        variant = GlassSurfaceVariant.Regular,
        shape = AppTheme.shapes.button,
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(AppTheme.strokeWidths.thin, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun AppSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        variant = GlassSurfaceVariant.Clear,
        shape = AppTheme.shapes.button,
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(AppTheme.strokeWidths.thin, MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun AppDangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        variant = GlassSurfaceVariant.Regular,
        shape = AppTheme.shapes.button,
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(AppTheme.strokeWidths.thin, DangerRed.copy(alpha = 0.85f)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
