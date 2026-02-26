@file:Suppress("FunctionNaming", "LongParameterList", "MagicNumber")

package io.qent.broxy.ui.liquidglass.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.liquidglass.GlassSurface
import io.qent.broxy.ui.liquidglass.GlassSurfaceVariant
import io.qent.broxy.ui.liquidglass.LocalGlassConfig
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.55f
    GlassSurface(
        modifier = modifier.height(32.dp),
        variant = GlassSurfaceVariant.Regular,
        shape = AppTheme.shapes.button,
        onClick = onClick,
        enabled = enabled,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
        }
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val iconAlpha = if (enabled) 1f else 0.55f
    GlassSurface(
        modifier = modifier.size(32.dp),
        variant = GlassSurfaceVariant.Clear,
        shape = AppTheme.shapes.input,
        onClick = onClick,
        enabled = enabled,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = iconAlpha),
            )
        }
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodySmall,
) {
    var focused by remember { mutableStateOf(false) }
    val outlineAlpha = if (focused) 0.85f else 0.55f

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { innerTextField ->
            GlassSurface(
                modifier = Modifier.fillMaxWidth().height(32.dp),
                variant = GlassSurfaceVariant.Regular,
                shape = AppTheme.shapes.input,
                border =
                    BorderStroke(
                        AppTheme.strokeWidths.thin,
                        MaterialTheme.colorScheme.outline.copy(alpha = outlineAlpha),
                    ),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isBlank() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = textStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                }
            }
        },
    )
}

@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = LocalGlassConfig.current
    val checkedTrack =
        if (config.glassEnabled) {
            MaterialTheme.colorScheme.primary.copy(alpha = if (config.reduceTransparency) 0.8f else 0.68f)
        } else {
            MaterialTheme.colorScheme.primary
        }
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors =
            SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = checkedTrack,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
    )
}
