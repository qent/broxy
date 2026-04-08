@file:Suppress("FunctionNaming", "TooManyFunctions")

package io.qent.broxy.ui.strings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

enum class AppLanguage(
    val tag: String,
) {
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

    val navRegistry: String

    val navPresets: String

    val navAgents: String

    val navRuns: String

    val navConnection: String

    val navAgentSettings: String

    val navSettings: String

    val loading: String

    val loadingInline: String

    val loadingPresets: String

    val loadingServerCapabilities: String

    val noConnectedServersAvailable: String

    val noAgentsAvailableForTools: String

    val errorLabel: String

    fun errorMessage(message: String): String

    val unavailable: String

    val noPreset: String

    val allEnabledServers: String

    val presetManagement: String

    val openPresetMenu: String

    val openClientInfo: String

    val connectionInfoStdioTitle: String

    val connectionInfoHttpTitle: String

    val connectionInfoSseTitle: String

    fun aiClientConfigNotFound(name: String): String

    fun aiClientOtherBroxyConfig(): String

    fun aiClientOtherBroxyConfigAt(url: String): String

    fun aiClientStatusLoadFailed(details: String?): String

    val presetCleared: String

    fun presetSelected(name: String): String

    val searchServers: String

    val searchPresets: String

    val searchAgents: String

    val searchCapabilities: String

    val clearSearch: String

    val serversEmptyTitle: String

    val serversEmptySubtitle: String

    val presetsEmptyTitle: String

    val presetsEmptySubtitle: String

    fun presetsEmptySubtitleForActiveMode(modeName: String): String

    val agentsEmptyTitle: String

    val agentsEmptySubtitle: String

    val clientsEmptyTitle: String

    val clientsEmptySubtitle: String

    val deleteServerTitle: String

    val deletePresetTitle: String

    val deleteAgentTitle: String

    fun deleteServerPrompt(name: String): String

    fun deletePresetPrompt(name: String): String

    fun deleteAgentPrompt(name: String): String

    val deleteServerDescription: String

    val deletePresetDescription: String

    val deleteAgentDescription: String

    val editServer: String

    val addServer: String

    val editPreset: String

    val addPreset: String

    val editAgent: String

    val addAgent: String

    val createAgent: String

    val createPreset: String

    val customAgentPreset: String

    val serverNotFound: String

    val presetNotFound: String

    val agentNotFound: String

    val nameLabel: String

    val cancel: String

    val continueInBrowser: String

    val skip: String

    val close: String

    val add: String

    val save: String

    val connect: String

    val disconnect: String

    val delete: String

    val edit: String

    val back: String

    val commandNotFound: String

    val serverFallbackName: String

    fun connecting(seconds: Long): String

    fun authorization(seconds: Long): String

    fun authorizationPopupTitle(name: String): String

    val authorizationPopupPermissionSubtitle: String

    val authorizationPopupSubtitle: String

    val authorizationDialogTitle: String

    val separatorDot: String

    val noDescriptionProvided: String

    val mcpServersTitle: String

    val importSectionTitle: String

    val importButtonLabel: String

    val hideButtonLabel: String

    val agentIdentitySection: String

    val agentLlmSection: String

    val agentPresetSourceSection: String

    val agentDescriptionLabel: String

    val agentDescriptionGenerateTitle: String

    val agentDescriptionGenerateSubtitle: String

    val agentGenerateScreenTitle: String

    val agentGenerateRequestLabel: String

    val agentGenerateRequestPlaceholder: String

    val generateAction: String

    val generatingAction: String

    val agentGenerateStageSelectingServers: String

    val agentGenerateStageSelectingCapabilities: String

    val agentGenerateStageFinalizingAgent: String

    val agentDescriptionEnableHint: String

    fun agentDescriptionGenerationFailed(details: String?): String

    val agentGenerateErrorBlankRequest: String

    val agentGenerateErrorAlreadyRunning: String

    val agentGenerateErrorSaveFailed: String

    val systemPromptLabel: String

    val aiFeaturesCardTitle: String

    val aiFeaturesCardDescription: String

    val agentGenerateAiCardTitle: String

    val agentGenerateAiCardSubtitle: String

    val aiFeaturesToggleTitle: String

    val aiFeaturesToggleDescription: String

    val aiFeaturesRuntimeLabel: String

    val aiFeaturesModelRequired: String

    val aiFeaturesTemperatureInvalid: String

    val aiFeaturesCodexCommandRequired: String

    val providerLabel: String

    val aiProviderHint: String

    val runtimeLabel: String

    val runtimeHint: String

    val runtimeLangChain: String

    val runtimeCodex: String

    val runtimeDisabled: String

    val aiFeaturesRuntimeHint: String

    val codexProviderDisabledHint: String

    val codexWebSearchLabel: String

    val codexReasoningLabel: String

    val codexReasoningLow: String

    val codexReasoningMedium: String

    val codexReasoningHigh: String

    val codexProviderToggleTitle: String

    val codexProviderToggleDescription: String

    val providerOpenAi: String

    val providerAnthropic: String

    val providerLmStudio: String

    val modelLabel: String

    val temperatureLabel: String

    val presetSourceLabel: String

    val selectCapabilitiesHint: String

    val serverDisabledBadge: String

    val toolsLabel: String

    val promptsLabel: String

    val resourcesLabel: String

    val presetContainsDisabledCapabilities: String

    val presetAllCapabilitiesDisabled: String

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

    val mcpFilePathTitle: String

    val mcpFilePathDescription: String

    val connectionRetryCountTitle: String

    val connectionRetryCountDescription: String

    val ignoreHttpsCertificateErrorsTitle: String

    val ignoreHttpsCertificateErrorsDescription: String

    val capabilitiesRefreshTitle: String

    val capabilitiesRefreshDescription: String

    val showTrayIconTitle: String

    val showTrayIconDescription: String

    val agentRunNotificationsTitle: String

    val agentRunNotificationsDescription: String

    val fallbackPromptsAndResourcesToToolsTitle: String

    val fallbackPromptsAndResourcesToToolsDescription: String

    val adapterModeTitle: String

    val adapterModeDescription: String

    val providersSectionTitle: String

    val openAiProviderTitle: String

    val openAiProviderSubtitle: String

    val anthropicProviderTitle: String

    val anthropicProviderSubtitle: String

    val lmStudioProviderTitle: String

    val lmStudioProviderSubtitle: String

    val providerEndpointLabel: String

    val agentsDirectoryLabel: String

    val agentsDirectoryDescription: String

    val providerApiKeyLabel: String

    val saveEndpoint: String

    val providerEndpointInvalid: String

    val agentsDirectoryPickFailed: String

    val saveApiKeyAction: String

    val clearApiKeyAction: String

    val logsTitle: String

    val logsDescription: String

    val resetHiddenImportedServersTitle: String

    val resetHiddenImportedServersDescription: String

    val resetHiddenImportedServersAction: String

    val openFolder: String

    val themeTitle: String

    val themeDescription: String

    val themeDark: String

    val themeLight: String

    fun httpPortSaved(port: Int): String

    fun requestTimeoutSaved(seconds: Int): String

    fun capabilitiesTimeoutSaved(seconds: Int): String

    fun mcpFilePathSaved(path: String): String

    fun connectionRetryCountSaved(count: Int): String

    fun ignoreHttpsCertificateErrorsToggle(enabled: Boolean): String

    fun refreshIntervalSaved(seconds: Int): String

    fun trayIconToggle(enabled: Boolean): String

    fun agentRunNotificationsToggle(enabled: Boolean): String

    fun fallbackPromptsAndResourcesToToolsToggle(enabled: Boolean): String

    fun adapterModeToggle(enabled: Boolean): String

    val providerSettingsSaved: String

    fun providerApiKeySaved(providerName: String): String

    fun providerApiKeyCleared(providerName: String): String

    val openingLogsFolder: String

    val resetHiddenImportedServersDone: String

    val statusRunning: String

    val statusStarting: String

    val statusStopping: String

    val statusStopped: String

    val portAlreadyInUse: String

    val addServerContentDescription: String

    val addPresetContentDescription: String

    val addAgentContentDescription: String

    val agentGenerateFabContentDescription: String

    val agentGenerateMagicEmoji: String

    val saveSettingsContentDescription: String

    val editPresetContentDescription: String

    val deletePresetContentDescription: String

    val copyPresetConnectionUrlContentDescription: String

    val copyConnectionSnippetContentDescription: String

    val editContentDescription: String

    val deleteContentDescription: String

    val runAgentContentDescription: String

    val editScheduleContentDescription: String

    val stopAgentContentDescription: String

    val refreshContentDescription: String

    val reorderContentDescription: String

    val openServerPageContentDescription: String

    val agentRunningNow: String

    val agentStatusPreparingRun: String

    val agentStatusLoadingCapabilities: String

    val agentStatusLlmRequestVariants: List<String>

    val agentStatusLlmThinkingVariants: List<String>

    val agentStatusLlmResponseGenerationVariants: List<String>

    fun agentStatusToolExecution(
        toolName: String,
        serverId: String,
    ): String

    fun runAgentTitle(agentName: String): String

    fun stopAgentTitle(agentName: String): String

    val stopAgentWarning: String

    val stopAgentConfirm: String

    val runAgentPromptLabel: String

    val promptLabel: String

    val workspaceLabel: String

    val filesystemAccessLabel: String

    val filesystemNoAccess: String

    val filesystemReadOnly: String

    val filesystemReadWrite: String

    val workspacePickFailed: String

    fun workspaceDirectoryMissing(path: String): String

    val cronLabel: String

    val cronOptionalHint: String

    val runOnScheduleRemovalHint: String

    val scheduleCardHint: String

    val schedulePatternLabel: String

    val schedulePatternDisabled: String

    val schedulePatternEveryMinutes: String

    val schedulePatternEveryHours: String

    val schedulePatternDaily: String

    val schedulePatternWeekdays: String

    val schedulePatternWeekly: String

    val schedulePatternMonthly: String

    val scheduleEveryMinutesLabel: String

    val scheduleEveryHoursLabel: String

    val scheduleHourLabel: String

    val scheduleMinuteLabel: String

    val scheduleWeekdaysLabel: String

    val scheduleDayOfMonthLabel: String

    val advancedCronToggle: String

    val advancedCronLabel: String

    val customScheduleHint: String

    val customSchedule: String

    val scheduleNextRunsLabel: String

    val scheduleInvalidConfiguration: String

    val scheduleInvalidCron: String

    fun scheduleSummaryEveryMinutes(minutes: Int): String

    fun scheduleSummaryEveryHours(
        hours: Int,
        minute: String,
    ): String

    fun scheduleSummaryDaily(time: String): String

    fun scheduleSummaryWeekdays(time: String): String

    fun scheduleSummaryWeekly(
        days: String,
        time: String,
    ): String

    fun scheduleSummaryMonthly(
        day: Int,
        time: String,
    ): String

    val runAgentNow: String

    val saveSchedule: String

    val launchAction: String

    val scheduleAction: String

    val removeSchedule: String

    val runHistoryTitle: String

    val runHistoryEmpty: String

    val runHistoryPromptPrefix: String

    val runHistoryResponsePrefix: String

    val runHistoryErrorPrefix: String

    val runHistoryStatusSuccess: String

    val runHistoryStatusFailed: String

    val runHistoryStatusSkipped: String

    val runHistoryTriggerManual: String

    val runHistoryTriggerScheduled: String

    val runsTitle: String

    val runsEmptyTitle: String

    val runsEmptySubtitle: String

    fun runsDetailsTitle(agentName: String): String

    val runsDialogueSectionTitle: String

    val runsDialogueEmpty: String

    val runsActionsSectionTitle: String

    val runsActionsEmpty: String

    val runNotFound: String

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

    fun agentRunNotificationSuccessTitle(agentName: String): String

    fun agentRunNotificationFailureTitle(agentName: String): String

    val agentRunNotificationPermissionTitle: String

    val agentRunNotificationPermissionDescription: String

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

    val connectionUrlCopied: String

    val connectionSnippetCopied: String
}

