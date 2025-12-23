package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

val SearchFieldFabAlignedBottomPadding: Dp = 26.dp

private val SearchFieldHeight: Dp = 36.dp

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    width: Dp = DefaultSearchFieldWidth,
) {
    val strings = LocalStrings.current
    var isFocused by remember { mutableStateOf(false) }
    val borderColor =
        if (isFocused || value.isNotBlank()) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        }
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier =
            modifier
                .onFocusChanged { isFocused = it.isFocused }
                .height(SearchFieldHeight)
                .width(width),
        decorationBox = { innerTextField ->
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = AppTheme.shapes.pill,
                color = surfaceColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(AppTheme.strokeWidths.thin, borderColor),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = AppTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                    if (value.isNotBlank()) {
                        IconButton(
                            onClick = { onValueChange("") },
                            modifier =
                                Modifier
                                    .size(28.dp)
                                    .pointerHoverIcon(PointerIcon.Default),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = strings.clearSearch,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}
