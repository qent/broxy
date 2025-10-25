package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    ) { data ->
        AppSnackbar(data = data)
    }
}

@Composable
private fun AppSnackbar(
    data: SnackbarData,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val message = data.visuals.message
    val isError = message.startsWith(strings.errorLabel, ignoreCase = true)
    val accentColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    val actionLabel = data.visuals.actionLabel

    Box(
        modifier = modifier.fillMaxWidth().padding(start = 84.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier =
                Modifier
                    .padding(horizontal = AppTheme.spacing.lg)
                    .widthIn(max = 520.dp),
            shape = AppTheme.shapes.pill,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = AppTheme.elevation.level1,
            shadowElevation = AppTheme.elevation.level2,
            border = BorderStroke(AppTheme.strokeWidths.hairline, borderColor),
        ) {
            Row(
                modifier =
                    Modifier.padding(
                        horizontal = AppTheme.spacing.md,
                        vertical = AppTheme.spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .background(accentColor, CircleShape),
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (actionLabel != null) {
                    TextButton(
                        onClick = { data.performAction() },
                        colors = ButtonDefaults.textButtonColors(contentColor = accentColor),
                        contentPadding =
                            PaddingValues(
                                horizontal = AppTheme.spacing.sm,
                                vertical = AppTheme.spacing.xs,
                            ),
                    ) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