object EnglishStrings : AppStrings {
    private const val ADAPTER_ON = "Adapter mode enabled"
    private const val ADAPTER_OFF = "Adapter mode disabled"
    override val appName = "Broxy"

    override val appGreeting = "Hello from UI"

    override val navMcp = "MCP"

    override val navRegistry = "Registry"

    override val navPresets = "Presets"

    override val navAgents = "Agents"

    override val navRuns = "Runs"

    override val navConnection = "Connection"

    override val navAgentSettings = "Agent settings"

    override val navSettings = "Settings"

    override val loading = "Loading..."

    override val loadingInline = "Loading…"

    override val loadingPresets = "Loading presets..."

    override val loadingServerCapabilities = "Loading server capabilities..."

    override val noConnectedServersAvailable = "No server capabilities available"

    override val noAgentsAvailableForTools = "No agents available"

    override val errorLabel = "Error"

    override fun errorMessage(message: String): String = "Error: $message"

    override val unavailable = "Unavailable"

    override val noPreset = "No preset"

    override val allEnabledServers = "All enabled servers"

    override val presetManagement = "AI Preset management"

    override val openPresetMenu = "Open preset menu"

    override val openClientInfo = "Open client info"

    override val connectionInfoStdioTitle = "STDIO"

    override val connectionInfoHttpTitle = "HTTP"

