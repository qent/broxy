package io.qent.broxy.ui.strings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

enum class AppLanguage(val tag: String) {
    English("en"),
    ;

    companion object {
        fun fromTag(tag: String): AppLanguage {
            val normalized = tag.lowercase()
            return values().firstOrNull { normalized.startsWith(it.tag) } ?: English
        }
    }
}

interface AppStrings {
    val appName: String

    val appGreeting: String

    val navMcp: String

    val navPresets: String

    val navSettings: String

    val loading: String

    val loadingInline: String

    val loadingPresets: String

    val loadingServerCapabilities: String

    val noConnectedServersAvailable: String

    val errorLabel: String

    fun errorMessage(message: String): String

    val unavailable: String

    val noPreset: String

    val openPresetMenu: String

    val presetCleared: String

    fun presetSelected(name: String): String

    val searchServers: String

    val searchPresets: String

    val serversEmptyTitle: String

    val serversEmptySubtitle: String

    val presetsEmptyTitle: String

    val presetsEmptySubtitle: String

    val deleteServerTitle: String

    val deletePresetTitle: String

    fun deleteServerPrompt(name: String): String

    fun deletePresetPrompt(name: String): String

    val deleteServerDescription: String

    val deletePresetDescription: String

    val editServer: String

    val addServer: String

    val editPreset: String

    val addPreset: String

    val createPreset: String

    val serverNotFound: String

    val presetNotFound: String

    val nameLabel: String

    val cancel: String

    val add: String

    val save: String

    val delete: String

    val edit: String

    val back: String

    val commandNotFound: String

    val serverFallbackName: String

    fun connecting(seconds: Long): String

    fun authorising(seconds: Long): String

    val separatorDot: String

    val noDescriptionProvided: String

    val mcpServersTitle: String

    val selectCapabilitiesHint: String

    val toolsLabel: String

    val promptsLabel: String

    val resourcesLabel: String

    val noToolsAvailable: String

    val noPromptsAvailable: String

    val noResourcesAvailable: String

    val showDetails: String

    val hideDetails: String

    val couldNotLoadCapabilities: String

    val noCapabilitiesExposed: String

    val argumentTypeUnspecified: String

    fun capabilityArgument(
        displayName: String,
        typeLabel: String,
    ): String

    val capabilitySeparator: String

    val remoteConnect: String

    val remoteDisconnect: String

    val remoteLogout: String

    val authorize: String

    val httpPortTitle: String

    val httpPortDescription: String

    val requestTimeoutTitle: String

    val requestTimeoutDescription: String

    val capabilitiesTimeoutTitle: String

    val capabilitiesTimeoutDescription: String

    val connectionRetryCountTitle: String

    val connectionRetryCountDescription: String

    val capabilitiesRefreshTitle: String

    val capabilitiesRefreshDescription: String

    val showTrayIconTitle: String

    val showTrayIconDescription: String

    val logsTitle: String

    val logsDescription: String

    val openFolder: String

    val themeTitle: String

    val themeDescription: String

    val themeDark: String

    val themeLight: String

    fun httpPortSaved(port: Int): String

    fun requestTimeoutSaved(seconds: Int): String

    fun capabilitiesTimeoutSaved(seconds: Int): String

    fun connectionRetryCountSaved(count: Int): String

    fun refreshIntervalSaved(seconds: Int): String

    fun trayIconToggle(enabled: Boolean): String

    val openingLogsFolder: String

    val statusRunning: String

    val statusStarting: String

    val statusStopping: String

    val statusStopped: String

    val portAlreadyInUse: String

    val addServerContentDescription: String

    val addPresetContentDescription: String

    val saveSettingsContentDescription: String

    val editPresetContentDescription: String

    val deletePresetContentDescription: String

    val editContentDescription: String

    val deleteContentDescription: String

    val trayFailedToLoadPresets: String

    val trayServerStatusUnknown: String

    val trayServerStatusUnavailable: String

    val trayNoPresetsAvailable: String

    fun trayServerStatus(statusText: String): String

    val trayShowApp: String

    val trayExit: String

    val trayStatusStarting: String

    val trayStatusOn: String

    val trayStatusStopping: String

    val trayStatusOff: String

    val trayStatusError: String

    val trayActivePresetMarker: String

    val commandLabel: String

