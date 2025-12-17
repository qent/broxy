package io.qent.broxy.ui.screens

import AppPrimaryButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiRemoteStatus
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun SettingsScreen(
    ui: UIState,
    notify: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppTheme.spacing.md)
    ) {
        when (ui) {
            UIState.Loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium)
            is UIState.Error -> Text("Error: ${ui.message}", style = MaterialTheme.typography.bodyMedium)
            is UIState.Ready -> SettingsContent(
                requestTimeoutSeconds = ui.requestTimeoutSeconds,
                capabilitiesTimeoutSeconds = ui.capabilitiesTimeoutSeconds,
                capabilitiesRefreshIntervalSeconds = ui.capabilitiesRefreshIntervalSeconds,
                inboundSsePort = ui.inboundSsePort,
                showTrayIcon = ui.showTrayIcon,
                onInboundSsePortSave = { port ->
                    ui.intents.updateInboundSsePort(port)
                    notify("HTTP port saved: $port")
                },
                onRequestTimeoutSave = { seconds ->
                    ui.intents.updateRequestTimeout(seconds)
                    notify("Timeout saved: ${seconds}s")
                },
                onCapabilitiesTimeoutSave = { seconds ->
                    ui.intents.updateCapabilitiesTimeout(seconds)
                    notify("Capabilities timeout saved: ${seconds}s")
                },
                onCapabilitiesRefreshIntervalSave = { seconds ->
                    ui.intents.updateCapabilitiesRefreshInterval(seconds)
                    notify("Refresh interval saved: ${seconds}s")
                },
                onToggleTrayIcon = { enabled ->
                    ui.intents.updateTrayIconVisibility(enabled)
                    notify(if (enabled) "Tray icon enabled" else "Tray icon disabled")
                },
                remote = ui.remote,
                onRemoteServerIdChange = { ui.intents.updateRemoteServerIdentifier(it) },
                onRemoteAuthorize = { ui.intents.startRemoteAuthorization() },
                onRemoteConnect = { ui.intents.connectRemote() },
                onRemoteDisconnect = { ui.intents.disconnectRemote() },
                onRemoteLogout = { ui.intents.logoutRemote() }
            )
        }
    }
}

