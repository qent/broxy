package io.qent.broxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun ProxyStatusIndicator(
    status: UiProxyStatus?,
    modifier: Modifier = Modifier,
) {
    val (dotColor, statusText) =
        when (status) {
            UiProxyStatus.Running -> AppTheme.extendedColors.success to "Running"
            UiProxyStatus.Starting -> MaterialTheme.colorScheme.secondary to "Starting"
            UiProxyStatus.Stopping -> MaterialTheme.colorScheme.secondary to "Stopping"
            UiProxyStatus.Stopped, null -> MaterialTheme.colorScheme.outline to "Stopped"
            is UiProxyStatus.Error -> {
                val message = status.message.ifBlank { "Error" }
                val portBusy =
                    message.contains("already in use", ignoreCase = true) ||
                        message.contains("Address already in use", ignoreCase = true)
                MaterialTheme.colorScheme.error to (if (portBusy) "Port already in use" else message)
            }
        }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
        )
        Spacer(Modifier.width(AppTheme.spacing.sm))
        Text(
            statusText,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
