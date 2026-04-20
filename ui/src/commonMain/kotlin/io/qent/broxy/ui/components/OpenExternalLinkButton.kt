@file:Suppress("FunctionNaming", "LongParameterList")

package io.qent.broxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun OpenExternalLinkButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 28.dp,
    iconSize: Dp = 13.dp,
    hoverUrl: String? = null,
    icon: ImageVector = Icons.AutoMirrored.Outlined.OpenInNew,
) {
    val iconHighlightSize = iconSize + 8.dp
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val reportHover = LocalExternalLinkHoverReporter.current
    val normalizedHoverUrl = hoverUrl?.trim()?.takeIf { it.isNotEmpty() }

    LaunchedEffect(isHovered, normalizedHoverUrl, reportHover) {
        reportHover(if (isHovered) normalizedHoverUrl else null)
    }
    DisposableEffect(reportHover) {
        onDispose {
            reportHover(null)
        }
    }

    Box(
        modifier =
            modifier
                .size(buttonSize)
                .hoverable(interactionSource = interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier =
                Modifier
                    .size(iconHighlightSize)
                    .clip(CircleShape)
                    .background(if (isHovered) Color.White.copy(alpha = 0.14f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
