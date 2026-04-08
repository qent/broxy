package io.qent.broxy.agents.infrastructure.persistence

import java.nio.file.Path

internal data class AgentStorageLayout(
    val rootDir: Path,
    val defaultDefinitionsDir: Path,
    val metadataDir: Path,
    val runsDir: Path,
    val runsIndexFile: Path,
    val settingsFile: Path,
    val secretsFile: Path,
) {
    fun agentMarkdownFile(
        definitionsDir: Path,
        id: String,
    ): Path = definitionsDir.resolve("$id.md")

    fun agentSidecarFile(id: String): Path = metadataDir.resolve("agent_$id.json")

    fun runFile(runId: String): Path = runsDir.resolve("run_$runId.json")
}

internal fun agentStorageLayout(baseDir: Path): AgentStorageLayout {
    val root = baseDir.resolve("agents")
    return AgentStorageLayout(
        rootDir = root,
        defaultDefinitionsDir = root,
        metadataDir = root.resolve("metadata"),
        runsDir = root.resolve("runs"),
        runsIndexFile = root.resolve("runs_index.json"),
        settingsFile = root.resolve("agents_settings.json"),
        secretsFile = root.resolve("agents_secrets.json"),
    )
}
