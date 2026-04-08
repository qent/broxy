@file:Suppress("FunctionNaming", "MatchingDeclarationName", "TooManyFunctions")

package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiAgentAiFeaturesSettings
import io.qent.broxy.ui.adapter.models.UiAgentCodexConfig
import io.qent.broxy.ui.adapter.models.UiAgentCodexReasoningEffort
import io.qent.broxy.ui.adapter.models.UiAgentLlmConfig
import io.qent.broxy.ui.adapter.models.UiAgentProviderConfig
import io.qent.broxy.ui.adapter.models.UiAgentProviderSettings
import io.qent.broxy.ui.adapter.models.UiAgentRuntime
import io.qent.broxy.ui.adapter.models.UiLlmProvider
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

private const val TOGGLE_SCALE = 0.7f
private const val API_KEY_MASK = "****************************************"

private val PROVIDER_TITLE_COLUMN_WIDTH = 240.dp

internal enum class AiFeaturesRuntimeSelection {
    DISABLED,
    LANGCHAIN,
    CODEX_CLI,
}

internal data class AiFeaturesRuntimeState(
    val enabled: Boolean,
    val runtime: UiAgentRuntime,
)

internal fun aiFeaturesRuntimeSelection(
    enabled: Boolean,
    runtime: UiAgentRuntime,
): AiFeaturesRuntimeSelection =
    if (!enabled) {
        AiFeaturesRuntimeSelection.DISABLED
    } else {
        when (runtime) {
            UiAgentRuntime.LANGCHAIN -> AiFeaturesRuntimeSelection.LANGCHAIN
            UiAgentRuntime.CODEX_CLI -> AiFeaturesRuntimeSelection.CODEX_CLI
        }
    }

internal fun applyAiFeaturesRuntimeSelection(
    currentRuntime: UiAgentRuntime,
    selection: AiFeaturesRuntimeSelection,
): AiFeaturesRuntimeState =
    when (selection) {
        AiFeaturesRuntimeSelection.DISABLED ->
            AiFeaturesRuntimeState(enabled = false, runtime = currentRuntime)
        AiFeaturesRuntimeSelection.LANGCHAIN ->
            AiFeaturesRuntimeState(enabled = true, runtime = UiAgentRuntime.LANGCHAIN)
        AiFeaturesRuntimeSelection.CODEX_CLI ->
            AiFeaturesRuntimeState(enabled = true, runtime = UiAgentRuntime.CODEX_CLI)
    }

@Suppress("LongParameterList")
internal fun buildAiFeaturesSettingsForSave(
    enabled: Boolean,
    runtime: UiAgentRuntime,
    llmProvider: UiLlmProvider,
    llmModel: String,
    llmTemperature: Double,
    codexModel: String,
    codexReasoningEffort: UiAgentCodexReasoningEffort,
): UiAgentAiFeaturesSettings =
    UiAgentAiFeaturesSettings(
        enabled = enabled,
        runtime = runtime,
        llm =
            UiAgentLlmConfig(
                provider = llmProvider,
                model = llmModel.trim(),
                temperature = llmTemperature,
            ),
        codex =
            UiAgentCodexConfig(
                model = codexModel.trim(),
                reasoningEffort = codexReasoningEffort,
                webSearch = false,
            ),
    )

