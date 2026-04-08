@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.theme.AppTheme

internal const val SETTING_CONTROL_HEIGHT_DP = 32
private const val SETTING_CONTROL_WIDTH_DP = 140

internal val SettingControlWidth: Dp = SETTING_CONTROL_WIDTH_DP.dp

@Composable
internal fun SettingItem(
    title: String,
    description: String,
    supportingContent: (@Composable ColumnScope.() -> Unit)? = null,
    control: @Composable RowScope.() -> Unit,
) {
    SettingsLikeItem(
        title = title,
        description = description,
        contentPadding =
            PaddingValues(
                start = AppTheme.spacing.md + AppTheme.spacing.sm,
                end = AppTheme.spacing.md,
                top = AppTheme.spacing.md,
                bottom = AppTheme.spacing.md,
            ),
        supportingContent = supportingContent,
        control = control,
    )
}

@Composable
internal fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(SETTING_CONTROL_HEIGHT_DP.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        visualTransformation = visualTransformation,
        decorationBox = { innerTextField ->
            CompactInputSurface {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = AppTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && label != null) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                }
            }
        },
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )
}

@Composable
internal fun SettingControlBox(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier =
            Modifier
                .widthIn(min = SettingControlWidth, max = SettingControlWidth)
                .height(SETTING_CONTROL_HEIGHT_DP.dp),
        contentAlignment = Alignment.CenterEnd,
        content = content,
    )
}

@Composable
private fun CompactInputSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.height(SETTING_CONTROL_HEIGHT_DP.dp),
        shape = AppTheme.shapes.input,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(AppTheme.strokeWidths.thin, MaterialTheme.colorScheme.outline),
        content = content,
    )
}

internal fun isValidProviderEndpoint(value: String): Boolean {
    val trimmed = value.trim()
    val lower = trimmed.lowercase()
    val hostPortPath = trimmed.substringAfter("://", "")
    val host =
        hostPortPath
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
    val hasValidScheme = lower.startsWith("http://") || lower.startsWith("https://")
    val hasValidHost =
        hostPortPath.isNotBlank() &&
            host.isNotBlank() &&
            !host.contains(' ') &&
            !host.startsWith(':') &&
            !host.endsWith(':')
    return trimmed.isBlank() || (hasValidScheme && hasValidHost)
}