    override val connectionInfoSseTitle = "SSE"

    override fun aiClientConfigNotFound(name: String): String = "Configuration for $name was not found."

    override fun aiClientOtherBroxyConfig(): String = "Found another Broxy configuration."

    override fun aiClientOtherBroxyConfigAt(url: String): String = "Found another Broxy configuration at $url."

    override fun aiClientStatusLoadFailed(details: String?): String =
        if (details.isNullOrBlank()) {
            "Failed to load client status."
        } else {
            "Failed to load client status: $details"
        }

    override val presetCleared = "Preset cleared"

    override fun presetSelected(name: String): String = "Preset selected: $name"

    override val searchServers = "Search servers"

    override val searchPresets = "Search presets"

    override val searchAgents = "Search agents"

    override val searchCapabilities = "Search capabilities"

    override val clearSearch = "Clear search"

    override val serversEmptyTitle = "No servers yet"

    override val serversEmptySubtitle = "Use the + button to add your first MCP server"

    override val presetsEmptyTitle = "No presets yet"

    override val presetsEmptySubtitle = "Use the + button to add your first preset"

    override fun presetsEmptySubtitleForActiveMode(modeName: String): String =
        "Active mode: $modeName. Create presets with a connected AI agent."

    override val agentsEmptyTitle = "No agents yet"

