package io.qent.broxy.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import io.qent.broxy.ui.adapter.models.UiServerIcon
import io.qent.broxy.ui.icons.rememberServerIconPainter
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun ServerIconBadge(
    icon: UiServerIcon,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
) {
    Surface(
        modifier = modifier,
        shape = AppTheme.shapes.item,
        color = backgroundColor,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(AppTheme.spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            when (icon) {
                is UiServerIcon.Asset -> {
                    val painter = rememberServerIconPainter(icon.id)
                    Image(
                        painter = painter,
                        contentDescription = contentDescription,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                UiServerIcon.Default -> {
                    Icon(
                        imageVector = Icons.Outlined.Storage,
                        contentDescription = contentDescription,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
