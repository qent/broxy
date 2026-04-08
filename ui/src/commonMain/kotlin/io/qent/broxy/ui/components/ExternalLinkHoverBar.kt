@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.qent.broxy.ui.theme.AppTheme

internal typealias ExternalLinkHoverReporter = (String?) -> Unit

internal val LocalExternalLinkHoverReporter = staticCompositionLocalOf<ExternalLinkHoverReporter> { {} }

@Composable
fun ExternalLinkHoverBar(
    hoveredUrl: String?,
    modifier: Modifier = Modifier,
) {
    val displayUrl = hoveredUrl?.trim()?.takeIf { it.isNotEmpty() }
    if (displayUrl == null) {
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = displayUrl,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppTheme.spacing.md,
                        end = AppTheme.spacing.md,
                        top = AppTheme.spacing.xs,
                        bottom = AppTheme.spacing.md,
                    ),
        )
    }
}
