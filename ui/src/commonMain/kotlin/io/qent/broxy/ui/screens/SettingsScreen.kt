@file:Suppress("FunctionNaming", "MatchingDeclarationName", "TooManyFunctions")
@file:OptIn(ExperimentalMaterial3Api::class)

package io.qent.broxy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppPrimaryButton
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.theme.ThemeStyle

private const val MIN_REFRESH_INTERVAL_SECONDS = 30
private const val MIN_PORT = 1
private const val MAX_PORT = 65535
private const val TOGGLE_SCALE = 0.7f

@Immutable
data class SettingsFabState(
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
@Suppress("LongMethod")
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
                    ignoreHttpsCertificateErrors = ui.ignoreHttpsCertificateErrors,
                    capabilitiesRefreshIntervalSeconds = ui.capabilitiesRefreshIntervalSeconds,
                    inboundHttpPort = ui.inboundHttpPort,
                    showTrayIcon = ui.showTrayIcon,
                    fallbackPromptsAndResourcesToTools = ui.fallbackPromptsAndResourcesToTools,
                    adapterMode = ui.adapterMode,
                    onInboundHttpPortSave = { port ->
                        ui.intents.updateInboundHttpPort(port)
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
                    onToggleIgnoreHttpsCertificateErrors = { enabled ->
                        ui.intents.updateIgnoreHttpsCertificateErrors(enabled)
                        notify(strings.ignoreHttpsCertificateErrorsToggle(enabled))
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
                    onToggleAdapterMode = { enabled ->
                        ui.intents.updateAdapterMode(enabled)
                        notify(strings.adapterModeToggle(enabled))
                    },
                    onOpenLogsFolder = {
                        ui.intents.openLogsFolder()
                        notify(strings.openingLogsFolder)
                    },
                    onResetHiddenImportedServers = {
                        ui.intents.resetHiddenImportedServers()
                        notify(strings.resetHiddenImportedServersDone)
                    },
                )
        }
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
private fun SettingsContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    themeStyle: ThemeStyle,
    onThemeStyleChange: (ThemeStyle) -> Unit,
    onFabStateChange: (SettingsFabState) -> Unit,
    requestTimeoutSeconds: Int,
    capabilitiesTimeoutSeconds: Int,
    connectionRetryCount: Int,
    ignoreHttpsCertificateErrors: Boolean,
    capabilitiesRefreshIntervalSeconds: Int,
    inboundHttpPort: Int,
    showTrayIcon: Boolean,
    fallbackPromptsAndResourcesToTools: Boolean,
    adapterMode: Boolean,
    onInboundHttpPortSave: (Int) -> Unit,
    onRequestTimeoutSave: (Int) -> Unit,
    onCapabilitiesTimeoutSave: (Int) -> Unit,
    onConnectionRetryCountSave: (Int) -> Unit,
    onToggleIgnoreHttpsCertificateErrors: (Boolean) -> Unit,
    onCapabilitiesRefreshIntervalSave: (Int) -> Unit,
    onToggleTrayIcon: (Boolean) -> Unit,
    onToggleFallbackPromptsAndResourcesToTools: (Boolean) -> Unit,
    onToggleAdapterMode: (Boolean) -> Unit,
    onOpenLogsFolder: () -> Unit,
    onResetHiddenImportedServers: () -> Unit,
) {
    var requestTimeoutInput by rememberSaveable(requestTimeoutSeconds) {
        mutableStateOf(requestTimeoutSeconds.toString())
    }
    var capabilitiesTimeoutInput by rememberSaveable(capabilitiesTimeoutSeconds) {
        mutableStateOf(capabilitiesTimeoutSeconds.toString())
    }
    var capabilitiesRefreshInput by rememberSaveable(capabilitiesRefreshIntervalSeconds) {
        mutableStateOf(capabilitiesRefreshIntervalSeconds.toString())
    }
    var connectionRetryInput by rememberSaveable(connectionRetryCount) {
        mutableStateOf(connectionRetryCount.toString())
    }
    var inboundHttpPortInput by rememberSaveable(inboundHttpPort) {
        mutableStateOf(inboundHttpPort.toString())
    }

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
    LaunchedEffect(inboundHttpPort) {
        inboundHttpPortInput = inboundHttpPort.toString()
    }

    val parsedRequest = requestTimeoutInput.toLongOrNull()
    val resolvedRequest = parsedRequest?.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()
    val canSaveRequest = resolvedRequest != null && resolvedRequest != requestTimeoutSeconds

    val parsedCapabilities = capabilitiesTimeoutInput.toLongOrNull()
    val resolvedCapabilities = parsedCapabilities?.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()
    val canSaveCapabilities = resolvedCapabilities != null && resolvedCapabilities != capabilitiesTimeoutSeconds

    val parsedRefresh = capabilitiesRefreshInput.toLongOrNull()
    val resolvedRefresh = parsedRefresh?.takeIf { it >= MIN_REFRESH_INTERVAL_SECONDS && it <= Int.MAX_VALUE }?.toInt()
    val canSaveRefresh = resolvedRefresh != null && resolvedRefresh != capabilitiesRefreshIntervalSeconds

    val parsedRetries = connectionRetryInput.toLongOrNull()
    val resolvedRetries = parsedRetries?.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()
    val canSaveRetries = resolvedRetries != null && resolvedRetries != connectionRetryCount

    val parsedPort = inboundHttpPortInput.toLongOrNull()
    val resolvedPort = parsedPort?.takeIf { it in MIN_PORT..MAX_PORT }?.toInt()
    val canSavePort = resolvedPort != null && resolvedPort != inboundHttpPort

    val canSaveAny =
        canSaveRequest ||
            canSaveCapabilities ||
            canSaveRefresh ||
            canSaveRetries ||
            canSavePort

    val scrollState = rememberScrollState()
    val onSave: () -> Unit = onSave@{
        if (!canSaveAny) return@onSave
        if (canSaveRequest) {
            onRequestTimeoutSave(requireNotNull(resolvedRequest))
        }
        if (canSaveCapabilities) {
            onCapabilitiesTimeoutSave(requireNotNull(resolvedCapabilities))
        }
        if (canSaveRefresh) {
            onCapabilitiesRefreshIntervalSave(requireNotNull(resolvedRefresh))
        }
        if (canSaveRetries) {
            onConnectionRetryCountSave(requireNotNull(resolvedRetries))
        }
        if (canSavePort) {
            onInboundHttpPortSave(requireNotNull(resolvedPort))
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
            Spacer(modifier = Modifier.height(AppTheme.spacing.xs))
            TimeoutSetting(
                title = LocalStrings.current.httpPortTitle,
                description = LocalStrings.current.httpPortDescription,
                value = inboundHttpPortInput,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        inboundHttpPortInput = value
                    }
                },
            )
            TimeoutSetting(
                title = LocalStrings.current.requestTimeoutTitle,
                description = LocalStrings.current.requestTimeoutDescription,
                value = requestTimeoutInput,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        requestTimeoutInput = value
                    }
                },
            )
            TimeoutSetting(
                title = LocalStrings.current.capabilitiesTimeoutTitle,
                description = LocalStrings.current.capabilitiesTimeoutDescription,
                value = capabilitiesTimeoutInput,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        capabilitiesTimeoutInput = value
                    }
                },
            )
            TimeoutSetting(
                title = LocalStrings.current.connectionRetryCountTitle,
                description = LocalStrings.current.connectionRetryCountDescription,
                value = connectionRetryInput,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        connectionRetryInput = value
                    }
                },
            )
            IgnoreHttpsCertificateErrorsSetting(
                checked = ignoreHttpsCertificateErrors,
                onToggle = onToggleIgnoreHttpsCertificateErrors,
            )
            TimeoutSetting(
                title = LocalStrings.current.capabilitiesRefreshTitle,
                description = LocalStrings.current.capabilitiesRefreshDescription,
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
            AdapterModeSetting(
                checked = adapterMode,
                onToggle = onToggleAdapterMode,
            )
            ResetHiddenImportedServersSetting(onReset = onResetHiddenImportedServers)
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
private fun IgnoreHttpsCertificateErrorsSetting(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    SettingItem(
        title = strings.ignoreHttpsCertificateErrorsTitle,
        description = strings.ignoreHttpsCertificateErrorsDescription,
    ) {
        SettingControlBox {
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                modifier = Modifier.scale(TOGGLE_SCALE),
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
                modifier = Modifier.scale(TOGGLE_SCALE),
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
            modifier = Modifier.width(SettingControlWidth).height(SETTING_CONTROL_HEIGHT_DP.dp),
        ) {
            Text(strings.openFolder, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ResetHiddenImportedServersSetting(onReset: () -> Unit) {
    val strings = LocalStrings.current
    SettingItem(
        title = strings.resetHiddenImportedServersTitle,
        description = strings.resetHiddenImportedServersDescription,
    ) {
        AppPrimaryButton(
            onClick = onReset,
            modifier = Modifier.width(SettingControlWidth).height(SETTING_CONTROL_HEIGHT_DP.dp),
        ) {
            Text(strings.resetHiddenImportedServersAction, style = MaterialTheme.typography.labelSmall)
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
                modifier = Modifier.scale(TOGGLE_SCALE),
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
private fun AdapterModeSetting(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    SettingItem(
        title = strings.adapterModeTitle,
        description = strings.adapterModeDescription,
    ) {
        SettingControlBox {
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                modifier = Modifier.scale(TOGGLE_SCALE),
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
@Suppress("LongMethod")
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
                        .menuAnchor(
                            ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                            enabled = true,
                        ),
                expanded = expanded,
                shape = fieldShape,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier =
                    Modifier
                        .background(color = MaterialTheme.colorScheme.surface, shape = dropdownShape)
                        .border(
                            BorderStroke(AppTheme.strokeWidths.thin, MaterialTheme.colorScheme.outline),
                            dropdownShape,
                        ),
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
        modifier = modifier.height(SETTING_CONTROL_HEIGHT_DP.dp),
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
