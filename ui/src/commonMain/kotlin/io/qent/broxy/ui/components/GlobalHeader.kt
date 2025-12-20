@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.qent.broxy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiRemoteStatus
import io.qent.broxy.ui.adapter.store.Intents
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
        actions = {
            if (ui is UIState.Ready && ui.remoteEnabled) {
                RemoteHeaderActions(remote = ui.remote, intents = ui.intents)
            }
        },
    )
}

@Composable
private fun RemoteHeaderActions(
    remote: UiRemoteConnectionState,
    intents: Intents,
    modifier: Modifier = Modifier,
) {
    val isAuthorized = remote.hasCredentials
    val isBusy = remote.status in setOf(UiRemoteStatus.Authorizing, UiRemoteStatus.Registering)
    val isConnected = remote.status == UiRemoteStatus.WsOnline || remote.status == UiRemoteStatus.WsConnecting

    if (!isAuthorized) {
        GradientAuthButton(
            onClick = { intents.startRemoteAuthorization() },
            enabled = !isBusy,
            modifier = modifier.padding(end = AppTheme.spacing.sm),
        )
        return
    }

    Row(
        modifier = modifier.padding(end = AppTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
    ) {
        ConnectionStatusDot(status = remote.status)
        IconButton(
            onClick = { if (isConnected) intents.disconnectRemote() else intents.connectRemote() },
            enabled = !isBusy,
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Outlined.LinkOff else Icons.Outlined.Link,
                contentDescription = if (isConnected) "Disconnect remote" else "Connect remote",
            )
        }
        IconButton(
            onClick = { intents.logoutRemote() },
            enabled = !isBusy,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Logout,
                contentDescription = "Logout remote",
            )
        }
    }
}

@Composable
private fun GradientAuthButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val gradient =
        Brush.horizontalGradient(
            listOf(
                Color(0xFF38BDF8),
                Color(0xFF22D3EE),
                Color(0xFF34D399),
            ),
        )
    val shape = AppTheme.shapes.pill
    val alpha = if (enabled) 1f else 0.6f

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = Color.Transparent,
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier.height(32.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .alpha(alpha)
                    .background(brush = gradient, shape = shape)
                    .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            Text("Authorize", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ConnectionStatusDot(
    status: UiRemoteStatus,
    modifier: Modifier = Modifier,
) {
    val color =
        when (status) {
            UiRemoteStatus.WsOnline -> AppTheme.extendedColors.success
            UiRemoteStatus.WsConnecting -> Color(0xFFF59E0B)
            UiRemoteStatus.WsOffline, UiRemoteStatus.Error -> MaterialTheme.colorScheme.error
            UiRemoteStatus.Registered -> MaterialTheme.colorScheme.onSurfaceVariant
            UiRemoteStatus.NotAuthorized, UiRemoteStatus.Authorizing, UiRemoteStatus.Registering ->
                MaterialTheme.colorScheme.outline
        }

    Box(
        modifier =
            modifier
                .size(10.dp)
                .background(color, CircleShape)
                .border(AppTheme.strokeWidths.hairline, MaterialTheme.colorScheme.outline, CircleShape),
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
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
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
