package io.qent.broxy.ui.screens

import AppPrimaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import io.qent.broxy.ui.adapter.models.UiRemoteStatus
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.theme.ThemeStyle

@Composable
fun SettingsScreen(
    ui: UIState,
    themeStyle: ThemeStyle,
    onThemeStyleChange: (ThemeStyle) -> Unit,
    notify: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg)
    ) {
        when (ui) {
            UIState.Loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium)
            is UIState.Error -> Text("Error: ${ui.message}", style = MaterialTheme.typography.bodyMedium)
            is UIState.Ready -> SettingsContent(
                requestTimeoutSeconds = ui.requestTimeoutSeconds,
                capabilitiesTimeoutSeconds = ui.capabilitiesTimeoutSeconds,
                capabilitiesRefreshIntervalSeconds = ui.capabilitiesRefreshIntervalSeconds,
                showTrayIcon = ui.showTrayIcon,
                themeStyle = themeStyle,
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
                onThemeStyleChange = onThemeStyleChange,
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
    showTrayIcon: Boolean,
    themeStyle: ThemeStyle,
    onRequestTimeoutSave: (Int) -> Unit,
    onCapabilitiesTimeoutSave: (Int) -> Unit,
    onCapabilitiesRefreshIntervalSave: (Int) -> Unit,
    onToggleTrayIcon: (Boolean) -> Unit,
    onThemeStyleChange: (ThemeStyle) -> Unit,
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
    val canSaveAny = canSaveRequest || canSaveCapabilities || canSaveRefresh

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg)
    ) {
        ThemeStyleSetting(
            themeStyle = themeStyle,
            onThemeStyleChange = onThemeStyleChange
        )
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
            title = "Request timeout",
            description = "Set how long broxy waits for downstream MCP calls before timing out.",
            label = "Seconds",
            value = requestTimeoutInput,
            onValueChange = { value ->
                if (value.isEmpty() || value.all { it.isDigit() }) {
                    requestTimeoutInput = value
                }
            }
        )
        TimeoutSetting(
            title = "Capabilities timeout",
            description = "Limit how long broxy waits for tool/resource/prompt listings when connecting to servers.",
            label = "Seconds",
            value = capabilitiesTimeoutInput,
            onValueChange = { value ->
                if (value.isEmpty() || value.all { it.isDigit() }) {
                    capabilitiesTimeoutInput = value
                }
            }
        )
        TimeoutSetting(
            title = "Capabilities refresh interval",
            description = "Control how often cached MCP server capabilities are refreshed in the background.",
            label = "Seconds",
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
    SettingCard(
        title = "Show tray icon",
        description = "Display the broxy icon in the system tray (macOS menu bar)."
    ) {
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun TimeoutSetting(
    title: String,
    description: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    SettingCard(
        title = title,
        description = description
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.widthIn(min = SettingControlWidth, max = SettingControlWidth),
            singleLine = true,
            label = { Text(label) }
        )
    }
}

@Composable
private fun ThemeStyleSetting(
    themeStyle: ThemeStyle,
    onThemeStyleChange: (ThemeStyle) -> Unit
) {
    SettingCard(
        title = "Appearance",
        description = "Choose whether broxy follows the system theme or forces light or dark mode."
    ) {
        ThemeStyleDropdown(
            modifier = Modifier.widthIn(min = SettingControlWidth, max = SettingControlWidth),
            selected = themeStyle,
            onSelected = onThemeStyleChange
        )
    }
}

@Composable
private fun SettingCard(
    title: String,
    description: String,
    supportingContent: (@Composable ColumnScope.() -> Unit)? = null,
    control: @Composable RowScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = AppTheme.shapes.card
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeStyleDropdown(
    modifier: Modifier = Modifier,
    selected: ThemeStyle,
    onSelected: (ThemeStyle) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(ThemeStyle.System, ThemeStyle.Dark, ThemeStyle.Light)

    val label = when (selected) {
        ThemeStyle.System -> "Match system"
        ThemeStyle.Dark -> "Dark"
        ThemeStyle.Light -> "Light"
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = modifier.menuAnchor()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                val optionLabel = when (option) {
                    ThemeStyle.System -> "Match system"
                    ThemeStyle.Dark -> "Dark"
                    ThemeStyle.Light -> "Light"
                }
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        expanded = false
                        if (option != selected) {
                            onSelected(option)
                        }
                    }
                )
            }
        }
    }
}

private val SettingControlWidth: Dp = 180.dp

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

    SettingCard(
        title = "Remote MCP proxy",
        description = "Authorize broxy to connect to the remote MCP proxy backend over WebSocket.",
        supportingContent = {
            Text(
                "Server identifier is shared with the backend. Change it to avoid collisions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
            OutlinedTextField(
                value = serverId,
                onValueChange = onServerIdChange,
                label = { Text("Server identifier") },
                singleLine = true,
                modifier = Modifier.widthIn(min = 220.dp, max = 260.dp)
            )
            Text(
                text = "$statusLabel${if (statusDetail.isNotBlank()) " — $statusDetail" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isAuthorized) {
                AppPrimaryButton(
                    onClick = if (isConnected) onDisconnect else onConnect,
                    enabled = !isBusy
                ) {
                    Text(if (isConnected) "Disconnect" else "Connect")
                }
                AppPrimaryButton(
                    onClick = onLogout,
                    enabled = !isBusy
                ) {
                    Text("Logout")
                }
            } else {
                AppPrimaryButton(
                    onClick = onAuthorize,
                    enabled = !isBusy
                ) {
                    Text("Authorize")
                }
            }
        }
    }
}
