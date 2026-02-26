@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.liquidglass.GlassSurface
import io.qent.broxy.ui.liquidglass.GlassSurfaceVariant
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun BroxyFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier.defaultMinSize(minWidth = 56.dp, minHeight = 56.dp),
        onClick = onClick,
        variant = GlassSurfaceVariant.Regular,
        shape = AppTheme.shapes.card,
        border = BorderStroke(1.dp, AppTheme.colors.primary),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 56.dp),
        ) {
            content()
        }
    }
}
