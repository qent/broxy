package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor

/**
 * Manages MCP namespace concerns: prefixing tool names with server id to avoid
 * collisions and resolving prefixed names back to target server + tool.
 */
interface NamespaceManager {
    /** Adds a `serverId_` prefix to the tool name. */
    fun prefixToolName(
        serverId: String,
        toolName: String,
    ): String

    /** Parses a prefixed tool name in form `serverId_tool` into pair. */
    fun parsePrefixedToolName(name: String): Pair<String, String>

    /**
     * Produces a merged capabilities view with all tools prefixed by server id.
     * Resources and prompts are concatenated as-is.
     */
    fun prefixAllCapabilities(all: Map<String, ServerCapabilities>): ServerCapabilities
}

internal const val NAMESPACE_SEPARATOR: Char = '_'

internal fun isSafeNamespaceServerId(serverId: String): Boolean = !serverId.contains(NAMESPACE_SEPARATOR)

class DefaultNamespaceManager : NamespaceManager {
    override fun prefixToolName(
        serverId: String,
        toolName: String,
    ): String = "${serverId}${NAMESPACE_SEPARATOR}$toolName"

    override fun parsePrefixedToolName(name: String): Pair<String, String> {
        val idx = name.indexOf(NAMESPACE_SEPARATOR)
        require(idx > 0 && idx < name.length - 1) { "Tool name must be in 'serverId_toolName' format" }
        val serverId = name.substring(0, idx)
        val tool = name.substring(idx + 1)
        return serverId to tool
    }

    override fun prefixAllCapabilities(all: Map<String, ServerCapabilities>): ServerCapabilities {
        val tools = mutableListOf<ToolDescriptor>()
        val resources = mutableListOf<ResourceDescriptor>()
        val prompts = mutableListOf<PromptDescriptor>()
        all.forEach { (serverId, caps) ->
            tools += caps.tools.map { it.copy(name = prefixToolName(serverId, it.name)) }
            resources += caps.resources
            prompts += caps.prompts
        }
        return ServerCapabilities(tools = tools, resources = resources, prompts = prompts)
    }
}
