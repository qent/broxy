@file:OptIn(ExperimentalMaterial3Api::class)

package io.qent.broxy.ui.screens

import AppPrimaryButton
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.theme.ThemeStyle

@Composable
fun SettingsScreen(
    ui: UIState,
    themeStyle: ThemeStyle,
    onThemeStyleChange: (ThemeStyle) -> Unit,
    notify: (String) -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md)) {
        when (ui) {
            UIState.Loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium)
            is UIState.Error -> Text("Error: ${ui.message}", style = MaterialTheme.typography.bodyMedium)
            is UIState.Ready ->
                SettingsContent(
                    modifier = Modifier.fillMaxSize(),
                    themeStyle = themeStyle,
                    onThemeStyleChange = onThemeStyleChange,
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
                    onOpenLogsFolder = {
                        ui.intents.openLogsFolder()
                        notify("Opening logs folder…")
                    },
                )
        }
    }
}

@Composable
private fun SettingsContent(
    modifier: Modifier = Modifier,
    themeStyle: ThemeStyle,
    onThemeStyleChange: (ThemeStyle) -> Unit,
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
    onOpenLogsFolder: () -> Unit,
) {
    var requestTimeoutInput by rememberSaveable(requestTimeoutSeconds) { mutableStateOf(requestTimeoutSeconds.toString()) }
    var capabilitiesTimeoutInput by rememberSaveable(capabilitiesTimeoutSeconds) {
        mutableStateOf(
            capabilitiesTimeoutSeconds.toString(),
        )
    }
    var capabilitiesRefreshInput by rememberSaveable(capabilitiesRefreshIntervalSeconds) {
        mutableStateOf(
            capabilitiesRefreshIntervalSeconds.toString(),
        )
    }
    var inboundSsePortInput by rememberSaveable(inboundSsePort) { mutableStateOf(inboundSsePort.toString()) }

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

    val scrollState = rememberScrollState()
    val saveButtonHeight = 32.dp
    val contentBottomPadding = AppTheme.spacing.lg + saveButtonHeight + AppTheme.spacing.md

    Box(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(bottom = contentBottomPadding),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AppTheme.spacing.sm))
            ThemeSetting(
                themeStyle = themeStyle,
                onThemeStyleChange = onThemeStyleChange,
            )
            TrayIconSetting(checked = showTrayIcon, onToggle = onToggleTrayIcon)
            LogsSetting(onOpenFolder = onOpenLogsFolder)
            TimeoutSetting(
                title = "HTTP port",
                description = "Port for the local HTTP-streamable MCP endpoint.",
                value = inboundSsePortInput,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        inboundSsePortInput = value
                    }
                },
            )
            TimeoutSetting(
                title = "Request timeout",
                description = "Max time to wait for downstream calls (seconds).",
                value = requestTimeoutInput,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        requestTimeoutInput = value
                    }
                },
            )
            TimeoutSetting(
                title = "Capabilities timeout",
                description = "Max time to wait for server listings (seconds).",
                value = capabilitiesTimeoutInput,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        capabilitiesTimeoutInput = value
                    }
                },
            )
            TimeoutSetting(
                title = "Capabilities refresh",
                description = "Background refresh interval (seconds).",
                value = capabilitiesRefreshInput,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        capabilitiesRefreshInput = value
                    }
                },
            )
            Spacer(Modifier.height(AppTheme.spacing.md))
        }

        Box(Modifier.padding(vertical = AppTheme.spacing.md).align(Alignment.BottomEnd)) {
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
                enabled = canSaveAny,
                modifier = Modifier.height(saveButtonHeight),
            ) {
                Text("Save", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TrayIconSetting(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    SettingItem(
        title = "Show tray icon",
        description = "Display the broxy icon in the system tray.",
    ) {
        SettingControlBox {
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                modifier = Modifier.scale(0.7f),
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
            )
        }
    }
}

@Composable
private fun LogsSetting(onOpenFolder: () -> Unit) {
    SettingItem(
        title = "Logs",
        description = "Application logs are stored in the logs/ folder next to the configuration files.",
    ) {
        AppPrimaryButton(
            onClick = onOpenFolder,
            modifier = Modifier.width(SettingControlWidth).height(32.dp),
        ) {
            Text("Open folder", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TimeoutSetting(
    title: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    SettingItem(
        title = title,
        description = description,
    ) {
        SettingControlBox {
            CompactTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingItem(
    title: String,
    description: String,
    supportingContent: (@Composable ColumnScope.() -> Unit)? = null,
    control: @Composable RowScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = AppTheme.shapes.card,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.lg, vertical = AppTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                supportingContent?.invoke(this)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = control,
            )
        }
    }
}

@Composable
private fun ThemeSetting(
    themeStyle: ThemeStyle,
    onThemeStyleChange: (ThemeStyle) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label =
        when (themeStyle) {
            ThemeStyle.Dark -> "Dark"
            ThemeStyle.Light -> "Light"
        }

    val fieldShape =
        if (expanded) {
            AppTheme.shapes.input.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
        } else {
            AppTheme.shapes.input
        }
    val dropdownShape =
        if (expanded) {
            AppTheme.shapes.input.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp))
        } else {
            AppTheme.shapes.input
        }

    SettingItem(
        title = "Theme",
        description = "Choose light or dark appearance.",
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.widthIn(min = SettingControlWidth, max = SettingControlWidth),
        ) {
            ThemeDropdownField(
                text = label,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                expanded = expanded,
                shape = fieldShape,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier =
                    Modifier
                        .background(color = MaterialTheme.colorScheme.surface, shape = dropdownShape)
                        .border(BorderStroke(AppTheme.strokeWidths.thin, MaterialTheme.colorScheme.outline), dropdownShape),
            ) {
                ThemeDropdownItem(
                    text = "Dark",
                    onClick = {
                        expanded = false
                        if (themeStyle != ThemeStyle.Dark) onThemeStyleChange(ThemeStyle.Dark)
                    },
                )
                ThemeDropdownItem(
                    text = "Light",
                    onClick = {
                        expanded = false
                        if (themeStyle != ThemeStyle.Light) onThemeStyleChange(ThemeStyle.Light)
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeDropdownField(
    text: String,
    modifier: Modifier = Modifier,
    expanded: Boolean,
    shape: Shape = AppTheme.shapes.input,
) {
    Surface(
        modifier = modifier.height(32.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(AppTheme.strokeWidths.thin, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
    }
}

@Composable
private fun ThemeDropdownItem(
    text: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodySmall) },
        contentPadding = PaddingValues(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.xxs),
        onClick = onClick,
    )
}

@Composable
private fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
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
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = AppTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && label != null) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                }
            }
        },
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun CompactInputSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.height(32.dp),
        shape = AppTheme.shapes.input,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(AppTheme.strokeWidths.thin, MaterialTheme.colorScheme.outline),
        content = content,
    )
}

private val SettingControlWidth: Dp = 140.dp

@Composable
private fun SettingControlBox(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier.widthIn(min = SettingControlWidth, max = SettingControlWidth).height(32.dp),
        contentAlignment = Alignment.CenterEnd,
        content = content,
    )
}
