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
    initialTheme: ThemeStyle = ThemeStyle.Dark
) {
    val currentScreen: MutableState<Screen> = mutableStateOf(initialScreen)
    val themeStyle: MutableState<ThemeStyle> = mutableStateOf(initialTheme)

    // Dialog flags
    val showAddServerDialog: MutableState<Boolean> = mutableStateOf(false)

    // Sub-navigation inside Screens.Presets (keeps the Presets menu item active).
    val presetEditor: MutableState<PresetEditorState?> = mutableStateOf(null)
}

enum class Screen(val title: String) {
    Servers("Servers"),
    Presets("Presets"),
    Settings("Settings"),
}

sealed interface PresetEditorState {
    data object Create : PresetEditorState

    data class Edit(val presetId: String) : PresetEditorState
}
