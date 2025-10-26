package io.qent.bro.core.proxy

import io.qent.bro.core.mcp.PromptDescriptor
import io.qent.bro.core.mcp.ResourceDescriptor
import io.qent.bro.core.mcp.ServerCapabilities
import io.qent.bro.core.mcp.ToolDescriptor

/**
 * Manages MCP namespace concerns: prefixing tool names with server id to avoid
 * collisions and resolving prefixed names back to target server + tool.
 */
interface NamespaceManager {
    /** Adds a `serverId:` prefix to the tool name. */
    fun prefixToolName(serverId: String, toolName: String): String

    /** Parses a prefixed tool name in form `serverId:tool` into pair. */
    fun parsePrefixedToolName(name: String): Pair<String, String>

    /**
     * Produces a merged capabilities view with all tools prefixed by server id.
     * Resources and prompts are concatenated as-is.
     */
    fun prefixAllCapabilities(all: Map<String, ServerCapabilities>): ServerCapabilities
}

class DefaultNamespaceManager : NamespaceManager {
    override fun prefixToolName(serverId: String, toolName: String): String = "$serverId:$toolName"

    override fun parsePrefixedToolName(name: String): Pair<String, String> {
        val idx = name.indexOf(':')
        require(idx > 0 && idx < name.length - 1) { "Tool name must be in 'serverId:toolName' format" }
        val serverId = name.substring(0, idx)
        val tool = name.substring(idx + 1)
        return serverId to tool
    }

    override fun prefixAllCapabilities(all: Map<String, ServerCapabilities>): ServerCapabilities {
        val tools = mutableListOf<ToolDescriptor>()
        val resources = mutableListOf<ResourceDescriptor>()
        val prompts = mutableListOf<PromptDescriptor>()
        all.forEach { (serverId, caps) ->
            tools += caps.tools.map { ToolDescriptor(name = prefixToolName(serverId, it.name), description = it.description) }
            resources += caps.resources
            prompts += caps.prompts
        }
        return ServerCapabilities(tools = tools, resources = resources, prompts = prompts)
    }
}

