package io.qent.broxy.ui.viewmodels

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.qent.broxy.ui.theme.ThemeStyle

/**
 * UI-local state: navigation and appearance only.
 * All business data comes from ui-adapter as Flow<UIState>.
 */
class AppState(
    initialScreen: Screen = Screen.Servers,
    initialTheme: ThemeStyle = ThemeStyle.System
) {
    val currentScreen: MutableState<Screen> = mutableStateOf(initialScreen)
    val themeStyle: MutableState<ThemeStyle> = mutableStateOf(initialTheme)

    // Dialog flags
    val showAddServerDialog: MutableState<Boolean> = mutableStateOf(false)
    val showAddPresetDialog: MutableState<Boolean> = mutableStateOf(false)
}

enum class Screen(val title: String) {
    Servers("Servers"),
    Presets("Presets"),
    Logs("Logs"),
    Settings("Settings"),
}