@Composable
private fun SettingsContent(
    requestTimeoutSeconds: Int,
    capabilitiesTimeoutSeconds: Int,
    capabilitiesRefreshIntervalSeconds: Int,
    inboundSsePort: Int,
    showTrayIcon: Boolean,
    onInboundSsePortSave: (Int) -> Unit,
    onRequestTimeoutSave: (Int) -> Unit,
    onCapabilitiesTimeoutSave: (Int) -> Unit,
    onCapabilitiesRefreshIntervalSave: (Int) -> Unit,
    onToggleTrayIcon: (Boolean) -> Unit,
    remote: UiRemoteConnectionState,
    onRemoteServerIdChange: (String) -> Unit,
    onRemoteAuthorize: () -> Unit,
    onRemoteConnect: () -> Unit,
    onRemoteDisconnect: () -> Unit,
    onRemoteLogout: () -> Unit
) {
    var requestTimeoutInput by rememberSaveable(requestTimeoutSeconds) { mutableStateOf(requestTimeoutSeconds.toString()) }
    var capabilitiesTimeoutInput by rememberSaveable(capabilitiesTimeoutSeconds) { mutableStateOf(capabilitiesTimeoutSeconds.toString()) }
    var capabilitiesRefreshInput by rememberSaveable(capabilitiesRefreshIntervalSeconds) { mutableStateOf(capabilitiesRefreshIntervalSeconds.toString()) }
    var inboundSsePortInput by rememberSaveable(inboundSsePort) { mutableStateOf(inboundSsePort.toString()) }
    var remoteServerId by rememberSaveable(remote.serverIdentifier) { mutableStateOf(remote.serverIdentifier) }

    LaunchedEffect(requestTimeoutSeconds) {
        requestTimeoutInput = requestTimeoutSeconds.toString()
    }

    LaunchedEffect(capabilitiesTimeoutSeconds) {
        capabilitiesTimeoutInput = capabilitiesTimeoutSeconds.toString()
    }

    LaunchedEffect(capabilitiesRefreshIntervalSeconds) {
        capabilitiesRefreshInput = capabilitiesRefreshIntervalSeconds.toString()
    }

    LaunchedEffect(inboundSsePort) {
        inboundSsePortInput = inboundSsePort.toString()
    }

    LaunchedEffect(remote.serverIdentifier) {
        remoteServerId = remote.serverIdentifier
    }

    val parsedRequest = requestTimeoutInput.toLongOrNull()
    val resolvedRequest = parsedRequest?.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()
    val canSaveRequest = resolvedRequest != null && resolvedRequest != requestTimeoutSeconds

    val parsedCapabilities = capabilitiesTimeoutInput.toLongOrNull()
    val resolvedCapabilities = parsedCapabilities?.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()
    val canSaveCapabilities = resolvedCapabilities != null && resolvedCapabilities != capabilitiesTimeoutSeconds

    val parsedRefresh = capabilitiesRefreshInput.toLongOrNull()
    val resolvedRefresh = parsedRefresh?.takeIf { it >= 30 && it <= Int.MAX_VALUE }?.toInt()
    val canSaveRefresh = resolvedRefresh != null && resolvedRefresh != capabilitiesRefreshIntervalSeconds

    val parsedPort = inboundSsePortInput.toLongOrNull()
    val resolvedPort = parsedPort?.takeIf { it in 1..65535 }?.toInt()
    val canSavePort = resolvedPort != null && resolvedPort != inboundSsePort

    val canSaveAny = canSaveRequest || canSaveCapabilities || canSaveRefresh || canSavePort

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
    ) {
        TrayIconSetting(checked = showTrayIcon, onToggle = onToggleTrayIcon)
        RemoteConnectorSetting(
            remote = remote,
            serverId = remoteServerId,
            onServerIdChange = { value ->
                if (value.all { it.isLetterOrDigit() || it in "-._" }) {
                    remoteServerId = value
                    onRemoteServerIdChange(value)
                }
            },
            onAuthorize = onRemoteAuthorize,
            onConnect = onRemoteConnect,
            onDisconnect = onRemoteDisconnect,
            onLogout = onRemoteLogout
        )
        TimeoutSetting(
            title = "HTTP port",
            description = "Port for the local HTTP-streamable MCP endpoint.",
            value = inboundSsePortInput,
            onValueChange = { value ->
                if (value.isEmpty() || value.all { it.isDigit() }) {
                    inboundSsePortInput = value
                }
            }
        )
        TimeoutSetting(
            title = "Request timeout",
            description = "Max time to wait for downstream calls (seconds).",
            value = requestTimeoutInput,
            onValueChange = { value ->
                if (value.isEmpty() || value.all { it.isDigit() }) {
                    requestTimeoutInput = value
                }
            }
        )
        TimeoutSetting(
            title = "Capabilities timeout",
            description = "Max time to wait for server listings (seconds).",
            value = capabilitiesTimeoutInput,
            onValueChange = { value ->
                if (value.isEmpty() || value.all { it.isDigit() }) {
                    capabilitiesTimeoutInput = value
                }
            }
        )
        TimeoutSetting(
            title = "Capabilities refresh",
            description = "Background refresh interval (seconds).",
            value = capabilitiesRefreshInput,
            onValueChange = { value ->
                if (value.isEmpty() || value.all { it.isDigit() }) {
                    capabilitiesRefreshInput = value
                }
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            AppPrimaryButton(
                onClick = {
                    if (canSaveRequest) {
                        resolvedRequest?.let { onRequestTimeoutSave(it) }
                    }
                    if (canSaveCapabilities) {
                        resolvedCapabilities?.let { onCapabilitiesTimeoutSave(it) }
                    }
                    if (canSaveRefresh) {
                        resolvedRefresh?.let { onCapabilitiesRefreshIntervalSave(it) }
                    }
                    if (canSavePort) {
                        resolvedPort?.let { onInboundSsePortSave(it) }
                    }
                },
                enabled = canSaveAny
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun TrayIconSetting(
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    SettingItem(
        title = "Show tray icon",
        description = "Display the broxy icon in the system tray."
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            modifier = Modifier.scale(0.7f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

@Composable
private fun TimeoutSetting(
    title: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    SettingItem(
        title = title,
        description = description
    ) {
        CompactTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.widthIn(min = SettingControlWidth, max = SettingControlWidth)
        )
    }
}

@Composable
private fun SettingItem(
    title: String,
    description: String,
    supportingContent: (@Composable ColumnScope.() -> Unit)? = null,
    control: @Composable RowScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = AppTheme.shapes.card
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.spacing.lg, vertical = AppTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                supportingContent?.invoke(this)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = control
            )
        }
    }
}

@Composable
private fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(32.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        decorationBox = { innerTextField ->
            CompactInputSurface {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = AppTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && label != null) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                }
            }
        },
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
    )
}

