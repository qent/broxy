package io.qent.bro.ui.viewmodels

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.qent.bro.core.models.McpServerConfig

/**
 * Global UI state for the application.
 * Presentation layer only; delegates logic to ViewModels/services.
 */
class AppState(
    initialScreen: Screen = Screen.Servers,
    initialTheme: ThemeSettings = ThemeSettings()
) {
    val currentScreen: MutableState<Screen> = mutableStateOf(initialScreen)

    // Servers management (configs live here; connections handled by ViewModel)
    val servers: SnapshotStateList<McpServerConfig> = mutableStateListOf()

    // Presets management
    val presets: SnapshotStateList<UiPreset> = mutableStateListOf()

    // Proxy status
    val proxyStatus: MutableState<ProxyStatus> = mutableStateOf(ProxyStatus.Stopped)

    // Theme settings
    val theme: MutableState<ThemeSettings> = mutableStateOf(initialTheme)

    // UI dialogs
    val showAddServerDialog: MutableState<Boolean> = mutableStateOf(false)
    val showAddPresetDialog: MutableState<Boolean> = mutableStateOf(false)
}

enum class Screen(val title: String) {
    Servers("Servers"),
    Presets("Presets"),
    Proxy("Proxy"),
    Settings("Settings")
}

// Minimal UI-layer models
data class UiPreset(
    val id: String,
    val name: String,
    val description: String? = null,
    val toolsCount: Int = 0
)

sealed class ProxyStatus {
    data object Running : ProxyStatus()
    data object Stopped : ProxyStatus()
    data class Error(val message: String) : ProxyStatus()
}

data class ThemeSettings(
    val darkTheme: Boolean = false,
    val dynamicColors: Boolean = false,
    val mediumCornerRadius: Int = 16,
    val largeCornerRadius: Int = 28,
    val motionEnabled: Boolean = true
)