    override val agentsEmptySubtitle = "Use the + button to add your first agent"

    override val clientsEmptyTitle = "No clients yet"

    override val clientsEmptySubtitle = "Client connectors will appear here"

    override val deleteServerTitle = "Delete server"

    override val deletePresetTitle = "Delete preset"

    override val deleteAgentTitle = "Delete agent"

    override fun deleteServerPrompt(name: String): String = "Remove \"$name\"?"

    override fun deletePresetPrompt(name: String): String = "Remove \"$name\"?"

    override fun deleteAgentPrompt(name: String): String = "Remove \"$name\"?"

    override val deleteServerDescription =
        "This removes the server configuration and presets that referenced it will lose access to its capabilities. " +
            "This action cannot be undone."

    override val deletePresetDescription =
        "This preset will disappear from Broxy, including the CLI shortcuts that rely on it. " +
            "This action cannot be undone."

    override val deleteAgentDescription =
        "This removes the agent configuration and schedule. This action cannot be undone."

    override val editServer = "Edit server"

    override val addServer = "Add server"

    override val editPreset = "Edit preset"

    override val addPreset = "Add preset"

    override val editAgent = "Edit agent"

    override val addAgent = "Add agent"

    override val createAgent = "Create agent"

    override val createPreset = "Create preset"

    override val customAgentPreset = "Custom capabilities"

    override val serverNotFound = "Server not found."

    override val presetNotFound = "Preset not found."

    override val agentNotFound = "Agent not found."

    override val nameLabel = "Name"

    override val cancel = "Cancel"

    override val continueInBrowser = "Continue in Browser"

    override val skip = "Skip"

    override val close = "Close"

    override val add = "Add"

    override val save = "Save"

    override val connect = "Connect"

    override val disconnect = "Disconnect"

    override val delete = "Delete"

    override val edit = "Edit"

    override val back = "Back"

    override val commandNotFound = "Command not found on PATH."

    override val serverFallbackName = "Server"

    override fun connecting(seconds: Long): String = "Connecting: $seconds s"

    override fun authorization(seconds: Long): String = "Authorization: $seconds s"

