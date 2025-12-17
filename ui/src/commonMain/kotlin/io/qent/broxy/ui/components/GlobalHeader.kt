@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.theme.AppTheme

private val GLOBAL_HEADER_HEIGHT = 40.dp
private val PRESET_SELECTOR_WIDTH = 220.dp

@Composable
fun GlobalHeader(
    ui: UIState,
    notify: (String) -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
    modifier: Modifier = Modifier
) {
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

    CenterAlignedTopAppBar(
        modifier = modifier,
        expandedHeight = GLOBAL_HEADER_HEIGHT,
        colors = colors,
        title = {
            PresetDropdown(ui = ui, notify = notify, width = PRESET_SELECTOR_WIDTH)
        },
        actions = {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(top = AppTheme.spacing.md, end = AppTheme.spacing.sm)
            ) {
                ProxyStatusIndicator(
                    dotColor = dotColor,
                    statusText = statusText,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
    )
}

@Composable
private fun ProxyStatusIndicator(
    dotColor: androidx.compose.ui.graphics.Color,
    statusText: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(AppTheme.spacing.sm))
        Text(
            statusText,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PresetDropdown(
    ui: UIState,
    notify: (String) -> Unit,
    width: Dp,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    when (ui) {
        UIState.Loading -> HeaderField(text = "Loading…", modifier = modifier.width(width))

        is UIState.Error -> HeaderField(text = "Unavailable", modifier = modifier.width(width))

        is UIState.Ready -> {
            val selectedPresetId = ui.selectedPresetId
            val currentName = ui.presets.firstOrNull { it.id == selectedPresetId }?.name
                ?: if (selectedPresetId == null) "No preset" else selectedPresetId

            Box(modifier = modifier.width(width)) {
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
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .width(width)
                        .background(color = AppTheme.colors.surface, shape = AppTheme.shapes.input)
                        .border(AppTheme.strokeWidths.thin, AppTheme.colors.outline, AppTheme.shapes.input),
                    shape = AppTheme.shapes.input,
                    containerColor = AppTheme.colors.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = AppTheme.elevation.level2
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "No preset",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.colors.onSurface
                            )
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = AppTheme.spacing.md,
                            vertical = AppTheme.spacing.xs
                        ),
                        onClick = {
                            expanded = false
                            if (ui.selectedPresetId != null) {
                                ui.intents.selectProxyPreset(null)
                                notify("Preset cleared")
                            }
                        }
                    )
                    
                    if (ui.presets.isNotEmpty()) {
                        // Optional: Add a subtle divider or spacer if needed, or just list items.
                        // Skipping explicit visual separator to keep it minimal as requested.
                    }

                    ui.presets.forEach { p ->
                        val isSelected = p.id == selectedPresetId
                        DropdownMenuItem(
                            text = {
                                Text(
                                    p.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTheme.colors.onSurface
                                )
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = AppTheme.spacing.md,
                                vertical = AppTheme.spacing.xs
                            ),
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