@Composable
@Suppress("LongMethod")
fun AgentSettingsScreen(
    ui: UIState,
    store: AppStore,
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
                AgentSettingsContent(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = AppTheme.spacing.md),
                    agentRunNotificationsEnabled = ui.agentRunNotificationsEnabled,
                    agentProviderSettings = ui.agentProviderSettings,
                    store = store,
                    onFabStateChange = onFabStateChange,
                    onToggleAgentRunNotifications = { enabled ->
                        ui.intents.updateAgentRunNotificationsEnabled(enabled)
                        notify(strings.agentRunNotificationsToggle(enabled))
                    },
                    onSaveAgentProviderSettings = { settings ->
                        ui.intents.saveAgentProviderSettings(settings)
                        notify(strings.providerSettingsSaved)
                    },
                    onSaveProviderApiKey = { provider, apiKey ->
                        ui.intents.saveAgentProviderApiKey(provider, apiKey)
                        val providerName =
                            when (provider) {
                                UiLlmProvider.OPENAI -> strings.providerOpenAi
                                UiLlmProvider.ANTHROPIC -> strings.providerAnthropic
                                UiLlmProvider.LM_STUDIO -> strings.providerLmStudio
                            }
                        notify(strings.providerApiKeySaved(providerName))
                    },
                )
        }
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
private fun AgentSettingsContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    agentRunNotificationsEnabled: Boolean,
    agentProviderSettings: UiAgentProviderSettings,
    store: AppStore,
    onFabStateChange: (SettingsFabState) -> Unit,
    onToggleAgentRunNotifications: (Boolean) -> Unit,
    onSaveAgentProviderSettings: (UiAgentProviderSettings) -> Unit,
    onSaveProviderApiKey: (UiLlmProvider, String) -> Unit,
) {
    val strings = LocalStrings.current
    var openAiEndpoint by rememberSaveable(agentProviderSettings.openAi.baseUrl) {
        mutableStateOf(agentProviderSettings.openAi.baseUrl)
    }
    var anthropicEndpoint by rememberSaveable(agentProviderSettings.anthropic.baseUrl) {
        mutableStateOf(agentProviderSettings.anthropic.baseUrl)
    }
    var lmStudioEndpoint by rememberSaveable(agentProviderSettings.lmStudio.baseUrl) {
        mutableStateOf(agentProviderSettings.lmStudio.baseUrl)
    }
    var enableCodexProvider by rememberSaveable(agentProviderSettings.enableCodexProvider) {
        mutableStateOf(agentProviderSettings.enableCodexProvider)
    }
    var agentsDirectoryPath by rememberSaveable(agentProviderSettings.agentsDirectoryPath) {
        mutableStateOf(agentProviderSettings.agentsDirectoryPath)
    }
    var openAiApiKey by rememberSaveable(agentProviderSettings.openAi.hasSavedApiKey) {
        mutableStateOf(if (agentProviderSettings.openAi.hasSavedApiKey) API_KEY_MASK else "")
    }
    var anthropicApiKey by rememberSaveable(agentProviderSettings.anthropic.hasSavedApiKey) {
        mutableStateOf(if (agentProviderSettings.anthropic.hasSavedApiKey) API_KEY_MASK else "")
    }
    var openAiApiKeyEdited by rememberSaveable(agentProviderSettings.openAi.hasSavedApiKey) {
        mutableStateOf(false)
    }
    var anthropicApiKeyEdited by rememberSaveable(agentProviderSettings.anthropic.hasSavedApiKey) {
        mutableStateOf(false)
    }

    var aiFeaturesEnabled by rememberSaveable(agentProviderSettings.aiFeatures.enabled) {
        mutableStateOf(agentProviderSettings.aiFeatures.enabled)
    }
    var aiRuntime by rememberSaveable(agentProviderSettings.aiFeatures.runtime) {
        mutableStateOf(agentProviderSettings.aiFeatures.runtime)
    }
    var aiLlmProvider by rememberSaveable(agentProviderSettings.aiFeatures.llm.provider) {
        mutableStateOf(agentProviderSettings.aiFeatures.llm.provider)
    }
    var aiLlmModel by rememberSaveable(agentProviderSettings.aiFeatures.llm.model) {
        mutableStateOf(agentProviderSettings.aiFeatures.llm.model)
    }
    var aiLlmTemperatureInput by rememberSaveable(agentProviderSettings.aiFeatures.llm.temperature) {
        mutableStateOf(
            agentProviderSettings.aiFeatures.llm.temperature
                .toString(),
        )
    }
    var aiCodexModel by rememberSaveable(agentProviderSettings.aiFeatures.codex.model) {
        mutableStateOf(agentProviderSettings.aiFeatures.codex.model)
    }
    var aiCodexReasoningEffort by rememberSaveable(agentProviderSettings.aiFeatures.codex.reasoningEffort) {
        mutableStateOf(agentProviderSettings.aiFeatures.codex.reasoningEffort)
    }

    LaunchedEffect(agentProviderSettings.openAi.baseUrl) {
        openAiEndpoint = agentProviderSettings.openAi.baseUrl
    }
    LaunchedEffect(agentProviderSettings.anthropic.baseUrl) {
        anthropicEndpoint = agentProviderSettings.anthropic.baseUrl
    }
    LaunchedEffect(agentProviderSettings.lmStudio.baseUrl) {
        lmStudioEndpoint = agentProviderSettings.lmStudio.baseUrl
    }
    LaunchedEffect(agentProviderSettings.enableCodexProvider) {
        enableCodexProvider = agentProviderSettings.enableCodexProvider
    }
    LaunchedEffect(agentProviderSettings.agentsDirectoryPath) {
        agentsDirectoryPath = agentProviderSettings.agentsDirectoryPath
    }
    LaunchedEffect(agentProviderSettings.openAi.hasSavedApiKey) {
        openAiApiKey = if (agentProviderSettings.openAi.hasSavedApiKey) API_KEY_MASK else ""
        openAiApiKeyEdited = false
    }
    LaunchedEffect(agentProviderSettings.anthropic.hasSavedApiKey) {
        anthropicApiKey = if (agentProviderSettings.anthropic.hasSavedApiKey) API_KEY_MASK else ""
        anthropicApiKeyEdited = false
    }
    LaunchedEffect(agentProviderSettings.aiFeatures.enabled) {
        aiFeaturesEnabled = agentProviderSettings.aiFeatures.enabled
    }
    LaunchedEffect(agentProviderSettings.aiFeatures.runtime) {
        aiRuntime = agentProviderSettings.aiFeatures.runtime
    }
    LaunchedEffect(agentProviderSettings.aiFeatures.llm.provider) {
        aiLlmProvider = agentProviderSettings.aiFeatures.llm.provider
    }
    LaunchedEffect(agentProviderSettings.aiFeatures.llm.model) {
        aiLlmModel = agentProviderSettings.aiFeatures.llm.model
    }
    LaunchedEffect(agentProviderSettings.aiFeatures.llm.temperature) {
        aiLlmTemperatureInput =
            agentProviderSettings.aiFeatures.llm.temperature
                .toString()
    }
    LaunchedEffect(agentProviderSettings.aiFeatures.codex.model) {
        aiCodexModel = agentProviderSettings.aiFeatures.codex.model
    }
    LaunchedEffect(agentProviderSettings.aiFeatures.codex.reasoningEffort) {
        aiCodexReasoningEffort = agentProviderSettings.aiFeatures.codex.reasoningEffort
    }

    val runtimeSelection = aiFeaturesRuntimeSelection(aiFeaturesEnabled, aiRuntime)
    val parsedTemperature = aiLlmTemperatureInput.trim().toDoubleOrNull()

    val openAiEndpointError =
        if (isValidProviderEndpoint(openAiEndpoint)) {
            null
        } else {
            strings.providerEndpointInvalid
        }
    val anthropicEndpointError =
        if (isValidProviderEndpoint(anthropicEndpoint)) {
            null
        } else {
            strings.providerEndpointInvalid
        }
    val lmStudioEndpointError =
        if (isValidProviderEndpoint(lmStudioEndpoint)) {
            null
        } else {
            strings.providerEndpointInvalid
        }

    val aiLlmModelError =
        if (runtimeSelection == AiFeaturesRuntimeSelection.LANGCHAIN && aiLlmModel.trim().isBlank()) {
            strings.aiFeaturesModelRequired
        } else {
            null
        }
    val aiCodexModelError =
        if (runtimeSelection == AiFeaturesRuntimeSelection.CODEX_CLI && aiCodexModel.trim().isBlank()) {
            strings.aiFeaturesModelRequired
        } else {
            null
        }
    val aiTemperatureError =
        if (runtimeSelection == AiFeaturesRuntimeSelection.LANGCHAIN && parsedTemperature == null) {
            strings.aiFeaturesTemperatureInvalid
        } else {
            null
        }

    val normalizedTemperature = parsedTemperature ?: agentProviderSettings.aiFeatures.llm.temperature
    val currentRuntimeSelection =
        aiFeaturesRuntimeSelection(
            enabled = agentProviderSettings.aiFeatures.enabled,
            runtime = agentProviderSettings.aiFeatures.runtime,
        )
    val aiSettingsChanged =
        runtimeSelection != currentRuntimeSelection ||
            aiLlmProvider != agentProviderSettings.aiFeatures.llm.provider ||
            aiLlmModel.trim() !=
            agentProviderSettings.aiFeatures.llm.model
                .trim() ||
            normalizedTemperature != agentProviderSettings.aiFeatures.llm.temperature ||
            aiCodexModel.trim() !=
            agentProviderSettings.aiFeatures.codex.model
                .trim() ||
            aiCodexReasoningEffort != agentProviderSettings.aiFeatures.codex.reasoningEffort ||
            agentProviderSettings.aiFeatures.codex.webSearch

    val canSaveProviderSettings =
        openAiEndpointError == null &&
            anthropicEndpointError == null &&
            lmStudioEndpointError == null &&
            aiLlmModelError == null &&
            aiCodexModelError == null &&
            aiTemperatureError == null &&
            (
                openAiEndpoint.trim() != agentProviderSettings.openAi.baseUrl.trim() ||
                    anthropicEndpoint.trim() != agentProviderSettings.anthropic.baseUrl.trim() ||
                    lmStudioEndpoint.trim() != agentProviderSettings.lmStudio.baseUrl.trim() ||
                    agentsDirectoryPath.trim() != agentProviderSettings.agentsDirectoryPath.trim() ||
                    enableCodexProvider != agentProviderSettings.enableCodexProvider ||
                    aiSettingsChanged
            )

    val saveOpenAiApiKey =
        openAiApiKeyEdited &&
            openAiApiKey.trim().isNotBlank() &&
            !(agentProviderSettings.openAi.hasSavedApiKey && openAiApiKey == API_KEY_MASK)
    val saveAnthropicApiKey =
        anthropicApiKeyEdited &&
            anthropicApiKey.trim().isNotBlank() &&
            !(agentProviderSettings.anthropic.hasSavedApiKey && anthropicApiKey == API_KEY_MASK)

    val canSaveAny = canSaveProviderSettings || saveOpenAiApiKey || saveAnthropicApiKey

    val onSave: () -> Unit = onSave@{
        if (!canSaveAny) {
            return@onSave
        }
        if (canSaveProviderSettings) {
            onSaveAgentProviderSettings(
                UiAgentProviderSettings(
                    enableCodexProvider = enableCodexProvider,
                    agentsDirectoryPath = agentsDirectoryPath.trim(),
                    openAi =
                        UiAgentProviderConfig(
                            baseUrl = openAiEndpoint.trim(),
                            hasSavedApiKey = agentProviderSettings.openAi.hasSavedApiKey,
                        ),
                    anthropic =
                        UiAgentProviderConfig(
                            baseUrl = anthropicEndpoint.trim(),
                            hasSavedApiKey = agentProviderSettings.anthropic.hasSavedApiKey,
                        ),
                    lmStudio =
                        UiAgentProviderConfig(
                            baseUrl = lmStudioEndpoint.trim(),
                            hasSavedApiKey = false,
                        ),
                    modelCache = agentProviderSettings.modelCache,
                    codex =
                        agentProviderSettings.codex.copy(
                            command = agentProviderSettings.codex.command,
                        ),
                    aiFeatures =
                        buildAiFeaturesSettingsForSave(
                            enabled = runtimeSelection != AiFeaturesRuntimeSelection.DISABLED,
                            runtime = aiRuntime,
                            llmProvider = aiLlmProvider,
                            llmModel = aiLlmModel,
                            llmTemperature = normalizedTemperature,
                            codexModel = aiCodexModel,
                            codexReasoningEffort = aiCodexReasoningEffort,
                        ),
                ),
            )
        }

        if (saveOpenAiApiKey) {
            onSaveProviderApiKey(UiLlmProvider.OPENAI, openAiApiKey.trim())
            openAiApiKeyEdited = false
        }
        if (saveAnthropicApiKey) {
            onSaveProviderApiKey(UiLlmProvider.ANTHROPIC, anthropicApiKey.trim())
            anthropicApiKeyEdited = false
        }
    }

    LaunchedEffect(
        canSaveAny,
        canSaveProviderSettings,
        saveOpenAiApiKey,
        saveAnthropicApiKey,
    ) {
        onFabStateChange(SettingsFabState(enabled = canSaveAny, onClick = onSave))
    }

    val scrollState = rememberScrollState()
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
            ProviderSettingsSection(
                agentsDirectoryPath = agentsDirectoryPath,
                onAgentsDirectoryPathChange = { agentsDirectoryPath = it },
                openAiEndpoint = openAiEndpoint,
                onOpenAiEndpointChange = { openAiEndpoint = it },
                openAiEndpointError = openAiEndpointError,
                openAiApiKey = openAiApiKey,
                onOpenAiApiKeyChange = {
                    openAiApiKey = it
                    openAiApiKeyEdited = true
                },
                anthropicEndpoint = anthropicEndpoint,
                onAnthropicEndpointChange = { anthropicEndpoint = it },
                anthropicEndpointError = anthropicEndpointError,
                anthropicApiKey = anthropicApiKey,
                onAnthropicApiKeyChange = {
                    anthropicApiKey = it
                    anthropicApiKeyEdited = true
                },
                lmStudioEndpoint = lmStudioEndpoint,
                onLmStudioEndpointChange = { lmStudioEndpoint = it },
                lmStudioEndpointError = lmStudioEndpointError,
            )
            AgentAiFeaturesCard(
                store = store,
                providerSettings = agentProviderSettings,
                runtimeSelection = runtimeSelection,
                llmProvider = aiLlmProvider,
                llmModel = aiLlmModel,
                llmTemperatureInput = aiLlmTemperatureInput,
                codexModel = aiCodexModel,
                codexReasoningEffort = aiCodexReasoningEffort,
                codexProviderEnabled = enableCodexProvider,
                llmModelError = aiLlmModelError,
                llmTemperatureError = aiTemperatureError,
                codexModelError = aiCodexModelError,
                onRuntimeSelectionChange = { selection ->
                    val next = applyAiFeaturesRuntimeSelection(aiRuntime, selection)
                    aiFeaturesEnabled = next.enabled
                    aiRuntime = next.runtime
                },
                onLlmProviderChange = { aiLlmProvider = it },
                onLlmModelChange = { aiLlmModel = it },
                onLlmTemperatureChange = { aiLlmTemperatureInput = it },
                onCodexModelChange = { aiCodexModel = it },
                onCodexReasoningEffortChange = { aiCodexReasoningEffort = it },
                showDisabledRuntimeOption = true,
            )
            AgentCodexProviderToggleSetting(
                enabled = enableCodexProvider,
                onToggle = { enableCodexProvider = it },
            )
            AgentRunNotificationsSetting(
                checked = agentRunNotificationsEnabled,
                onToggle = onToggleAgentRunNotifications,
            )
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
private fun AgentCodexProviderToggleSetting(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    SettingItem(
        title = strings.codexProviderToggleTitle,
        description = strings.codexProviderToggleDescription,
    ) {
        SettingControlBox {
            Switch(
                checked = enabled,
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
private fun AgentRunNotificationsSetting(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    SettingItem(
        title = strings.agentRunNotificationsTitle,
        description = strings.agentRunNotificationsDescription,
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
@Suppress("LongMethod", "LongParameterList")
private fun ProviderSettingsSection(
    agentsDirectoryPath: String,
    onAgentsDirectoryPathChange: (String) -> Unit,
    openAiEndpoint: String,
    onOpenAiEndpointChange: (String) -> Unit,
    openAiEndpointError: String?,
    openAiApiKey: String,
    onOpenAiApiKeyChange: (String) -> Unit,
    anthropicEndpoint: String,
    onAnthropicEndpointChange: (String) -> Unit,
    anthropicEndpointError: String?,
    anthropicApiKey: String,
    onAnthropicApiKeyChange: (String) -> Unit,
    lmStudioEndpoint: String,
    onLmStudioEndpointChange: (String) -> Unit,
    lmStudioEndpointError: String?,
) {
    val strings = LocalStrings.current

    ProviderCard(
        title = strings.agentsDirectoryLabel,
        subtitle = strings.agentsDirectoryDescription,
        error = null,
        fields = {
            CompactTextField(
                value = agentsDirectoryPath,
                onValueChange = onAgentsDirectoryPathChange,
                label = strings.agentsDirectoryLabel,
                modifier = Modifier.weight(1f),
            )
        },
    )

    ProviderCard(
        title = strings.openAiProviderTitle,
        subtitle = strings.openAiProviderSubtitle,
        error = openAiEndpointError,
        fields = {
            CompactTextField(
                value = openAiEndpoint,
                onValueChange = onOpenAiEndpointChange,
                label = strings.providerEndpointLabel,
                modifier = Modifier.weight(1f),
            )
            CompactTextField(
                value = openAiApiKey,
                onValueChange = onOpenAiApiKeyChange,
                label = strings.providerApiKeyLabel,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
        },
    )

    ProviderCard(
        title = strings.anthropicProviderTitle,
        subtitle = strings.anthropicProviderSubtitle,
        error = anthropicEndpointError,
        fields = {
            CompactTextField(
                value = anthropicEndpoint,
                onValueChange = onAnthropicEndpointChange,
                label = strings.providerEndpointLabel,
                modifier = Modifier.weight(1f),
            )
            CompactTextField(
                value = anthropicApiKey,
                onValueChange = onAnthropicApiKeyChange,
                label = strings.providerApiKeyLabel,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
        },
    )

    ProviderCard(
        title = strings.lmStudioProviderTitle,
        subtitle = strings.lmStudioProviderSubtitle,
        error = lmStudioEndpointError,
        fields = {
            CompactTextField(
                value = lmStudioEndpoint,
                onValueChange = onLmStudioEndpointChange,
                label = strings.providerEndpointLabel,
                modifier = Modifier.weight(1f),
            )
        },
    )
}

@Composable
private fun ProviderCard(
    title: String,
    subtitle: String,
    error: String?,
    fields: @Composable RowScope.() -> Unit,
) {
    SettingsLikeItem(
        title = title,
        descriptionContent = {},
        contentPadding =
            PaddingValues(
                start = AppTheme.spacing.md + AppTheme.spacing.sm,
                end = AppTheme.spacing.md,
                top = AppTheme.spacing.md,
                bottom = AppTheme.spacing.md,
            ),
        titleContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.width(PROVIDER_TITLE_COLUMN_WIDTH),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    content = fields,
                )
            }
        },
        supportingContent = {
            error?.let {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.width(PROVIDER_TITLE_COLUMN_WIDTH))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        control = {},
    )
}
