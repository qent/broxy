package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun GlobalHeader(
    ui: UIState,
    notify: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val port = (ui as? UIState.Ready)?.inboundSsePort ?: 3335
    val sseUrl = "http://0.0.0.0:$port/mcp"

    val status = (ui as? UIState.Ready)?.proxyStatus
    val (dotColor, statusText) = when (status) {
        UiProxyStatus.Running -> AppTheme.extendedColors.success to "Running"
        UiProxyStatus.Starting -> MaterialTheme.colorScheme.secondary to "Starting"
        UiProxyStatus.Stopping -> MaterialTheme.colorScheme.secondary to "Stopping"
        UiProxyStatus.Stopped, null -> MaterialTheme.colorScheme.outline to "Stopped"
        is UiProxyStatus.Error -> {
            val message = status.message.ifBlank { "Error" }
            val portBusy = message.contains("already in use", ignoreCase = true) ||
                message.contains("Address already in use", ignoreCase = true)
            MaterialTheme.colorScheme.error to (if (portBusy) "Порт занят" else message)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.sm)
            .padding(top = AppTheme.spacing.xxs, bottom = AppTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
        ) {
            HeaderField(
                text = sseUrl,
                modifier = Modifier.widthIn(min = 200.dp, max = 260.dp)
            )
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(sseUrl))
                    notify("SSE URL copied")
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy SSE URL",
                    modifier = Modifier.size(18.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(AppTheme.spacing.xs))
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.weight(1f))
        PresetDropdown(ui = ui, notify = notify, modifier = Modifier.width(196.dp))
    }
}

@Composable
private fun PresetDropdown(
    ui: UIState,
    notify: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    when (ui) {
        UIState.Loading -> HeaderField(text = "Loading…", modifier = modifier)

        is UIState.Error -> HeaderField(text = "Unavailable", modifier = modifier)

        is UIState.Ready -> {
            val selectedPresetId = ui.selectedPresetId
            val currentName = ui.presets.firstOrNull { it.id == selectedPresetId }?.name
                ?: if (selectedPresetId == null) "No preset" else selectedPresetId

            Box(modifier = modifier) {
                HeaderField(
                    text = currentName,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { expanded = !expanded },
                    trailing = {
                        Icon(
                            imageVector = Icons.Outlined.ExpandMore,
                            contentDescription = "Open preset menu",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("No preset") },
                        onClick = {
                            expanded = false
                            if (ui.selectedPresetId != null) {
                                ui.intents.selectProxyPreset(null)
                                notify("Preset cleared")
                            }
                        }
                    )
                    if (ui.presets.isNotEmpty()) {
                        DropdownMenuItem(
                            enabled = false,
                            text = { Text("—") },
                            onClick = {}
                        )
                    }
                    ui.presets.forEach { p ->
                        val isSelected = p.id == selectedPresetId
                        DropdownMenuItem(
                            text = { Text(p.name) },
                            onClick = {
                                expanded = false
                                if (!isSelected) {
                                    ui.intents.selectProxyPreset(p.id)
                                    notify("Preset selected: ${p.name}")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderField(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val clickModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Surface(
        modifier = modifier
            .height(32.dp)
            .then(clickModifier),
        shape = AppTheme.shapes.input,
        color = colors.surface,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(AppTheme.strokeWidths.thin, colors.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (trailing != null) trailing()
        }
    }
}
