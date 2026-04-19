@file:Suppress("FunctionNaming", "TooManyFunctions")
@file:OptIn(ExperimentalMaterial3Api::class)

package io.qent.broxy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiPreset
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiRemoteStatus
import io.qent.broxy.ui.adapter.store.Intents
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.presets.resolvePresetCapabilityStatus
import io.qent.broxy.ui.strings.AppStrings
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.theme.PRESET_STATUS_DOT_NO_CAPABILITIES_HEX
import io.qent.broxy.ui.theme.PRESET_STATUS_DOT_PARTIAL_HEX

private val GLOBAL_HEADER_HEIGHT = 40.dp
private val HEADER_CONTROL_HEIGHT = 32.dp
private val HEADER_ICON_SIZE = 18.dp
private val PRESET_SELECTOR_WIDTH = 220.dp
private val REMOTE_ACTIONS_WIDTH = 280.dp
private const val AUTH_GRADIENT_DISABLED_ALPHA = 0.45f
private const val AUTH_GRADIENT_START = 0xFF3B82F6
private const val AUTH_GRADIENT_END = 0xFF8B5CF6
private const val PRESET_MANAGEMENT_AI_TOKEN = "AI"
private const val PRESET_MANAGEMENT_ROCK_ICON = "🤘"
private const val PRESET_MANAGEMENT_ROCK_ICON_DISABLED_ALPHA = 0.45f
private const val AGENTIC_FIRST_LETTER_COLOR_HEX = 0xFF2563EB
private const val REMOTE_CONNECTING_COLOR_HEX = 0xFF38BDF8
private const val REMOTE_OFFLINE_COLOR_HEX = 0xFFF59E0B
private val PRESET_STATUS_DOT_NO_CAPABILITIES_COLOR = Color(PRESET_STATUS_DOT_NO_CAPABILITIES_HEX)
private val PRESET_STATUS_DOT_PARTIAL_COLOR = Color(PRESET_STATUS_DOT_PARTIAL_HEX)

