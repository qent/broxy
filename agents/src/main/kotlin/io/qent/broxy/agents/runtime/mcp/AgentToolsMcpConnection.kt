package io.qent.broxy.agents.runtime.mcp

import io.qent.broxy.agents.AgentDefinition
import io.qent.broxy.agents.AgentExecutionRequest
import io.qent.broxy.agents.AgentExecutionResult
import io.qent.broxy.agents.AgentRuntime
import io.qent.broxy.agents.LlmProvider
import io.qent.broxy.agents.resolveAgentLaunchDefaults
import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val AGENT_TOOLS_SERVER_ID = "broxyagenttools"
private const val AGENT_TOOL_NAME_PREFIX = "agent"

internal class AgentToolCycleDetectedException(
    chain: String,
) : IllegalStateException("Agent tool cycle detected: $chain")

internal data class ResolvedAgentTools(
    val connection: McpServerConnection,
    val toolRefs: List<ToolReference>,
)

@Suppress("ReturnCount")
internal fun buildAgentToolsConnection(request: AgentExecutionRequest): ResolvedAgentTools? {
    val resolver = request.resolveAgentById ?: return null
    val nestedExecutor = request.executeNestedAgent ?: return null
    val enabledRefs =
        request.agent.agentTools
            .asSequence()
            .filter { it.enabled }
            .toList()
    if (enabledRefs.isEmpty()) {
        return null
    }

    val usedNames = linkedSetOf<String>()
    val seenAgentIds = linkedSetOf<String>()
    val resolvedTools = mutableListOf<ResolvedAgentTool>()
    enabledRefs.forEach { ref ->
        val normalizedId = ref.agentId.trim()
        if (normalizedId.isBlank() || !seenAgentIds.add(normalizedId)) {
            return@forEach
        }
        val targetAgent = resolver(normalizedId) ?: return@forEach
        val toolName = buildUniqueToolName(targetAgent.id, usedNames)
        resolvedTools +=
            ResolvedAgentTool(
                toolName = toolName,
                target = targetAgent,
            )
    }

    if (resolvedTools.isEmpty()) {
        return null
    }

    val connection = AgentToolsMcpServerConnection(request, resolvedTools, nestedExecutor)
    return ResolvedAgentTools(
        connection = connection,
        toolRefs =
            resolvedTools.map { tool ->
                ToolReference(
                    serverId = connection.serverId,
                    toolName = tool.toolName,
                    enabled = true,
                )
            },
    )
}

private class AgentToolsMcpServerConnection(
    private val parentRequest: AgentExecutionRequest,
    private val tools: List<ResolvedAgentTool>,
    private val nestedExecutor: suspend (AgentExecutionRequest) -> Result<AgentExecutionResult>,
) : McpServerConnection {
    private var currentStatus: ServerStatus = ServerStatus.Stopped
    private val toolByName = tools.associateBy { it.toolName }

    override val serverId: String = AGENT_TOOLS_SERVER_ID
    override val config: McpServerConfig =
        McpServerConfig(
            id = AGENT_TOOLS_SERVER_ID,
            name = "Agent tools",
            transport = TransportConfig.StdioTransport(command = "broxy-agent-tools"),
            enabled = true,
        )
    override val status: ServerStatus
        get() = currentStatus

    override suspend fun connect(): Result<Unit> {
        currentStatus = ServerStatus.Running
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        currentStatus = ServerStatus.Stopped
    }

    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> =
        Result.success(
            ServerCapabilities(
                tools =
                    tools.map { resolved ->
                        ToolDescriptor(
                            name = resolved.toolName,
                            description = buildToolDescription(resolved.target),
                        )
                    },
            ),
        )

    @Suppress("ReturnCount")
    override suspend fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Result<JsonElement> {
        val resolved =
            toolByName[toolName]
                ?: return Result.failure(IllegalArgumentException("Unknown agent tool '$toolName'"))
        val input =
            arguments["input"]?.asStringOrNull()
                ?: return Result.failure(
                    IllegalArgumentException(
                        "Agent tool '$toolName' requires string argument 'input'",
                    ),
                )
        val lineage = parentRequest.agentInvocationStack + parentRequest.agent.id
        if (resolved.target.id in lineage) {
            val chain = (lineage + resolved.target.id).joinToString(" -> ")
            return Result.failure(AgentToolCycleDetectedException(chain))
        }

        val launch = resolveAgentLaunchDefaults(resolved.target)
        val childRuntime = launch.runtime
        val childLlm = launch.llm
        val childRequest =
            parentRequest.copy(
                agent = resolved.target,
                runtime = childRuntime,
                llm = childLlm,
                codex = if (childRuntime == AgentRuntime.CODEX_CLI) launch.codex else null,
                prompt = input,
                fileSystem = launch.fileSystem,
                apiKey = resolveApiKey(childLlm.provider),
                agentInvocationStack = lineage,
                onOperation = {},
                onTraceDialogue = {},
                onTraceAction = {},
            )
        return nestedExecutor(childRequest).map { JsonPrimitive(it.response) }
    }

    override suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> = Result.failure(UnsupportedOperationException("Agent tool server does not expose prompts"))

    override suspend fun readResource(uri: String): Result<JsonObject> =
        Result.failure(UnsupportedOperationException("Agent tool server does not expose resources"))

    private fun resolveApiKey(provider: LlmProvider): String? {
        val fromMap =
            parentRequest.apiKeys[provider]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        if (fromMap != null) {
            return fromMap
        }
        return if (provider == parentRequest.llm.provider) {
            parentRequest.apiKey?.trim()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }
}

private fun buildToolDescription(target: AgentDefinition): String {
    val desc =
        target.description
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Runs the referenced Broxy agent."
    return "$desc Input: string field 'input'. Output: string."
}

private fun buildUniqueToolName(
    agentId: String,
    usedNames: MutableSet<String>,
): String {
    val normalized = sanitizeToolName(agentId)
    var candidate = "${AGENT_TOOL_NAME_PREFIX}_$normalized"
    var suffix = 2
    while (!usedNames.add(candidate)) {
        candidate = "${AGENT_TOOL_NAME_PREFIX}_${normalized}_$suffix"
        suffix += 1
    }
    return candidate
}

private fun sanitizeToolName(value: String): String {
    val builder = StringBuilder()
    value.trim().forEach { ch ->
        if (ch.isLetterOrDigit()) {
            builder.append(ch.lowercaseChar())
        } else if (ch == '-' || ch == '_') {
            builder.append(ch)
        } else if (builder.isNotEmpty() && builder.last() != '-') {
            builder.append('-')
        }
    }
    val result = builder.toString().trim('-').trim('_')
    return if (result.isBlank()) "agent" else result
}

private fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

private data class ResolvedAgentTool(
    val toolName: String,
    val target: AgentDefinition,
)
