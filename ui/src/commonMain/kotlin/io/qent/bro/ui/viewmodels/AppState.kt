package io.qent.bro.ui.viewmodels

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * UI-local state: navigation and appearance only.
 * All business data comes from ui-adapter as Flow<UIState>.
 */
class AppState(
    initialScreen: Screen = Screen.Servers
) {
    val currentScreen: MutableState<Screen> = mutableStateOf(initialScreen)

    // Dialog flags
    val showAddServerDialog: MutableState<Boolean> = mutableStateOf(false)
    val showAddPresetDialog: MutableState<Boolean> = mutableStateOf(false)
}

enum class Screen(val title: String) {
    Servers("Servers"),
    Presets("Presets"),
    Proxy("Proxy"),
    Logs("Logs"),
}


