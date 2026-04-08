@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiAgentAiFeaturesSettings
import io.qent.broxy.ui.adapter.models.UiAgentCodexConfig
import io.qent.broxy.ui.adapter.models.UiAgentCodexReasoningEffort
import io.qent.broxy.ui.adapter.models.UiAgentGenerationStage
import io.qent.broxy.ui.adapter.models.UiAgentLlmConfig
import io.qent.broxy.ui.adapter.models.UiAgentProviderSettings
import io.qent.broxy.ui.adapter.models.UiAgentRuntime
import io.qent.broxy.ui.adapter.models.UiLlmProvider
import io.qent.broxy.ui.adapter.store.AGENT_GENERATION_ERROR_ALREADY_RUNNING
import io.qent.broxy.ui.adapter.store.AGENT_GENERATION_ERROR_BLANK_REQUEST
import io.qent.broxy.ui.adapter.store.AGENT_GENERATION_ERROR_SAVE_FAILED
import io.qent.broxy.ui.adapter.store.AgentGenerationState
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppPrimaryButton
import io.qent.broxy.ui.components.AppSecondaryButton
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.EditorHeaderRow
import io.qent.broxy.ui.strings.AppStrings
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun AgentGenerateScreen(
    ui: UIState,
    store: AppStore,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val strings = LocalStrings.current
    val readyUi = ui as? UIState.Ready
    if (readyUi == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            EditorHeaderRow(
                title = strings.agentGenerateScreenTitle,
                onBack = onBack,
            )
            Text(
                text = strings.loading,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val generationState by store.agentGenerationState.collectAsState()
    val providerSettings = readyUi.agentProviderSettings
    val initialConfig =
        remember(providerSettings.aiFeatures) {
            resolveInitialGenerateAiConfig(providerSettings)
        }
    var runtimeSelection by
        remember(providerSettings.aiFeatures) {
            mutableStateOf(initialConfig.runtimeSelection)
        }
    var llmProvider by
        remember(providerSettings.aiFeatures) {
            mutableStateOf(initialConfig.llmProvider)
        }
    var llmModel by
        remember(providerSettings.aiFeatures) {
            mutableStateOf(initialConfig.llmModel)
        }
    var llmTemperatureInput by
        remember(providerSettings.aiFeatures) {
            mutableStateOf(initialConfig.llmTemperatureInput)
        }
    var codexModel by
        remember(providerSettings.aiFeatures) {
            mutableStateOf(initialConfig.codexModel)
        }
    var codexReasoningEffort by
        remember(providerSettings.aiFeatures) {
            mutableStateOf(initialConfig.codexReasoningEffort)
        }

    val parsedTemperature = llmTemperatureInput.trim().toDoubleOrNull()
    val llmModelError =
        if (runtimeSelection == AiFeaturesRuntimeSelection.LANGCHAIN && llmModel.trim().isBlank()) {
            strings.aiFeaturesModelRequired
        } else {
            null
        }
    val codexModelError =
        if (runtimeSelection == AiFeaturesRuntimeSelection.CODEX_CLI && codexModel.trim().isBlank()) {
            strings.aiFeaturesModelRequired
        } else {
            null
        }
    val llmTemperatureError =
        if (runtimeSelection == AiFeaturesRuntimeSelection.LANGCHAIN && parsedTemperature == null) {
            strings.aiFeaturesTemperatureInvalid
        } else {
            null
        }
    val generationConfigValid =
        llmModelError == null &&
            codexModelError == null &&
            llmTemperatureError == null &&
            runtimeSelection != AiFeaturesRuntimeSelection.DISABLED &&
            (runtimeSelection != AiFeaturesRuntimeSelection.CODEX_CLI || providerSettings.enableCodexProvider)
    val localGenerationConfig =
        if (!generationConfigValid) {
            null
        } else {
            UiAgentAiFeaturesSettings(
                enabled = true,
                runtime =
                    when (runtimeSelection) {
                        AiFeaturesRuntimeSelection.LANGCHAIN -> UiAgentRuntime.LANGCHAIN
                        AiFeaturesRuntimeSelection.CODEX_CLI -> UiAgentRuntime.CODEX_CLI
                        AiFeaturesRuntimeSelection.DISABLED -> UiAgentRuntime.LANGCHAIN
                    },
                llm =
                    UiAgentLlmConfig(
                        provider = llmProvider,
                        model = llmModel.trim(),
                        temperature = parsedTemperature ?: providerSettings.aiFeatures.llm.temperature,
                    ),
                codex =
                    UiAgentCodexConfig(
                        model = codexModel.trim(),
                        reasoningEffort = codexReasoningEffort,
                        webSearch = false,
                    ),
            )
        }
    val viewState =
        remember(generationState, strings, generationConfigValid) {
            resolveAgentGenerateScreenState(
                state = generationState,
                strings = strings,
                isGenerationConfigValid = generationConfigValid,
            )
        }
    val actionRowHeight = 40.dp
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AppTheme.spacing.xs))

            EditorHeaderRow(
                title = strings.agentGenerateScreenTitle,
                onBack = onBack,
                actions = {
                    AppSecondaryButton(
                        onClick = onSkip,
                        modifier = Modifier.height(actionRowHeight),
                    ) {
                        Text(
                            text = strings.skip,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    AppPrimaryButton(
                        onClick = {
                            val config = localGenerationConfig ?: return@AppPrimaryButton
                            store.startGenerateAgentFromRequest(config)
                        },
                        enabled = viewState.generateEnabled,
                        modifier = Modifier.height(actionRowHeight),
                    ) {
                        Text(
                            text = viewState.generateButtonLabel,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )

            OutlinedTextField(
                value = generationState.request,
                onValueChange = store::updateAgentGenerationRequest,
                label = { Text(strings.agentGenerateRequestLabel) },
                placeholder = { Text(strings.agentGenerateRequestPlaceholder) },
                enabled = viewState.requestEnabled,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 10,
            )

            if (viewState.errorMessage != null) {
                Text(
                    text = viewState.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = AppTheme.spacing.xs),
                )
            }

            AgentAiFeaturesCard(
                store = store,
                providerSettings = providerSettings,
                runtimeSelection = runtimeSelection,
                llmProvider = llmProvider,
                llmModel = llmModel,
                llmTemperatureInput = llmTemperatureInput,
                codexModel = codexModel,
                codexReasoningEffort = codexReasoningEffort,
                codexProviderEnabled = providerSettings.enableCodexProvider,
                llmModelError = llmModelError,
                llmTemperatureError = llmTemperatureError,
                codexModelError = codexModelError,
                onRuntimeSelectionChange = { selection ->
                    runtimeSelection =
                        when (selection) {
                            AiFeaturesRuntimeSelection.DISABLED -> AiFeaturesRuntimeSelection.LANGCHAIN
                            else -> selection
                        }
                },
                onLlmProviderChange = { llmProvider = it },
                onLlmModelChange = { llmModel = it },
                onLlmTemperatureChange = { llmTemperatureInput = it },
                onCodexModelChange = { codexModel = it },
                onCodexReasoningEffortChange = { codexReasoningEffort = it },
                showDisabledRuntimeOption = false,
                titleOverride = strings.agentGenerateAiCardTitle,
                subtitleOverride = strings.agentGenerateAiCardSubtitle,
            )

            Spacer(Modifier.height(AppTheme.spacing.md))
        }

        AppVerticalScrollbar(
            scrollState = scrollState,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .offset(x = AppTheme.spacing.md - AppTheme.strokeWidths.hairline),
        )
    }
}

internal data class AgentGenerateScreenState(
    val requestEnabled: Boolean,
    val generateEnabled: Boolean,
    val generateButtonLabel: String,
    val errorMessage: String? = null,
)

internal fun resolveAgentGenerateScreenState(
    state: AgentGenerationState,
    strings: AppStrings,
    isGenerationConfigValid: Boolean = true,
): AgentGenerateScreenState {
    val hasRequest = state.request.trim().isNotBlank()
    val stageLabel = resolveAgentGenerationStageLabel(state.stage, strings)
    val generateButtonLabel =
        if (state.isRunning) {
            if (stageLabel == null) {
                strings.generatingAction
            } else {
                "${strings.generatingAction} ($stageLabel)"
            }
        } else {
            strings.generateAction
        }
    return AgentGenerateScreenState(
        requestEnabled = !state.isRunning,
        generateEnabled = !state.isRunning && hasRequest && isGenerationConfigValid,
        generateButtonLabel = generateButtonLabel,
        errorMessage = resolveAgentGenerationErrorMessage(state.errorMessage, strings),
    )
}

internal data class GenerateAiConfigSeed(
    val runtimeSelection: AiFeaturesRuntimeSelection,
    val llmProvider: UiLlmProvider,
    val llmModel: String,
    val llmTemperatureInput: String,
    val codexModel: String,
    val codexReasoningEffort: UiAgentCodexReasoningEffort,
)

internal fun resolveInitialGenerateAiConfig(settings: UiAgentProviderSettings): GenerateAiConfigSeed {
    val aiFeatures = settings.aiFeatures
    return GenerateAiConfigSeed(
        runtimeSelection =
            when (aiFeatures.runtime) {
                UiAgentRuntime.LANGCHAIN -> AiFeaturesRuntimeSelection.LANGCHAIN
                UiAgentRuntime.CODEX_CLI -> AiFeaturesRuntimeSelection.CODEX_CLI
            },
        llmProvider = aiFeatures.llm.provider,
        llmModel = aiFeatures.llm.model,
        llmTemperatureInput = aiFeatures.llm.temperature.toString(),
        codexModel = aiFeatures.codex.model,
        codexReasoningEffort = aiFeatures.codex.reasoningEffort,
    )
}

internal fun generationRuntimeOptions(): List<AiFeaturesRuntimeSelection> =
    listOf(
        AiFeaturesRuntimeSelection.LANGCHAIN,
        AiFeaturesRuntimeSelection.CODEX_CLI,
    )

internal fun resolveAgentGenerationStageLabel(
    stage: UiAgentGenerationStage?,
    strings: AppStrings,
): String? =
    when (stage) {
        UiAgentGenerationStage.SELECTING_SERVERS -> strings.agentGenerateStageSelectingServers
        UiAgentGenerationStage.SELECTING_CAPABILITIES -> strings.agentGenerateStageSelectingCapabilities
        UiAgentGenerationStage.FINALIZING_AGENT -> strings.agentGenerateStageFinalizingAgent
        null -> null
    }

internal fun resolveAgentGenerationErrorMessage(
    rawMessage: String?,
    strings: AppStrings,
): String? {
    val normalized = rawMessage?.trim()
    if (normalized.isNullOrBlank()) {
        return null
    }
    return when (normalized) {
        AGENT_GENERATION_ERROR_ALREADY_RUNNING -> strings.agentGenerateErrorAlreadyRunning
        AGENT_GENERATION_ERROR_BLANK_REQUEST -> strings.agentGenerateErrorBlankRequest
        AGENT_GENERATION_ERROR_SAVE_FAILED -> strings.agentGenerateErrorSaveFailed
        else -> normalized
    }
}
