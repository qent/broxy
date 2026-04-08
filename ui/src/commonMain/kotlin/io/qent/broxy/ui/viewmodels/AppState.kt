package io.qent.broxy.ui.viewmodels

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.qent.broxy.ui.adapter.catalog.CatalogInstallSession
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.theme.ThemeStyle

/**
 * UI-local state: navigation and appearance only.
 * All business data comes from ui-adapter as Flow<UIState>.
 */
class AppState(
    initialScreen: Screen = Screen.Servers,
    initialTheme: ThemeStyle = ThemeStyle.Dark,
) {
    val currentScreen: MutableState<Screen> = mutableStateOf(initialScreen)
    val themeStyle: MutableState<ThemeStyle> = mutableStateOf(initialTheme)

    // Sub-navigation inside Screens.Servers (keeps the Servers menu item active).
    val serverEditor: MutableState<ServerEditorState?> = mutableStateOf(null)
    val serverDetailsId: MutableState<String?> = mutableStateOf(null)

    // Sub-navigation inside Screens.Presets (keeps the Presets menu item active).
    val presetEditor: MutableState<PresetEditorState?> = mutableStateOf(null)

    // Sub-navigation inside Screens.Catalog.
    val catalogInstall: MutableState<CatalogInstallSession?> = mutableStateOf(null)

    // Sub-navigation inside Screens.Agents (keeps the Agents menu item active).
    val agentEditor: MutableState<AgentEditorState?> = mutableStateOf(null)
    val agentLaunchId: MutableState<String?> = mutableStateOf(null)
    val agentDetailsId: MutableState<String?> = mutableStateOf(null)
    val agentGenerateMode: MutableState<Boolean> = mutableStateOf(false)

    // Sub-navigation inside Screens.Runs (keeps the Runs menu item active).
    val runDetailsId: MutableState<String?> = mutableStateOf(null)
}

enum class Screen {
    Servers,
    Catalog,
    Presets,
    Agents,
    Runs,
    Clients,
    AgentSettings,
    Settings,
}

sealed interface PresetEditorState {
    data object Create : PresetEditorState

    data class Edit(
        val presetId: String,
    ) : PresetEditorState
}

sealed interface ServerEditorState {
    data object Create : ServerEditorState

    data class CreateFromImport(
        val clientId: String,
        val sourceServerId: String,
        val initialDraft: UiServerDraft,
    ) : ServerEditorState

    data class Edit(
        val serverId: String,
    ) : ServerEditorState
}

sealed interface AgentEditorState {
    data object Create : AgentEditorState

    data class Edit(
        val agentId: String,
    ) : AgentEditorState
}
