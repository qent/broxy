package io.qent.broxy.ui.components

import AppDangerButton
import AppSecondaryButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun DeleteConfirmationDialog(
    title: String,
    prompt: String,
    description: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Delete",
    dismissLabel: String = "Cancel",
) {
    val dangerColor = Color(0xFFDC2626)

    AppDialog(
        title = title,
        titleStyle = MaterialTheme.typography.titleLarge,
        minWidth = AppTheme.layout.dialogMinWidth,
        maxWidth = 520.dp,
        onDismissRequest = onDismiss,
        dismissButton = { AppSecondaryButton(onClick = onDismiss) { Text(dismissLabel) } },
        confirmButton = { AppDangerButton(onClick = onConfirm) { Text(confirmLabel) } },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = AppTheme.spacing.xs, bottom = AppTheme.spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = AppTheme.shapes.pill,
                color = dangerColor.copy(alpha = 0.2f),
                border =
                    BorderStroke(
                        width = AppTheme.strokeWidths.hairline,
                        color = dangerColor.copy(alpha = 0.6f),
                    ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = dangerColor,
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
