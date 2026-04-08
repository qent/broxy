package io.qent.broxy.agents

const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
const val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com"
const val DEFAULT_LM_STUDIO_BASE_URL = "http://127.0.0.1:1234/v1"
const val DEFAULT_LLM_MODEL = "gpt-5-nano"
const val DEFAULT_LLM_TEMPERATURE = 1.0
const val DEFAULT_CODEX_MODEL = "gpt-5.1-codex-mini"
const val DEFAULT_CODEX_COMMAND = "codex"
const val DEFAULT_CODEX_PORT_RANGE_START = 39600
const val DEFAULT_CODEX_PORT_RANGE_END = 39699
val DEFAULT_CODEX_REASONING_EFFORT = AgentCodexReasoningEffort.HIGH

fun defaultAgentLlmConfig(): AgentLlmConfig =
    AgentLlmConfig(
        provider = LlmProvider.OPENAI,
        model = DEFAULT_LLM_MODEL,
        temperature = DEFAULT_LLM_TEMPERATURE,
    )

fun AgentProviderSettings.baseUrlFor(provider: LlmProvider): String =
    when (provider) {
        LlmProvider.OPENAI -> openAi.baseUrl
        LlmProvider.ANTHROPIC -> anthropic.baseUrl
        LlmProvider.LM_STUDIO -> lmStudio.baseUrl
    }?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: defaultBaseUrlFor(provider)

fun defaultBaseUrlFor(provider: LlmProvider): String =
    when (provider) {
        LlmProvider.OPENAI -> DEFAULT_OPENAI_BASE_URL
        LlmProvider.ANTHROPIC -> DEFAULT_ANTHROPIC_BASE_URL
        LlmProvider.LM_STUDIO -> DEFAULT_LM_STUDIO_BASE_URL
    }
