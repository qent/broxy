package io.qent.broxy.ui.screens

import AppSecondaryButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.qent.broxy.ui.adapter.models.UiServer
import io.qent.broxy.ui.components.AppDialog

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
