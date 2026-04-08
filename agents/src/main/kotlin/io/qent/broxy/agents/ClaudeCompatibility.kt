@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "MaxLineLength",
)

package io.qent.broxy.agents

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig

data class ClaudeFileSystemAccessResolution(
    val access: AgentFileSystemAccess,
    val warnings: List<String> = emptyList(),
)

data class ClaudeMcpMergeResult(
    val config: McpServersConfig,
    val warnings: List<String> = emptyList(),
)

fun resolveClaudeFileSystemAccess(
    agent: AgentDefinition,
    requestedAccess: AgentFileSystemAccess,
): ClaudeFileSystemAccessResolution {
    val allowedEntries =
        agent.claudeTools
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
    val disallowedEntries =
        agent.claudeDisallowedTools
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
    val hasAllowed = allowedEntries.isNotEmpty()
    val hasDisallowed = disallowedEntries.isNotEmpty()
    if (!hasAllowed && !hasDisallowed) {
        return ClaudeFileSystemAccessResolution(requestedAccess)
    }

    val warnings = mutableListOf<String>()
    val allowedTokens = allowedEntries.map { it.toClaudeToolToken() }.toSet()
    val disallowedTokens = disallowedEntries.map { it.toClaudeToolToken() }.toSet()

    var readAllowed = !hasAllowed || allowedTokens.any { it in FILESYSTEM_READ_TOKENS || it in FILESYSTEM_WRITE_TOKENS }
    var writeAllowed = !hasAllowed || allowedTokens.any { it in FILESYSTEM_WRITE_TOKENS }

    if (disallowedTokens.any { it in FILESYSTEM_READ_TOKENS }) {
        if (writeAllowed) {
            warnings += "Claude disallowedTools blocks read tools; Broxy cannot keep write-only mode and will disable filesystem access."
        }
        readAllowed = false
        writeAllowed = false
    } else if (disallowedTokens.any { it in FILESYSTEM_WRITE_TOKENS }) {
        writeAllowed = false
    }

    val mapped =
        when {
            !readAllowed -> AgentFileSystemAccess.NONE
            writeAllowed -> AgentFileSystemAccess.READ_WRITE
            else -> AgentFileSystemAccess.READ_ONLY
        }

    if (mapped != requestedAccess) {
        warnings +=
            "Filesystem access '$requestedAccess' was adjusted to '$mapped' from Claude tools/disallowedTools."
    }

    val unknownAllowed =
        allowedTokens
            .filterNot { it in KNOWN_CLAUDE_TOOL_TOKENS }
            .toSet()
    if (unknownAllowed.isNotEmpty()) {
        warnings +=
            "Claude tools entries ${unknownAllowed.joinToString(", ")} are unsupported by Broxy filesystem mapping and were ignored."
    }

    val unknownDisallowed =
        disallowedTokens
            .filterNot { it in KNOWN_CLAUDE_TOOL_TOKENS }
            .toSet()
    if (unknownDisallowed.isNotEmpty()) {
        warnings +=
            "Claude disallowedTools entries ${unknownDisallowed.joinToString(
                ", ",
            )} are unsupported by Broxy filesystem mapping and were ignored."
    }

    return ClaudeFileSystemAccessResolution(access = mapped, warnings = warnings)
}

fun resolveClaudePermissionModeWarning(agent: AgentDefinition): String? {
    val mode = agent.claudePermissionMode?.trim().orEmpty()
    if (mode.isBlank()) {
        return null
    }
    return "Claude permissionMode='$mode' is advisory-only in Broxy and does not change runtime sandbox policies."
}

fun mergeAgentMcpServers(
    baseConfig: McpServersConfig,
    agent: AgentDefinition,
): ClaudeMcpMergeResult {
    val refs = agent.claudeMcpServers.orEmpty()
    if (refs.isEmpty()) {
        return ClaudeMcpMergeResult(config = baseConfig)
    }
    val warnings = mutableListOf<String>()
    val mergedById =
        linkedMapOf<String, McpServerConfig>().apply {
            baseConfig.servers.forEach { put(it.id, it) }
        }
    refs.forEach { reference ->
        val id = reference.id.trim()
        if (id.isBlank()) {
            return@forEach
        }
        val inline = reference.inlineConfig
        if (inline == null) {
            if (mergedById[id] == null) {
                warnings += "Claude mcpServers references '$id', but this server is absent in mcp.json and no inline config was provided."
            }
            return@forEach
        }
        val normalizedInline =
            inline.copy(
                id = id,
                name = inline.name.trim().ifBlank { id },
            )
        if (mergedById[id] != null) {
            warnings += "Claude inline mcpServers config overrides server '$id' from mcp.json for this run."
        }
        mergedById[id] = normalizedInline
    }
    return ClaudeMcpMergeResult(
        config = baseConfig.copy(servers = mergedById.values.toList()),
        warnings = warnings,
    )
}

private fun String.toClaudeToolToken(): String = trim().substringBefore("(").trim().lowercase()

private val FILESYSTEM_READ_TOKENS = setOf("read", "ls", "glob", "grep")
private val FILESYSTEM_WRITE_TOKENS = setOf("write", "edit", "multiedit", "notebookedit")
private val KNOWN_CLAUDE_TOOL_TOKENS =
    setOf(
        "agent",
        "task",
        "bash",
        "edit",
        "multiedit",
        "glob",
        "grep",
        "ls",
        "read",
        "write",
        "webfetch",
        "websearch",
        "notebookedit",
    )
