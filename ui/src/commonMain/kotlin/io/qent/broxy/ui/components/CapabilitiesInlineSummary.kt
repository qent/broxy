package io.qent.broxy.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun CapabilitiesInlineSummary(
    toolsCount: Int,
    promptsCount: Int,
    resourcesCount: Int,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 14.dp,
    textStyle: TextStyle = MaterialTheme.typography.bodySmall
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CapabilitiesInlineItem(
            icon = Icons.Outlined.Construction,
            contentDescription = "Tools",
            count = toolsCount,
            tint = tint,
            iconSize = iconSize,
            textStyle = textStyle
        )
        CapabilitiesInlineItem(
            icon = Icons.Outlined.ChatBubbleOutline,
            contentDescription = "Prompts",
            count = promptsCount,
            tint = tint,
            iconSize = iconSize,
            textStyle = textStyle
        )
        CapabilitiesInlineItem(
            icon = Icons.Outlined.Description,
            contentDescription = "Resources",
            count = resourcesCount,
            tint = tint,
            iconSize = iconSize,
            textStyle = textStyle
        )
    }
}

@Composable
private fun CapabilitiesInlineItem(
    icon: ImageVector,
    contentDescription: String,
    count: Int,
    tint: Color,
    iconSize: Dp,
    textStyle: TextStyle
) {
    Row(
        modifier = Modifier.widthIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = count.toString(),
            style = textStyle,
            color = tint,
            modifier = Modifier.widthIn(min = 12.dp),
            textAlign = TextAlign.Center
        )
    }
}
