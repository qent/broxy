@file:Suppress("FunctionNaming", "LongParameterList")

package io.qent.broxy.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiServerIcon
import io.qent.broxy.ui.icons.rememberServerIconPainter
import io.qent.broxy.ui.theme.AppTheme

private const val LUMINANCE_THRESHOLD = 0.5f

@Composable
fun ServerIconBadge(
    icon: UiServerIcon,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
    padding: Dp = AppTheme.spacing.sm,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val isLightTheme = MaterialTheme.colorScheme.background.luminance() > LUMINANCE_THRESHOLD
    val badgeBackgroundColor = if (isLightTheme) Color.White else backgroundColor
    val fallbackIconTint = if (isLightTheme) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface
    val showRemove = onRemove != null && icon is UiServerIcon.Custom
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()
    val hoverModifier = if (showRemove) Modifier.hoverable(hoverInteraction) else Modifier
    val clickModifier =
        if (onClick == null) {
            Modifier
        } else {
            Modifier.composed {
                val interactionSource = remember { MutableInteractionSource() }
                clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
            }
        }
    Surface(
        modifier = modifier.then(clickModifier).then(hoverModifier),
        shape = AppTheme.shapes.item,
        color = badgeBackgroundColor,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                ServerIconContent(
                    icon = icon,
                    contentDescription = contentDescription,
                    fallbackIconTint = fallbackIconTint,
                )
            }

            if (showRemove && isHovered) {
                RemoveIconBadge(
                    onRemove = requireNotNull(onRemove),
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(AppTheme.spacing.xxs)
                            .size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ServerIconContent(
    icon: UiServerIcon,
    contentDescription: String?,
    fallbackIconTint: Color,
) {
    when (icon) {
        is UiServerIcon.Asset -> {
            val painter = rememberServerIconPainter(icon)
            Image(
                painter = painter,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        is UiServerIcon.Custom -> {
            val painter = rememberServerIconPainter(icon)
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
                tint = fallbackIconTint,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun RemoveIconBadge(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onRemove,
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = AppTheme.elevation.level1,
    ) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxSize().padding(2.dp),
        )
    }
}