    override fun authorizationPopupTitle(name: String): String = "Authorize $name"

    override val authorizationPopupPermissionSubtitle =
        "This server wants to open a browser page for OAuth authorization."

    override val authorizationPopupSubtitle =
        "Finish sign-in in the browser tab that just opened, then return here."

    override val authorizationDialogTitle = "Server Authorization"

    override val separatorDot = " • "

    override val noDescriptionProvided = "No description provided"

    override val mcpServersTitle = "MCP servers"

    override val importSectionTitle = "Import"

    override val importButtonLabel = "Import"

    override val hideButtonLabel = "Hide"

    override val agentIdentitySection = "Agent"

    override val agentLlmSection = "LLM"

    override val agentPresetSourceSection = "Capabilities Source"

    override val agentDescriptionLabel = "Agent description"

    override val agentDescriptionGenerateTitle = "Generate agent description"

    override val agentDescriptionGenerateSubtitle =
        "Create a concise 30-36 word summary from the name, system prompt, and selected capabilities."

    override val agentGenerateScreenTitle = "Generate Agent"

    override val agentGenerateRequestLabel = "Request"

    override val agentGenerateRequestPlaceholder = "Describe what you want this agent to do."

    override val generateAction = "Generate"

    override val generatingAction = "Generating..."

    override val agentGenerateStageSelectingServers = "Selecting servers"

    override val agentGenerateStageSelectingCapabilities = "Selecting capabilities"

    override val agentGenerateStageFinalizingAgent = "Finalizing agent"

    override val agentDescriptionEnableHint = "Enable AI features in Agent Settings to generate a description."

    override fun agentDescriptionGenerationFailed(details: String?): String =
        if (details.isNullOrBlank()) {
            "Failed to generate description."
        } else {
            "Failed to generate description: $details"
        }

    override val agentGenerateErrorBlankRequest = "Request cannot be blank."

    override val agentGenerateErrorAlreadyRunning = "Agent generation is already in progress."

    override val agentGenerateErrorSaveFailed = "Failed to save generated agent."

    override val systemPromptLabel = "System prompt"

    override val aiFeaturesCardTitle = "AI features"

    override val aiFeaturesCardDescription = "Configure runtime and model settings for AI-powered app features."

    override val agentGenerateAiCardTitle = "Generation settings"

    override val agentGenerateAiCardSubtitle =
        "Select runtime and model used to generate this agent from your request."

    override val aiFeaturesToggleTitle = "Enable AI features"

    override val aiFeaturesToggleDescription =
        "Allow Broxy to run internal AI features such as agent description generation."

    override val aiFeaturesRuntimeLabel = "AI runtime"

    override val aiFeaturesModelRequired = "Model cannot be blank."

    override val aiFeaturesTemperatureInvalid = "Temperature must be a valid number."

    override val aiFeaturesCodexCommandRequired = "Codex command cannot be blank."

    override val providerLabel = "Provider"

    override val aiProviderHint = "Configure language model parameters"

    override val runtimeLabel = "Runtime"

    override val runtimeHint = "Select which runtime will execute this launch"

    override val runtimeLangChain = "LangChain"

    override val runtimeCodex = "Codex CLI"

    override val runtimeDisabled = "Disabled"

    override val aiFeaturesRuntimeHint = "Select runtime for app-level AI features."

    override val codexProviderDisabledHint = "Enable Codex provider in Agent Settings to use Codex runtime."

    override val codexWebSearchLabel = "Web search"

    override val codexReasoningLabel = "Reasoning"

    override val codexReasoningLow = "Low"

    override val codexReasoningMedium = "Medium"

    override val codexReasoningHigh = "High"

    override val codexProviderToggleTitle = "Enable Codex provider"

    override val codexProviderToggleDescription = "Allow agents to run with Codex CLI runtime."

    override val providerOpenAi = "OpenAI"

    override val providerAnthropic = "Anthropic"

    override val providerLmStudio = "LM Studio"

    override val modelLabel = "Model:"

    override val temperatureLabel = "Temperature:"

    override val presetSourceLabel = "Preset"

    override val selectCapabilitiesHint = "Select tools/prompts/resources from available servers"

    override val serverDisabledBadge = "Disabled"

