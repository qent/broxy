package io.qent.broxy.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.qent.broxy.ui.theme.AppTheme

/**
 * Shared button colors and wrappers to keep button styling consistent across the app.
 */
object AppButtonDefaults {
    @Composable
    fun primaryTonalColors(): ButtonColors = ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        disabledContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.45f)
    )

    @Composable
    fun secondaryTonalColors(): ButtonColors = ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.4f)
    )
}

@Composable
fun AppPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = AppTheme.shapes.surfaceSm,
        colors = AppButtonDefaults.primaryTonalColors(),
        content = content
    )
}

@Composable
fun AppSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = AppTheme.shapes.surfaceSm,
        colors = AppButtonDefaults.secondaryTonalColors(),
        content = content
    )
}
