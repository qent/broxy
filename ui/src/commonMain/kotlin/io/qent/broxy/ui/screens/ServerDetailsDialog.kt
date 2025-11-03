package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.components.AppDialog
import io.qent.broxy.ui.components.AppSecondaryButton
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun ServerDetailsDialog(cfg: UiServer, onClose: () -> Unit) {
    AppDialog(
        title = "${cfg.name} • Details",
        onDismissRequest = onClose,
        dismissButton = null,
        confirmButton = { AppSecondaryButton(onClick = onClose) { Text("Close") } }
    ) {
        Text("ID: ${cfg.id}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Transport: ${cfg.transportLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