    override val toolsLabel = "Tools"

    override val promptsLabel = "Prompts"

    override val resourcesLabel = "Resources"

    override val presetContainsDisabledCapabilities = "Some capabilities are disabled"

    override val presetAllCapabilitiesDisabled = "All capabilities are disabled"

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

    override val mcpFilePathTitle = "MCP config path"

    override val mcpFilePathDescription = "Path to Claude-compatible mcp.json used for MCP server definitions."

    override val connectionRetryCountTitle = "Connection retries"

    override val connectionRetryCountDescription = "Retry attempts when connecting to servers."

    override val ignoreHttpsCertificateErrorsTitle = "Ignore HTTPS certificate errors"

    override val ignoreHttpsCertificateErrorsDescription =
        "Allow self-signed/invalid certificates for all downstream HTTPS/WSS MCP connections."

    override val capabilitiesRefreshTitle = "Capabilities refresh"

    override val capabilitiesRefreshDescription = "Background refresh interval (seconds)."

    override val showTrayIconTitle = "Show tray icon"

    override val showTrayIconDescription = "Display the Broxy icon in the system tray."

    override val agentRunNotificationsTitle = "Agent run notifications"

    override val agentRunNotificationsDescription =
        "Show system notifications when an agent run finishes."

    override val fallbackPromptsAndResourcesToToolsTitle = "Prompts/resources as tools"

    override val fallbackPromptsAndResourcesToToolsDescription =
        "Expose prompts and resources as tools for clients that only support tools."

    override val adapterModeTitle = "Adapter mode"

    override val adapterModeDescription = "Expose a fixed adapter toolset and fetch actions on demand."

    override val providersSectionTitle = "AI providers"

    override val openAiProviderTitle = "OpenAI"

    override val openAiProviderSubtitle = "Endpoint and API key for OpenAI-compatible requests."

    override val anthropicProviderTitle = "Anthropic"

    override val anthropicProviderSubtitle = "Endpoint and API key for Anthropic requests."

    override val lmStudioProviderTitle = "LM Studio"

    override val lmStudioProviderSubtitle = "Local endpoint for LM Studio models."

    override val providerEndpointLabel = "Endpoint override"

    override val agentsDirectoryLabel = "Agents directory"

    override val agentsDirectoryDescription = "Path to Claude subagent markdown files (*.md)."

    override val providerApiKeyLabel = "API key"

    override val saveEndpoint = "Save endpoint"

    override val providerEndpointInvalid = "Enter a valid URL with http:// or https:// and a host."

    override val agentsDirectoryPickFailed = "Failed to pick agents directory."

    override val saveApiKeyAction = "Save key"

    override val clearApiKeyAction = "Clear key"

    override val logsTitle = "Logs"

    override val logsDescription = "Application logs are stored in the logs/ folder next to the configuration files."

    override val resetHiddenImportedServersTitle = "Imported MCP servers"

    override val resetHiddenImportedServersDescription = "Reset hidden imported MCP servers and scan clients again."

    override val resetHiddenImportedServersAction = "Reset hidden"

    override val openFolder = "Open folder"

    override val themeTitle = "Theme"

    override val themeDescription = "Choose light or dark appearance."

    override val themeDark = "Dark"

    override val themeLight = "Light"

    override fun httpPortSaved(port: Int): String = "HTTP port saved: $port"

    override fun requestTimeoutSaved(seconds: Int): String = "Timeout saved: ${seconds}s"

    override fun capabilitiesTimeoutSaved(seconds: Int): String = "Capabilities timeout saved: ${seconds}s"

    override fun mcpFilePathSaved(path: String): String = "MCP config path saved: $path"

    override fun connectionRetryCountSaved(count: Int): String = "Connection retries saved: $count"

    override fun ignoreHttpsCertificateErrorsToggle(enabled: Boolean): String =
        if (enabled) "HTTPS certificate errors ignored" else "HTTPS certificate validation enabled"

    override fun refreshIntervalSaved(seconds: Int): String = "Refresh interval saved: ${seconds}s"

    override fun trayIconToggle(enabled: Boolean): String = if (enabled) "Tray icon enabled" else "Tray icon disabled"

    override fun agentRunNotificationsToggle(enabled: Boolean): String =
        if (enabled) {
            "Agent run notifications enabled"
        } else {
            "Agent run notifications disabled"
        }

