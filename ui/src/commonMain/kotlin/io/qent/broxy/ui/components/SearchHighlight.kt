package io.qent.broxy.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import io.qent.broxy.ui.adapter.models.UiCapabilityArgument

fun buildHighlightedAnnotatedString(
    text: String,
    query: String,
    highlightColor: Color,
): AnnotatedString {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isBlank()) return AnnotatedString(text)

    val lowerText = text.lowercase()
    val lowerQuery = trimmedQuery.lowercase()
    var index = 0
    return buildAnnotatedString {
        while (index < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, startIndex = index)
            if (matchIndex < 0) {
                append(text.substring(index))
                break
            }
            if (matchIndex > index) {
                append(text.substring(index, matchIndex))
            }
            withStyle(SpanStyle(color = highlightColor)) {
                append(text.substring(matchIndex, matchIndex + lowerQuery.length))
            }
            index = matchIndex + lowerQuery.length
        }
    }
}

@Composable
fun HighlightedText(
    text: String,
    query: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    highlightColor: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text = buildHighlightedAnnotatedString(text, query, highlightColor),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
    )
}

fun matchesCapabilityQuery(
    query: String,
    name: String,
    description: String,
    arguments: List<UiCapabilityArgument>,
): Boolean {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isBlank()) return true
    if (name.contains(trimmedQuery, ignoreCase = true)) return true
    if (description.contains(trimmedQuery, ignoreCase = true)) return true
    return arguments.any { arg ->
        arg.name.contains(trimmedQuery, ignoreCase = true) ||
            arg.type.contains(trimmedQuery, ignoreCase = true)
    }
}

fun matchesResourceQuery(
    query: String,
    name: String,
    key: String,
    description: String,
    arguments: List<UiCapabilityArgument>,
): Boolean {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isBlank()) return true
    if (name.contains(trimmedQuery, ignoreCase = true)) return true
    if (key.contains(trimmedQuery, ignoreCase = true)) return true
    if (description.contains(trimmedQuery, ignoreCase = true)) return true
    return arguments.any { arg ->
        arg.name.contains(trimmedQuery, ignoreCase = true) ||
            arg.type.contains(trimmedQuery, ignoreCase = true)
    }
}
