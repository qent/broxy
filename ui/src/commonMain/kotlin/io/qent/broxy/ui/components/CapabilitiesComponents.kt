@file:Suppress("FunctionNaming", "MatchingDeclarationName")

package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiCapabilityArgument
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

data class CapabilityDisplayItem(
    val serverName: String,
    val capabilityName: String,
    val description: String,
    val arguments: List<UiCapabilityArgument>,
)

@Composable
fun CapabilitiesCard(
    title: String,
    items: List<CapabilityDisplayItem>,
    icon: ImageVector,
    showServerName: Boolean = true,
    highlightQuery: String = "",
) {
    if (items.isEmpty()) return
    FormCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
            items.forEachIndexed { index, item ->
                CapabilityRow(item, icon, showServerName, highlightQuery)
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        thickness = AppTheme.strokeWidths.hairline,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun CapabilityRow(
    item: CapabilityDisplayItem,
    icon: ImageVector,
    showServerName: Boolean = true,
    highlightQuery: String = "",
) {
    val strings = LocalStrings.current
    val highlightColor = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text =
                    if (showServerName) {
                        buildAnnotatedString {
                            append(buildHighlightedAnnotatedString(item.capabilityName, highlightQuery, highlightColor))
                            append(strings.capabilitySeparator)
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                                append(item.serverName)
                            }
                        }
                    } else {
                        buildHighlightedAnnotatedString(item.capabilityName, highlightQuery, highlightColor)
                    },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CapabilityArgumentList(
            arguments = item.arguments,
            modifier = Modifier.padding(top = AppTheme.spacing.xs),
            highlightQuery = highlightQuery,
            highlightColor = highlightColor,
        )
        Text(
            text = buildHighlightedAnnotatedString(item.description, highlightQuery, highlightColor),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun FormCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = AppTheme.shapes.card,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.lg, vertical = AppTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content(this)
        }
    }
}
