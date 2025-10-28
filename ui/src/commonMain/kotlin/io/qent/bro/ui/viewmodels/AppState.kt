package io.qent.bro.ui.viewmodels

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * UI-local state: navigation and appearance only.
 * All business data comes from ui-adapter as Flow<UIState>.
 */
class AppState(
    initialScreen: Screen = Screen.Servers,
    initialTheme: ThemeSettings = ThemeSettings()
) {
    val currentScreen: MutableState<Screen> = mutableStateOf(initialScreen)
    val theme: MutableState<ThemeSettings> = mutableStateOf(initialTheme)

    // Dialog flags
    val showAddServerDialog: MutableState<Boolean> = mutableStateOf(false)
    val showAddPresetDialog: MutableState<Boolean> = mutableStateOf(false)
}

enum class Screen(val title: String) {
    Servers("Servers"),
    Presets("Presets"),
    Proxy("Proxy"),
    Logs("Logs"),
    Settings("Settings")
}

data class ThemeSettings(
    val darkTheme: Boolean = false,
    val dynamicColors: Boolean = false,
    val mediumCornerRadius: Int = 16,
    val largeCornerRadius: Int = 28,
    val motionEnabled: Boolean = true
)