    val argsLabel: String

    val envLabel: String

    val httpStreamableUrlLabel: String

    val httpSseUrlLabel: String

    val headersLabel: String

    val webSocketUrlLabel: String

    val transportStdioLabel: String

    val transportStreamableHttpLabel: String

    val transportHttpSseLabel: String

    val transportWebSocketLabel: String

    val connectionTimedOutSavedDisabled: String

    fun connectionFailedSavedDisabled(details: String?): String

    fun savedName(name: String): String
}

object EnglishStrings : AppStrings {
    override val appName = "Broxy"

    override val appGreeting = "Hello from UI"

    override val navMcp = "MCP"

    override val navPresets = "Presets"

    override val navSettings = "Settings"

    override val loading = "Loading..."

    override val loadingInline = "Loading…"

    override val loadingPresets = "Loading presets..."

    override val loadingServerCapabilities = "Loading server capabilities..."

    override val noConnectedServersAvailable = "No connected servers available"

    override val errorLabel = "Error"

    override fun errorMessage(message: String): String = "Error: $message"

    override val unavailable = "Unavailable"

    override val noPreset = "No preset"

    override val openPresetMenu = "Open preset menu"

    override val presetCleared = "Preset cleared"

    override fun presetSelected(name: String): String = "Preset selected: $name"

    override val searchServers = "Search servers"

    override val searchPresets = "Search presets"

    override val serversEmptyTitle = "No servers yet"

    override val serversEmptySubtitle = "Use the + button to add your first MCP server"

    override val presetsEmptyTitle = "No presets yet"

    override val presetsEmptySubtitle = "Use the + button to add your first preset"

    override val deleteServerTitle = "Delete server"

    override val deletePresetTitle = "Delete preset"

    override fun deleteServerPrompt(name: String): String = "Remove \"$name\"?"

    override fun deletePresetPrompt(name: String): String = "Remove \"$name\"?"

    override val deleteServerDescription =
        "This removes the server configuration and presets that referenced it will lose access to its capabilities. " +
            "This action cannot be undone."

    override val deletePresetDescription =
        "This preset will disappear from Broxy, including the CLI shortcuts that rely on it. This action cannot be undone."

    override val editServer = "Edit server"

    override val addServer = "Add server"

    override val editPreset = "Edit preset"

    override val addPreset = "Add preset"

    override val createPreset = "Create preset"

    override val serverNotFound = "Server not found."

    override val presetNotFound = "Preset not found."

    override val nameLabel = "Name"

    override val cancel = "Cancel"

    override val add = "Add"

    override val save = "Save"

    override val delete = "Delete"

    override val edit = "Edit"

    override val back = "Back"

    override val commandNotFound = "Command not found on PATH."

    override val serverFallbackName = "Server"

    override fun connecting(seconds: Long): String = "Connecting: $seconds s"

    override fun authorising(seconds: Long): String = "Authorising: $seconds s"

    override val separatorDot = " • "

    override val noDescriptionProvided = "No description provided"

    override val mcpServersTitle = "MCP servers"

    override val selectCapabilitiesHint = "Select tools/prompts/resources from connected servers"

    override val toolsLabel = "Tools"

    override val promptsLabel = "Prompts"

    override val resourcesLabel = "Resources"

    override val noToolsAvailable = "No tools available"

    override val noPromptsAvailable = "No prompts available"

    override val noResourcesAvailable = "No resources available"

    override val showDetails = "Show details"

    override val hideDetails = "Hide details"

    override val couldNotLoadCapabilities = "Could not load capabilities"

    override val noCapabilitiesExposed = "No capabilities exposed."

    override val argumentTypeUnspecified = "unspecified"

    override fun capabilityArgument(
        displayName: String,
        typeLabel: String,
    ): String = "• $displayName ($typeLabel)"

    override val capabilitySeparator = " · "

    override val remoteConnect = "Connect remote"

    override val remoteDisconnect = "Disconnect remote"

    override val remoteLogout = "Logout remote"

    override val authorize = "Authorize"

    override val httpPortTitle = "HTTP port"

    override val httpPortDescription = "Port for the local HTTP-streamable MCP endpoint."

    override val requestTimeoutTitle = "Request timeout"

    override val requestTimeoutDescription = "Max time to wait for downstream calls (seconds)."