@Composable
fun GlobalHeader(
    ui: UIState,
    notify: (String) -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    dragArea: @Composable (Modifier) -> Unit = { dragModifier -> Spacer(dragModifier) },
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
@Suppress("LongMethod")
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
                modifier = Modifier.size(HEADER_ICON_SIZE),
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
                modifier = Modifier.size(HEADER_ICON_SIZE),
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
    val gradientAlpha = if (enabled) 1f else AUTH_GRADIENT_DISABLED_ALPHA
    val gradient =
        Brush.horizontalGradient(
            listOf(Color(AUTH_GRADIENT_START), Color(AUTH_GRADIENT_END)).map { it.copy(alpha = gradientAlpha) },
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
        UiRemoteStatus.WsConnecting -> Color(REMOTE_CONNECTING_COLOR_HEX)
        UiRemoteStatus.Error -> MaterialTheme.colorScheme.error
        UiRemoteStatus.WsOffline, UiRemoteStatus.Registered -> Color(REMOTE_OFFLINE_COLOR_HEX)
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
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun PresetDropdown(
    ui: UIState,
    notify: (String) -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "arrowRotation",
    )

    // Shape logic for unified block look
    val defaultShape = AppTheme.shapes.input
    val headerShape =
        if (expanded) {
            defaultShape.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
        } else {
            defaultShape
        }
    val dropdownShape =
        if (expanded) {
            defaultShape.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp))
        } else {
            defaultShape
        }

    // We might need a tiny negative offset if borders are doubled.
    // ExposedDropdownMenu usually aligns perfectly; keep as-is for now.

    when (ui) {
        UIState.Loading -> HeaderField(text = strings.loadingInline, modifier = modifier.width(width))

        is UIState.Error -> HeaderField(text = strings.unavailable, modifier = modifier.width(width))

        is UIState.Ready -> {
            val activePresetId = ui.activeProxyPresetId
            val normalizedActiveId = activePresetId ?: UiPresetCore.EMPTY_PRESET_ID
            val currentName = resolvePresetName(normalizedActiveId, ui.presets, strings, ui.agenticModeEnabled)
            val isPresetManagementSelected = normalizedActiveId == UiPresetCore.PRESET_MANAGEMENT_ID
            val presetManagementLabel = if (ui.agenticModeEnabled) strings.agenticMode else strings.presetManagement
            val presetManagementLabelText = presetManagementLabelText(presetManagementLabel, ui.agenticModeEnabled)
            val activePreset = ui.presets.firstOrNull { it.id == normalizedActiveId }
            val enabledServerIds =
                ui.servers
                    .asSequence()
                    .filter { it.enabled }
                    .map { it.id }
                    .toSet()
            val capabilityStatus =
                activePreset?.let { preset ->
                    resolvePresetCapabilityStatus(
                        preset = preset,
                        enabledServerIds = enabledServerIds,
                    )
                }
            val statusDotColor =
                when {
                    capabilityStatus?.hasNoAvailableCapabilities == true -> PRESET_STATUS_DOT_NO_CAPABILITIES_COLOR
                    capabilityStatus?.hasCapabilityWarning == true -> PRESET_STATUS_DOT_PARTIAL_COLOR
                    else -> null
                }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = modifier.width(width),
            ) {
                HeaderField(
                    text = currentName,
                    textContent =
                        if (isPresetManagementSelected) {
                            { textModifier ->
                                Text(
                                    text = presetManagementLabelText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTheme.colors.onSurface,
                                    modifier = textModifier,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            null
                        },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                    shape = headerShape,
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                        ) {
                            if (statusDotColor != null) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(8.dp)
                                            .background(color = statusDotColor, shape = CircleShape),
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.ExpandMore,
                                contentDescription = strings.openPresetMenu,
                                modifier =
                                    Modifier
                                        .size(18.dp)
                                        .rotate(arrowRotation),
                            )
                        }
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
                            PaddingValues(
                                horizontal = AppTheme.spacing.md,
                                vertical = AppTheme.spacing.xxs,
                            ),
                        onClick = {
                            expanded = false
                            ui.intents.selectProxyPreset(UiPresetCore.EMPTY_PRESET_ID)
                            notify(strings.presetCleared)
                        },
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                strings.allEnabledServers,
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.colors.onSurface,
                            )
                        },
                        contentPadding =
                            PaddingValues(
                                horizontal = AppTheme.spacing.md,
                                vertical = AppTheme.spacing.xxs,
                            ),
                        onClick = {
                            expanded = false
                            ui.intents.selectProxyPreset(UiPresetCore.ALL_ENABLED_PRESET_ID)
                            notify(strings.presetSelected(strings.allEnabledServers))
                        },
                    )

                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                            ) {
                                Text(
                                    text = presetManagementLabelText,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTheme.colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                PresetManagementRockToggle(
                                    enabled = ui.agenticModeEnabled,
                                    onToggle = {
                                        ui.intents.setPresetManagementAgenticMode(!ui.agenticModeEnabled)
                                    },
                                )
                            }
                        },
                        contentPadding =
                            PaddingValues(
                                horizontal = AppTheme.spacing.md,
                                vertical = AppTheme.spacing.xxs,
                            ),
                        onClick = {
                            expanded = false
                            ui.intents.selectProxyPreset(UiPresetCore.PRESET_MANAGEMENT_ID)
                            notify(strings.presetSelected(presetManagementLabel))
                        },
                    )

                    if (ui.presets.isNotEmpty()) {
                        HorizontalDivider(
                            modifier =
                                Modifier.padding(
                                    horizontal = AppTheme.spacing.md,
                                    vertical = AppTheme.spacing.xs,
                                ),
                            thickness = AppTheme.strokeWidths.thin,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    ui.presets.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    p.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTheme.colors.onSurface,
                                )
                            },
                            contentPadding =
                                PaddingValues(
                                    horizontal = AppTheme.spacing.md,
                                    vertical = AppTheme.spacing.xxs,
                                ),
                            onClick = {
                                expanded = false
                                ui.intents.selectProxyPreset(p.id)
                                notify(strings.presetSelected(p.name))
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
    textContent: (@Composable (Modifier) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier =
            modifier
                .height(HEADER_CONTROL_HEIGHT),
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
            if (textContent != null) {
                textContent(Modifier.weight(1f))
            } else {
                Text(
                    text = text,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) trailing()
        }
    }
}

@Composable
private fun PresetManagementRockToggle(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Text(
        text = PRESET_MANAGEMENT_ROCK_ICON,
        modifier =
            Modifier
                .alpha(if (enabled) 1f else PRESET_MANAGEMENT_ROCK_ICON_DISABLED_ALPHA)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                ),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

internal fun presetManagementLabelText(
    label: String,
    agenticModeEnabled: Boolean,
) = buildAnnotatedString {
    if (label.isEmpty()) {
        append(label)
        return@buildAnnotatedString
    }
    val highlightStyle =
        SpanStyle(
            color = Color(AGENTIC_FIRST_LETTER_COLOR_HEX),
            fontWeight = FontWeight.Bold,
        )
    if (label.startsWith("$PRESET_MANAGEMENT_AI_TOKEN ")) {
        withStyle(highlightStyle) {
            append(PRESET_MANAGEMENT_AI_TOKEN)
        }
        append(label.removePrefix(PRESET_MANAGEMENT_AI_TOKEN))
        return@buildAnnotatedString
    }
    if (!agenticModeEnabled) {
        append(label)
        return@buildAnnotatedString
    }
    withStyle(
        highlightStyle,
    ) {
        append(label.first())
    }
    append(label.drop(1))
}

private fun resolvePresetName(
    presetId: String,
    presets: List<UiPreset>,
    strings: AppStrings,
    agenticModeEnabled: Boolean,
): String =
    when (presetId) {
        UiPresetCore.EMPTY_PRESET_ID -> strings.noPreset
        UiPresetCore.ALL_ENABLED_PRESET_ID -> strings.allEnabledServers
        UiPresetCore.PRESET_MANAGEMENT_ID -> if (agenticModeEnabled) strings.agenticMode else strings.presetManagement
        else -> presets.firstOrNull { it.id == presetId }?.name ?: presetId
    }
