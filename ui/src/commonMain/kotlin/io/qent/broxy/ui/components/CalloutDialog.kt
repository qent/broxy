package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun CalloutDialog(
    title: String,
    prompt: String,
    description: String,
    icon: ImageVector,
    accentColor: Color,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
    minWidth: Dp = AppTheme.layout.dialogMinWidth,
    maxWidth: Dp = 520.dp,
) {
    AppDialog(
        title = title,
        titleStyle = MaterialTheme.typography.titleLarge,
        minWidth = minWidth,
        maxWidth = maxWidth,
        onDismissRequest = onDismiss,
        dismissButton = dismissButton,
        maxContentHeight = null,
        enableScroll = false,
        confirmButton = confirmButton,
        dismissOnClickOutside = dismissOnClickOutside,
        dismissOnBackPress = dismissOnBackPress,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = AppTheme.spacing.xs, bottom = AppTheme.spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = AppTheme.shapes.pill,
                color = accentColor.copy(alpha = 0.15f),
                border =
                    BorderStroke(
                        width = AppTheme.strokeWidths.hairline,
                        color = accentColor.copy(alpha = 0.6f),
                    ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
            ) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
