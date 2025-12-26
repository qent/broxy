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
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.theme.ThemeStyle

@Immutable
data class SettingsFabState(
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
fun SettingsScreen(
    ui: UIState,
    themeStyle: ThemeStyle,
    onThemeStyleChange: (ThemeStyle) -> Unit,
    onFabStateChange: (SettingsFabState) -> Unit,
    notify: (String) -> Unit = {},
) {
    val strings = LocalStrings.current
    Box(modifier = Modifier.fillMaxSize()) {
        when (ui) {
            UIState.Loading ->
                Text(
                    strings.loading,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = AppTheme.spacing.md),
                )
            is UIState.Error ->
                Text(
                    strings.errorMessage(ui.message),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = AppTheme.spacing.md),
                )
            is UIState.Ready ->
                SettingsContent(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = AppTheme.spacing.md),
                    themeStyle = themeStyle,
                    onThemeStyleChange = onThemeStyleChange,
                    onFabStateChange = onFabStateChange,
                    requestTimeoutSeconds = ui.requestTimeoutSeconds,
                    capabilitiesTimeoutSeconds = ui.capabilitiesTimeoutSeconds,
                    connectionRetryCount = ui.connectionRetryCount,
                    capabilitiesRefreshIntervalSeconds = ui.capabilitiesRefreshIntervalSeconds,
                    inboundSsePort = ui.inboundSsePort,
                    showTrayIcon = ui.showTrayIcon,
                    fallbackPromptsAndResourcesToTools = ui.fallbackPromptsAndResourcesToTools,
                    onInboundSsePortSave = { port ->
                        ui.intents.updateInboundSsePort(port)
                        notify(strings.httpPortSaved(port))
                    },
                    onRequestTimeoutSave = { seconds ->
                        ui.intents.updateRequestTimeout(seconds)
                        notify(strings.requestTimeoutSaved(seconds))
                    },
                    onCapabilitiesTimeoutSave = { seconds ->
                        ui.intents.updateCapabilitiesTimeout(seconds)
                        notify(strings.capabilitiesTimeoutSaved(seconds))
                    },
                    onConnectionRetryCountSave = { count ->
                        ui.intents.updateConnectionRetryCount(count)
                        notify(strings.connectionRetryCountSaved(count))
                    },
                    onCapabilitiesRefreshIntervalSave = { seconds ->
                        ui.intents.updateCapabilitiesRefreshInterval(seconds)
                        notify(strings.refreshIntervalSaved(seconds))
                    },
                    onToggleTrayIcon = { enabled ->
                        ui.intents.updateTrayIconVisibility(enabled)
                        notify(strings.trayIconToggle(enabled))
                    },
                    onToggleFallbackPromptsAndResourcesToTools = { enabled ->
                        ui.intents.updateFallbackPromptsAndResourcesToTools(enabled)
                        notify(strings.fallbackPromptsAndResourcesToToolsToggle(enabled))
                    },
                    onOpenLogsFolder = {
                        ui.intents.openLogsFolder()
                        notify(strings.openingLogsFolder)
                    },
                )
        }
    }
}