@Composable
private fun CompactInputSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.height(32.dp),
        shape = AppTheme.shapes.input,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(AppTheme.strokeWidths.thin, MaterialTheme.colorScheme.outline),
        content = content
    )
}

private val SettingControlWidth: Dp = 140.dp

@Composable
private fun RemoteConnectorSetting(
    remote: UiRemoteConnectionState,
    serverId: String,
    onServerIdChange: (String) -> Unit,
    onAuthorize: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onLogout: () -> Unit
) {
    val statusLabel = when (remote.status) {
        UiRemoteStatus.NotAuthorized -> "Not authorized"
        UiRemoteStatus.Authorizing -> "Authorizing..."
        UiRemoteStatus.Registering -> "Registering..."
        UiRemoteStatus.Registered -> "Registered"
        UiRemoteStatus.WsConnecting -> "Connecting..."
        UiRemoteStatus.WsOnline -> "WS online"
        UiRemoteStatus.WsOffline -> "WS offline"
        UiRemoteStatus.Error -> "Error"
    }
    val statusDetail = remote.message ?: remote.email ?: ""
    val isAuthorized = remote.hasCredentials
    val isBusy = remote.status in setOf(UiRemoteStatus.Authorizing, UiRemoteStatus.Registering)
    val isConnected = remote.status == UiRemoteStatus.WsOnline || remote.status == UiRemoteStatus.WsConnecting

    SettingItem(
        title = "Remote MCP proxy",
        description = "Proxy connection status.",
        supportingContent = {
            Text(
                if (statusDetail.isNotBlank()) "$statusLabel — $statusDetail" else statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (isConnected) AppTheme.extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
         if (isAuthorized) {
             Row(
                 horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                 verticalAlignment = Alignment.CenterVertically
             ) {
                 CompactTextField(
                     value = serverId,
                     onValueChange = onServerIdChange,
                     modifier = Modifier.widthIn(min = 120.dp, max = 120.dp),
                     label = "Server ID"
                 )
                 AppPrimaryButton(
                     onClick = if (isConnected) onDisconnect else onConnect,
                     enabled = !isBusy,
                     modifier = Modifier.height(32.dp)
                 ) {
                     Text(if (isConnected) "Disconnect" else "Connect", style = MaterialTheme.typography.labelSmall)
                 }
                 AppPrimaryButton(
                     onClick = onLogout,
                     enabled = !isBusy,
                     modifier = Modifier.height(32.dp)
                 ) {
                     Text("Logout", style = MaterialTheme.typography.labelSmall)
                 }
             }
         } else {
             AppPrimaryButton(
                 onClick = onAuthorize,
                 enabled = !isBusy,
                 modifier = Modifier.height(32.dp)
             ) {
                 Text("Authorize", style = MaterialTheme.typography.labelSmall)
             }
         }
    }
}
