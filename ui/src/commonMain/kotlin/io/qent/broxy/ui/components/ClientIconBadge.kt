@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import io.qent.broxy.ui.icons.rememberClientIconPainter
import io.qent.broxy.ui.theme.AppTheme

private const val LUMINANCE_THRESHOLD = 0.5f

@Composable
fun ClientIconBadge(
    iconId: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
) {
    val isLightTheme = MaterialTheme.colorScheme.background.luminance() > LUMINANCE_THRESHOLD
    val badgeBackgroundColor = if (isLightTheme) Color.White else backgroundColor
    val fallbackIconTint = if (isLightTheme) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface
    Surface(
        modifier = modifier,
        shape = AppTheme.shapes.item,
        color = badgeBackgroundColor,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(AppTheme.spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            val painter = rememberClientIconPainter(iconId)
            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.SmartToy,
                    contentDescription = contentDescription,
                    tint = fallbackIconTint,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