@Composable
private fun SettingsContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    themeStyle: ThemeStyle,
    onThemeStyleChange: (ThemeStyle) -> Unit,
    onFabStateChange: (SettingsFabState) -> Unit,
    requestTimeoutSeconds: Int,
    capabilitiesTimeoutSeconds: Int,
    connectionRetryCount: Int,
    capabilitiesRefreshIntervalSeconds: Int,
    inboundSsePort: Int,
    showTrayIcon: Boolean,
    fallbackPromptsAndResourcesToTools: Boolean,
    onInboundSsePortSave: (Int) -> Unit,
    onRequestTimeoutSave: (Int) -> Unit,
    onCapabilitiesTimeoutSave: (Int) -> Unit,
    onConnectionRetryCountSave: (Int) -> Unit,
    onCapabilitiesRefreshIntervalSave: (Int) -> Unit,
    onToggleTrayIcon: (Boolean) -> Unit,
    onToggleFallbackPromptsAndResourcesToTools: (Boolean) -> Unit,
    onOpenLogsFolder: () -> Unit,
) {
    val strings = LocalStrings.current
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
    var connectionRetryInput by rememberSaveable(connectionRetryCount) { mutableStateOf(connectionRetryCount.toString()) }
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

    LaunchedEffect(connectionRetryCount) {
        connectionRetryInput = connectionRetryCount.toString()
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

    val parsedRetries = connectionRetryInput.toLongOrNull()
    val resolvedRetries = parsedRetries?.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()
    val canSaveRetries = resolvedRetries != null && resolvedRetries != connectionRetryCount

    val parsedPort = inboundSsePortInput.toLongOrNull()
    val resolvedPort = parsedPort?.takeIf { it in 1..65535 }?.toInt()
    val canSavePort = resolvedPort != null && resolvedPort != inboundSsePort

    val canSaveAny = canSaveRequest || canSaveCapabilities || canSaveRefresh || canSaveRetries || canSavePort

    val scrollState = rememberScrollState()
    val onSave: () -> Unit = onSave@{
        if (!canSaveAny) return@onSave
        if (canSaveRequest) {
            resolvedRequest?.let { onRequestTimeoutSave(it) }
        }
        if (canSaveCapabilities) {
            resolvedCapabilities?.let { onCapabilitiesTimeoutSave(it) }
        }
        if (canSaveRefresh) {
            resolvedRefresh?.let { onCapabilitiesRefreshIntervalSave(it) }
        }
        if (canSaveRetries) {
            resolvedRetries?.let { onConnectionRetryCountSave(it) }
        }
        if (canSavePort) {
            resolvedPort?.let { onInboundSsePortSave(it) }
        }
    }

    LaunchedEffect(
        canSaveAny,
        resolvedRequest,
        resolvedCapabilities,
        resolvedRefresh,
        resolvedRetries,
        resolvedPort,
    ) {
        onFabStateChange(SettingsFabState(enabled = canSaveAny, onClick = onSave))
    }

    Box(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(contentPadding)
                    .padding(bottom = AppTheme.spacing.fab),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AppTheme.spacing.xs))
            TimeoutSetting(
                title = strings.httpPortTitle,
                description = strings.httpPortDescription,
                value = inboundSsePortInput,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        inboundSsePortInput = value
                    }
                },
            )
            TimeoutSetting(
                title = strings.requestTimeoutTitle,
                description = strings.requestTimeoutDescription,
                value = requestTimeoutInput,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        requestTimeoutInput = value
                    }
                },
            )
            TimeoutSetting(
                title = strings.capabilitiesTimeoutTitle,
                description = strings.capabilitiesTimeoutDescription,
                value = capabilitiesTimeoutInput,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        capabilitiesTimeoutInput = value
                    }
                },
            )
            TimeoutSetting(
                title = strings.connectionRetryCountTitle,
                description = strings.connectionRetryCountDescription,
                value = connectionRetryInput,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        connectionRetryInput = value
                    }
                },
            )
            TimeoutSetting(
                title = strings.capabilitiesRefreshTitle,
                description = strings.capabilitiesRefreshDescription,
                value = capabilitiesRefreshInput,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        capabilitiesRefreshInput = value
                    }
                },
            )
            FallbackPromptsResourcesSetting(
                checked = fallbackPromptsAndResourcesToTools,
                onToggle = onToggleFallbackPromptsAndResourcesToTools,
            )
            LogsSetting(onOpenFolder = onOpenLogsFolder)
            ThemeSetting(
                themeStyle = themeStyle,
                onThemeStyleChange = onThemeStyleChange,
            )
            TrayIconSetting(checked = showTrayIcon, onToggle = onToggleTrayIcon)
        }
        AppVerticalScrollbar(
            scrollState = scrollState,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .offset(x = -AppTheme.strokeWidths.hairline),
        )
    }
}

@Composable
private fun TrayIconSetting(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    SettingItem(
        title = strings.showTrayIconTitle,
        description = strings.showTrayIconDescription,
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
    val strings = LocalStrings.current
    SettingItem(
        title = strings.logsTitle,
        description = strings.logsDescription,
    ) {
        AppPrimaryButton(
            onClick = onOpenFolder,
            modifier = Modifier.width(SettingControlWidth).height(32.dp),
        ) {
            Text(strings.openFolder, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FallbackPromptsResourcesSetting(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    SettingItem(
        title = strings.fallbackPromptsAndResourcesToToolsTitle,
        description = strings.fallbackPromptsAndResourcesToToolsDescription,
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
    SettingsLikeItem(
        title = title,
        description = description,
        contentPadding =
            PaddingValues(
                start = AppTheme.spacing.md + AppTheme.spacing.sm,
                end = AppTheme.spacing.md,
                top = AppTheme.spacing.md,
                bottom = AppTheme.spacing.md,
            ),
        supportingContent = supportingContent,
        control = control,
    )
}

@Composable
private fun ThemeSetting(
    themeStyle: ThemeStyle,
    onThemeStyleChange: (ThemeStyle) -> Unit,
) {
    val strings = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    val label =
        when (themeStyle) {
            ThemeStyle.Dark -> strings.themeDark
            ThemeStyle.Light -> strings.themeLight
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
        title = strings.themeTitle,
        description = strings.themeDescription,
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.widthIn(min = SettingControlWidth, max = SettingControlWidth),
        ) {
            ThemeDropdownField(
                text = label,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
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
                    text = strings.themeDark,
                    onClick = {
                        expanded = false
                        if (themeStyle != ThemeStyle.Dark) onThemeStyleChange(ThemeStyle.Dark)
                    },
                )
                ThemeDropdownItem(
                    text = strings.themeLight,
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
