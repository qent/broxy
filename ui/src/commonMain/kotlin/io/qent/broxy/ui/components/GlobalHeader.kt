@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.qent.broxy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiRemoteStatus
import io.qent.broxy.ui.adapter.store.Intents
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

private val GLOBAL_HEADER_HEIGHT = 40.dp
private val HEADER_CONTROL_HEIGHT = 32.dp
private val PRESET_SELECTOR_WIDTH = 220.dp
private val REMOTE_ACTIONS_WIDTH = 280.dp

@Composable
fun GlobalHeader(
    ui: UIState,
    notify: (String) -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    dragArea: @Composable (Modifier) -> Unit = { modifier -> Spacer(modifier) },
    modifier: Modifier = Modifier,
) {
    val showRemoteActions = ui is UIState.Ready && ui.remoteEnabled
    CenterAlignedTopAppBar(
        modifier = modifier.height(GLOBAL_HEADER_HEIGHT),
        expandedHeight = GLOBAL_HEADER_HEIGHT,
        colors = colors,
        navigationIcon = {
            if (showRemoteActions) {
                Box(
                    modifier = Modifier.width(REMOTE_ACTIONS_WIDTH).fillMaxHeight(),
                ) {
                    dragArea(Modifier.fillMaxSize())
                }
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth().height(GLOBAL_HEADER_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dragArea(Modifier.weight(1f).fillMaxHeight())
                Box(
                    modifier = Modifier.width(PRESET_SELECTOR_WIDTH).padding(top = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PresetDropdown(ui = ui, notify = notify, width = PRESET_SELECTOR_WIDTH)
                }
                dragArea(Modifier.weight(1f).fillMaxHeight())
            }
        },
        actions = {
            if (showRemoteActions) {
                Box(
                    modifier = Modifier.width(REMOTE_ACTIONS_WIDTH).fillMaxHeight(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    RemoteHeaderActions(remote = ui.remote, intents = ui.intents)
                }
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
    val strings = LocalStrings.current
    val isAuthorized = remote.hasCredentials
    val isBusy = remote.status in setOf(UiRemoteStatus.Authorizing, UiRemoteStatus.Registering)
    val isConnected = remote.status == UiRemoteStatus.WsOnline || remote.status == UiRemoteStatus.WsConnecting
    val statusColor = remoteStatusColor(remote.status)
    val cloudColors =
        IconButtonDefaults.iconButtonColors(
            contentColor = statusColor,
            disabledContentColor = statusColor.copy(alpha = 0.4f),
        )
    val accountLabel = remote.email ?: remote.serverIdentifier
    val accountInteraction = remember { MutableInteractionSource() }
    val isAccountHovered by accountInteraction.collectIsHoveredAsState()
    val cloudContentDescription = if (isConnected) strings.remoteDisconnect else strings.remoteConnect

    if (!isAuthorized) {
        AuthButton(
            onClick = { intents.startRemoteAuthorization() },
            enabled = !isBusy,
            modifier = modifier.padding(end = AppTheme.spacing.md),
        )
        return
    }

    Row(
        modifier =
            modifier
                .padding(end = AppTheme.spacing.xs)
                .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier =
                Modifier
                    .height(HEADER_CONTROL_HEIGHT)
                    .wrapContentWidth()
                    .background(
                        color =
                            if (isAccountHovered && !isBusy) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            } else {
                                Color.Transparent
                            },
                        shape = AppTheme.shapes.input,
                    ).hoverable(accountInteraction)
                    .clickable(
                        enabled = !isBusy,
                        interactionSource = accountInteraction,
                        indication = null,
                    ) {
                        intents.openRemotePortal()
                    },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = accountLabel,
                modifier = Modifier.padding(horizontal = AppTheme.spacing.sm),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        IconButton(
            onClick = { if (isConnected) intents.disconnectRemote() else intents.connectRemote() },
            enabled = !isBusy,
            modifier = Modifier.size(HEADER_CONTROL_HEIGHT),
            colors = cloudColors,
        ) {
            CloudStatusIcon(
                status = remote.status,
                contentDescription = cloudContentDescription,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(
            onClick = { intents.logoutRemote() },
            enabled = !isBusy,
            modifier = Modifier.size(HEADER_CONTROL_HEIGHT),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Logout,
                contentDescription = strings.remoteLogout,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AuthButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    val gradientAlpha = if (enabled) 1f else 0.45f
    val gradient =
        Brush.horizontalGradient(
            listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6)).map { it.copy(alpha = gradientAlpha) },
        )

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = AppTheme.shapes.input,
        color = colors.surface,
        contentColor = colors.primary,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, gradient),
        modifier = modifier.height(HEADER_CONTROL_HEIGHT - 1.dp),
    ) {
        Box(
            modifier =
                Modifier.padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                strings.authorize,
                style = MaterialTheme.typography.labelSmall.copy(brush = gradient),
            )
        }
    }
}

@Composable
private fun remoteStatusColor(status: UiRemoteStatus): Color =
    when (status) {
        UiRemoteStatus.WsOnline -> AppTheme.extendedColors.success
        UiRemoteStatus.WsConnecting -> Color(0xFF38BDF8)
        UiRemoteStatus.Error -> MaterialTheme.colorScheme.error
        UiRemoteStatus.WsOffline, UiRemoteStatus.Registered -> Color(0xFFF59E0B)
        UiRemoteStatus.NotAuthorized, UiRemoteStatus.Authorizing, UiRemoteStatus.Registering ->
            MaterialTheme.colorScheme.outline
    }

@Composable
private fun CloudStatusIcon(
    status: UiRemoteStatus,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    when (status) {
        UiRemoteStatus.WsOnline ->
            Icon(
                imageVector = Icons.Outlined.CloudDone,
                contentDescription = contentDescription,
                modifier = modifier,
            )
        UiRemoteStatus.WsConnecting ->
            Icon(
                imageVector = Icons.Outlined.Cloud,
                contentDescription = contentDescription,
                modifier = modifier,
            )
        UiRemoteStatus.Error -> CloudAlertIcon(contentDescription = contentDescription, modifier = modifier)
        UiRemoteStatus.WsOffline,
        UiRemoteStatus.Registered,
        UiRemoteStatus.NotAuthorized,
        UiRemoteStatus.Authorizing,
        UiRemoteStatus.Registering,
        ->
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = contentDescription,
                modifier = modifier,
            )
    }
}

@Composable
private fun CloudAlertIcon(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Icon(
            imageVector = Icons.Outlined.Cloud,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
        )
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(10.dp).align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun PresetDropdown(
    ui: UIState,
    notify: (String) -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
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
        UIState.Loading -> HeaderField(text = strings.loadingInline, modifier = modifier.width(width))

        is UIState.Error -> HeaderField(text = strings.unavailable, modifier = modifier.width(width))

        is UIState.Ready -> {
            val selectedPresetId = ui.selectedPresetId
            val currentName =
                ui.presets.firstOrNull { it.id == selectedPresetId }?.name
                    ?: if (selectedPresetId == null) strings.noPreset else selectedPresetId

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
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                    shape = headerShape,
                    trailing = {
                        Icon(
                            imageVector = Icons.Outlined.ExpandMore,
                            contentDescription = strings.openPresetMenu,
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
                                strings.noPreset,
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
                                notify(strings.presetCleared)
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
                                    notify(strings.presetSelected(p.name))
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
                .height(HEADER_CONTROL_HEIGHT)
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