    override fun fallbackPromptsAndResourcesToToolsToggle(enabled: Boolean): String =
        if (enabled) "Prompt/resource tool fallback enabled" else "Prompt/resource tool fallback disabled"

    override fun adapterModeToggle(enabled: Boolean): String = if (enabled) ADAPTER_ON else ADAPTER_OFF

    override val providerSettingsSaved = "Provider settings saved"

    override fun providerApiKeySaved(providerName: String): String = "$providerName API key saved"

    override fun providerApiKeyCleared(providerName: String): String = "$providerName API key cleared"

    override val openingLogsFolder = "Opening logs folder…"

    override val resetHiddenImportedServersDone = "Hidden imported MCP servers were reset"

    override val statusRunning = "Running"

    override val statusStarting = "Starting"

    override val statusStopping = "Stopping"

    override val statusStopped = "Stopped"

    override val portAlreadyInUse = "Port already in use"

    override val addServerContentDescription = "Add server"

    override val addPresetContentDescription = "Add preset"

    override val addAgentContentDescription = "Add agent"

    override val agentGenerateFabContentDescription = "Generate agent with AI"

    override val agentGenerateMagicEmoji = "✨"

    override val saveSettingsContentDescription = "Save settings"

    override val editPresetContentDescription = "Edit preset"

    override val deletePresetContentDescription = "Delete preset"

    override val copyPresetConnectionUrlContentDescription = "Copy preset connection URL"

    override val copyConnectionSnippetContentDescription = "Copy connection snippet value"

    override val editContentDescription = "Edit"

    override val deleteContentDescription = "Delete"

    override val runAgentContentDescription = "Run agent"

    override val editScheduleContentDescription = "Edit schedule"

    override val stopAgentContentDescription = "Stop agent"

    override val refreshContentDescription = "Refresh"

    override val reorderContentDescription = "Reorder"

    override val openServerPageContentDescription = "Open server page"

    override val agentRunningNow = "Running"

    override val agentStatusPreparingRun = "Preparing run..."

    override val agentStatusLoadingCapabilities = "Loading server capabilities..."

    override val agentStatusLlmRequestVariants =
        listOf(
            "Sending request to LLM...",
            "Submitting prompt to model...",
            "Calling the language model...",
            "Forwarding query to LLM...",
            "Dispatching model request...",
        )

    override val agentStatusLlmThinkingVariants =
        listOf(
            "LLM is thinking...",
            "Waiting for model reasoning...",
            "Model is processing the request...",
            "Analyzing context in LLM...",
            "Model is preparing an answer...",
        )

    override val agentStatusLlmResponseGenerationVariants =
        listOf(
            "Generating response...",
            "Composing answer...",
            "Drafting reply...",
            "Thinking through response...",
            "Building final answer...",
        )

    override fun agentStatusToolExecution(
        toolName: String,
        serverId: String,
    ): String = "Running tool $toolName on server $serverId"

    override fun runAgentTitle(agentName: String): String = "Run $agentName"

    override fun stopAgentTitle(agentName: String): String = "Stop $agentName"

    override val stopAgentWarning = "The current run will be interrupted immediately. Do you want to continue?"

    override val stopAgentConfirm = "Stop"

    override val runAgentPromptLabel = "Prompt for this run."

    override val promptLabel = "Prompt"

    override val workspaceLabel = "Workspace"

    override val filesystemAccessLabel = "File system"

    override val filesystemNoAccess = "No access"

    override val filesystemReadOnly = "Read-only"

    override val filesystemReadWrite = "Read-write"

    override val workspacePickFailed = "Failed to pick workspace directory."

    override fun workspaceDirectoryMissing(path: String): String = "Workspace directory is missing: $path"

    override val cronLabel = "Cron (optional)"

    override val cronOptionalHint = "Leave empty to run immediately."

    override val runOnScheduleRemovalHint = "Launching now will remove the existing schedule for this agent."

    override val scheduleCardHint = "Regular task run settings"

    override val schedulePatternLabel = "Pattern"

    override val schedulePatternDisabled = "Disabled"

    override val schedulePatternEveryMinutes = "Every N minutes"

    override val schedulePatternEveryHours = "Every N hours"

