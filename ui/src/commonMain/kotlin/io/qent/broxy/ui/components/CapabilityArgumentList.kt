package io.qent.broxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.qent.broxy.ui.adapter.models.UiCapabilityArgument
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun CapabilityArgumentList(
    arguments: List<UiCapabilityArgument>,
    modifier: Modifier = Modifier,
    highlightQuery: String = "",
    highlightColor: Color = MaterialTheme.colorScheme.primary,
) {
    val strings = LocalStrings.current
    if (arguments.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        arguments.forEach { argument ->
            val displayName =
                buildString {
                    append(argument.name)
                    if (argument.required) append("*")
                }
            val typeLabel = argument.type.ifBlank { strings.argumentTypeUnspecified }
            val rendered = strings.capabilityArgument(displayName, typeLabel)
            Text(
                text = buildHighlightedAnnotatedString(rendered, highlightQuery, highlightColor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