    override val capabilitiesTimeoutTitle = "Capabilities timeout"

    override val capabilitiesTimeoutDescription = "Max time to wait for server listings (seconds)."

    override val connectionRetryCountTitle = "Connection retries"

    override val connectionRetryCountDescription = "Retry attempts when connecting to servers."

    override val capabilitiesRefreshTitle = "Capabilities refresh"

    override val capabilitiesRefreshDescription = "Background refresh interval (seconds)."

    override val showTrayIconTitle = "Show tray icon"

    override val showTrayIconDescription = "Display the Broxy icon in the system tray."

    override val logsTitle = "Logs"

    override val logsDescription = "Application logs are stored in the logs/ folder next to the configuration files."

    override val openFolder = "Open folder"

    override val themeTitle = "Theme"

    override val themeDescription = "Choose light or dark appearance."

    override val themeDark = "Dark"

    override val themeLight = "Light"

    override fun httpPortSaved(port: Int): String = "HTTP port saved: $port"

    override fun requestTimeoutSaved(seconds: Int): String = "Timeout saved: ${seconds}s"

    override fun capabilitiesTimeoutSaved(seconds: Int): String = "Capabilities timeout saved: ${seconds}s"

    override fun connectionRetryCountSaved(count: Int): String = "Connection retries saved: $count"

    override fun refreshIntervalSaved(seconds: Int): String = "Refresh interval saved: ${seconds}s"

    override fun trayIconToggle(enabled: Boolean): String = if (enabled) "Tray icon enabled" else "Tray icon disabled"

    override val openingLogsFolder = "Opening logs folder…"

    override val statusRunning = "Running"

    override val statusStarting = "Starting"

    override val statusStopping = "Stopping"

    override val statusStopped = "Stopped"

    override val portAlreadyInUse = "Port already in use"

    override val addServerContentDescription = "Add server"

    override val addPresetContentDescription = "Add preset"

    override val saveSettingsContentDescription = "Save settings"

    override val editPresetContentDescription = "Edit preset"

    override val deletePresetContentDescription = "Delete preset"

    override val editContentDescription = "Edit"

    override val deleteContentDescription = "Delete"

    override val trayFailedToLoadPresets = "Failed to load presets"

    override val trayServerStatusUnknown = "Server status: unknown"

    override val trayServerStatusUnavailable = "Server status: unavailable"

    override val trayNoPresetsAvailable = "No presets available"

    override fun trayServerStatus(statusText: String): String = "SSE server: $statusText"

    override val trayShowApp = "Show Broxy"

    override val trayExit = "Exit"

    override val trayStatusStarting = "starting"

    override val trayStatusOn = "on"

    override val trayStatusStopping = "stopping"

    override val trayStatusOff = "off"

    override val trayStatusError = "error"

    override val trayActivePresetMarker = " \u2713"

    override val commandLabel = "Command"

    override val argsLabel = "Args (comma-separated)"

    override val envLabel = "Env (key:value per line, values may use {ENV_VAR})"

    override val httpStreamableUrlLabel = "HTTP Streamable URL"

    override val httpSseUrlLabel = "HTTP SSE URL"

    override val headersLabel = "Headers (key:value per line)"

    override val webSocketUrlLabel = "WebSocket URL"

    override val transportStdioLabel = "STDIO"

    override val transportStreamableHttpLabel = "HTTP Streamable"

    override val transportHttpSseLabel = "HTTP SSE"

    override val transportWebSocketLabel = "WebSocket"

    override val connectionTimedOutSavedDisabled = "Connection timed out. Saved as disabled."

    override fun connectionFailedSavedDisabled(details: String?): String {
        val suffix = details?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
        return "Connection failed$suffix. Saved as disabled."
    }

    override fun savedName(name: String): String = "Saved $name"
}

object AppStringsProvider {
    fun forLanguage(language: AppLanguage): AppStrings =
        when (language) {
            AppLanguage.English -> EnglishStrings
        }
}

val LocalStrings = staticCompositionLocalOf<AppStrings> { EnglishStrings }

@Composable
fun ProvideAppStrings(
    language: AppLanguage,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalStrings provides AppStringsProvider.forLanguage(language), content = content)
}

object AppTextTokens {
    val portBusyNeedles = listOf("already in use", "Address already in use")
}
