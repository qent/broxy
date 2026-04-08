package io.qent.broxy.agents

data class ResolvedAgentLaunchDefaults(
    val runtime: AgentRuntime,
    val llm: AgentLlmConfig,
    val codex: AgentCodexConfig,
    val fileSystem: AgentFileSystemSettings,
)

fun resolveAgentLaunchDefaults(agent: AgentDefinition): ResolvedAgentLaunchDefaults {
    val manual = agent.manualLaunchDefaults
    val schedule = agent.schedule
    return ResolvedAgentLaunchDefaults(
        runtime = manual?.runtime ?: schedule?.runtime ?: AgentRuntime.LANGCHAIN,
        llm = manual?.llm ?: schedule?.llm ?: defaultAgentLlmConfig(),
        codex = manual?.codex ?: schedule?.codex ?: AgentCodexConfig(),
        fileSystem = manual?.fileSystem ?: schedule?.fileSystem ?: AgentFileSystemSettings(),
    )
}
