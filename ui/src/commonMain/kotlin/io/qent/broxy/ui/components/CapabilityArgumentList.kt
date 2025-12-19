package io.qent.broxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.qent.broxy.ui.adapter.models.UiCapabilityArgument
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun CapabilityArgumentList(
    arguments: List<UiCapabilityArgument>,
    modifier: Modifier = Modifier,
) {
    if (arguments.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        arguments.forEach { argument ->
            val displayName =
                buildString {
                    append(argument.name)
                    if (argument.required) append("*")
                }
            val typeLabel = argument.type.ifBlank { "unspecified" }
            Text(
                text = "• $displayName ($typeLabel)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