    override val schedulePatternDaily = "Daily"

    override val schedulePatternWeekdays = "Weekdays (Mon-Fri)"

    override val schedulePatternWeekly = "Weekly"

    override val schedulePatternMonthly = "Monthly"

    override val scheduleEveryMinutesLabel = "Every minutes"

    override val scheduleEveryHoursLabel = "Every hours"

    override val scheduleHourLabel = "Hour"

    override val scheduleMinuteLabel = "Minute"

    override val scheduleWeekdaysLabel = "Days of week"

    override val scheduleDayOfMonthLabel = "Day of month"

    override val advancedCronToggle = "Advanced cron"

    override val advancedCronLabel = "Cron"

    override val customScheduleHint = "Custom cron schedule preserved."

    override val customSchedule = "Custom schedule"

    override val scheduleNextRunsLabel = "Next runs"

    override val scheduleInvalidConfiguration = "Invalid schedule configuration."

    override val scheduleInvalidCron = "Invalid cron expression."

    override fun scheduleSummaryEveryMinutes(minutes: Int): String = "Every $minutes minute(s)"

    override fun scheduleSummaryEveryHours(
        hours: Int,
        minute: String,
    ): String = "Every $hours hour(s) at :$minute"

    override fun scheduleSummaryDaily(time: String): String = "Daily at $time"

    override fun scheduleSummaryWeekdays(time: String): String = "Weekdays at $time"

    override fun scheduleSummaryWeekly(
        days: String,
        time: String,
    ): String = "Weekly on $days at $time"

    override fun scheduleSummaryMonthly(
        day: Int,
        time: String,
    ): String = "Monthly on day $day at $time"

    override val runAgentNow = "Run now"

    override val saveSchedule = "Save schedule"

    override val launchAction = "Launch"

    override val scheduleAction = "Schedule"

    override val removeSchedule = "Remove schedule"

    override val runHistoryTitle = "Recent runs"

    override val runHistoryEmpty = "No runs yet."

    override val runHistoryPromptPrefix = "Prompt:"

    override val runHistoryResponsePrefix = "Response:"

    override val runHistoryErrorPrefix = "Error:"

    override val runHistoryStatusSuccess = "Success"

    override val runHistoryStatusFailed = "Failed"

    override val runHistoryStatusSkipped = "Skipped"

    override val runHistoryTriggerManual = "Manual"

    override val runHistoryTriggerScheduled = "Scheduled"

    override val runsTitle = "Runs"

    override val runsEmptyTitle = "No runs yet"

    override val runsEmptySubtitle = "Agent runs will appear here after launch."

    override fun runsDetailsTitle(agentName: String): String = "Run details: $agentName"

    override val runsDialogueSectionTitle = "Dialogue"

    override val runsDialogueEmpty = "No dialogue entries."

    override val runsActionsSectionTitle = "Actions"

    override val runsActionsEmpty = "No action entries."

    override val runNotFound = "Run not found."

    override val trayFailedToLoadPresets = "Failed to load presets"

    override val trayServerStatusUnknown = "Server status: unknown"

    override val trayServerStatusUnavailable = "Server status: unavailable"

    override val trayNoPresetsAvailable = "No presets available"

    override fun trayServerStatus(statusText: String): String = "HTTP server: $statusText"

    override val trayShowApp = "Show Broxy"

    override val trayExit = "Exit"

    override val trayStatusStarting = "starting"

    override val trayStatusOn = "on"

    override val trayStatusStopping = "stopping"

    override val trayStatusOff = "off"

    override val trayStatusError = "error"

    override val trayActivePresetMarker = " \u2713"

    override fun agentRunNotificationSuccessTitle(agentName: String): String = "$agentName: run completed"

    override fun agentRunNotificationFailureTitle(agentName: String): String = "$agentName: run failed"

    override val agentRunNotificationPermissionTitle = "Enable Broxy notifications"

    override val agentRunNotificationPermissionDescription =
        "Allow notifications for Broxy in macOS settings when prompted."

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
        val suffix = details?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        return "Connection failed$suffix. Saved as disabled."
    }

    override fun savedName(name: String): String = "Saved $name"

    override val connectionUrlCopied = "Connection URL copied"

    override val connectionSnippetCopied = "Connection value copied"
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
