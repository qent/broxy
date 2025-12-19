@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.qent.broxy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.theme.AppTheme

private val GLOBAL_HEADER_HEIGHT = 40.dp
private val PRESET_SELECTOR_WIDTH = 220.dp

@Composable
fun GlobalHeader(
    ui: UIState,
    notify: (String) -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
    modifier: Modifier = Modifier,
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        expandedHeight = GLOBAL_HEADER_HEIGHT,
        colors = colors,
        title = {
            PresetDropdown(ui = ui, notify = notify, width = PRESET_SELECTOR_WIDTH)
        },
        actions = {},
    )
}

@Composable
private fun PresetDropdown(
    ui: UIState,
    notify: (String) -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "arrowRotation")

    // Shape logic for unified block look
    val defaultShape = AppTheme.shapes.input
    val headerShape =
        if (expanded) defaultShape.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)) else defaultShape
    val dropdownShape =
        if (expanded) defaultShape.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp)) else defaultShape

    // We still might need a tiny negative offset if the borders are doubled, but ExposedDropdownMenu usually aligns perfectly.
    // To be safe and ensure the "unified" single-border look, we can check.
    // Usually ExposedDropdownMenu places the menu directly below.
    // If we want to overlap the 1dp border, we might arguably need -1dp offset.
    // Let's try standard first, but with the specific shapes it should look connected.

    when (ui) {
        UIState.Loading -> HeaderField(text = "Loading…", modifier = modifier.width(width))

        is UIState.Error -> HeaderField(text = "Unavailable", modifier = modifier.width(width))

        is UIState.Ready -> {
            val selectedPresetId = ui.selectedPresetId
            val currentName =
                ui.presets.firstOrNull { it.id == selectedPresetId }?.name
                    ?: if (selectedPresetId == null) "No preset" else selectedPresetId

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = modifier.width(width),
            ) {
                HeaderField(
                    text = currentName,
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    shape = headerShape,
                    trailing = {
                        Icon(
                            imageVector = Icons.Outlined.ExpandMore,
                            contentDescription = "Open preset menu",
                            modifier =
                                Modifier
                                    .size(18.dp)
                                    .rotate(arrowRotation),
                        )
                    },
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier =
                        Modifier
                            .background(color = AppTheme.colors.surface, shape = dropdownShape)
                            .border(AppTheme.strokeWidths.thin, AppTheme.colors.outline, dropdownShape),
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "No preset",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.colors.onSurface,
                            )
                        },
                        contentPadding =
                            androidx.compose.foundation.layout.PaddingValues(
                                horizontal = AppTheme.spacing.md,
                                vertical = AppTheme.spacing.xxs,
                            ),
                        onClick = {
                            expanded = false
                            if (ui.selectedPresetId != null) {
                                ui.intents.selectProxyPreset(null)
                                notify("Preset cleared")
                            }
                        },
                    )

                    ui.presets.forEach { p ->
                        val isSelected = p.id == selectedPresetId
                        DropdownMenuItem(
                            text = {
                                Text(
                                    p.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTheme.colors.onSurface,
                                )
                            },
                            contentPadding =
                                androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = AppTheme.spacing.md,
                                    vertical = AppTheme.spacing.xxs,
                                ),
                            onClick = {
                                expanded = false
                                if (!isSelected) {
                                    ui.intents.selectProxyPreset(p.id)
                                    notify("Preset selected: ${p.name}")
                                }
                            },
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
    shape: Shape = AppTheme.shapes.input,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val clickModifier =
        if (onClick == null) {
            Modifier
        } else {
            Modifier.clickable(onClick = onClick)
        }
    Surface(
        modifier =
            modifier
                .height(32.dp)
                .then(clickModifier),
        shape = shape,
        color = colors.surface,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(AppTheme.strokeWidths.thin, colors.outline),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailing != null) trailing()
        }
    }
}
