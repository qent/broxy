package io.qent.broxy.ui.screens

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
import androidx.compose.material3.Button
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
                timeoutSeconds = ui.requestTimeoutSeconds,
                showTrayIcon = ui.showTrayIcon,
                themeStyle = themeStyle,
                onTimeoutSave = { seconds ->
                    ui.intents.updateRequestTimeout(seconds)
                    notify("Timeout saved: ${seconds}s")
                },
                onToggleTrayIcon = { enabled ->
                    ui.intents.updateTrayIconVisibility(enabled)
                    notify(if (enabled) "Tray icon enabled" else "Tray icon disabled")
                },
                onThemeStyleChange = onThemeStyleChange
            )
        }
    }
}

@Composable
private fun SettingsContent(
    timeoutSeconds: Int,
    showTrayIcon: Boolean,
    themeStyle: ThemeStyle,
    onTimeoutSave: (Int) -> Unit,
    onToggleTrayIcon: (Boolean) -> Unit,
    onThemeStyleChange: (ThemeStyle) -> Unit
) {
    var timeoutInput by rememberSaveable(timeoutSeconds) { mutableStateOf(timeoutSeconds.toString()) }

    LaunchedEffect(timeoutSeconds) {
        timeoutInput = timeoutSeconds.toString()
    }

    val parsed = timeoutInput.toLongOrNull()
    val resolved = parsed?.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()
    val canSave = resolved != null && resolved != timeoutSeconds

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.lg)
    ) {
        ThemeStyleSetting(
            themeStyle = themeStyle,
            onThemeStyleChange = onThemeStyleChange
        )
        TrayIconSetting(checked = showTrayIcon, onToggle = onToggleTrayIcon)
        TimeoutSetting(
            value = timeoutInput,
            canSave = canSave,
            onValueChange = { value ->
                if (value.isEmpty() || value.all { it.isDigit() }) {
                    timeoutInput = value
                }
            },
            onSave = {
                resolved?.let { onTimeoutSave(it) }
            }
        )
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
    value: String,
    canSave: Boolean,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit
) {
    SettingCard(
        title = "Request timeout",
        description = "Set how long broxy waits for downstream MCP calls before timing out.",
        supportingContent = {
            Button(
                onClick = onSave,
                enabled = canSave
            ) {
                Text("Save")
            }
        }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.widthIn(min = SettingControlWidth, max = SettingControlWidth),
            singleLine = true,
            label = { Text("Seconds") }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
