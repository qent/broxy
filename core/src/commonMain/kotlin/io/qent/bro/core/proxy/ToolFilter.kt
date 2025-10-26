package io.qent.bro.core.proxy

import io.qent.bro.core.mcp.PromptDescriptor
import io.qent.bro.core.mcp.ResourceDescriptor
import io.qent.bro.core.mcp.ServerCapabilities
import io.qent.bro.core.mcp.ToolDescriptor
import io.qent.bro.core.models.Preset
import io.qent.bro.core.models.ToolReference
import io.qent.bro.core.utils.ConsoleLogger
import io.qent.bro.core.utils.Logger

/**
 * Filters and rewrites server capabilities according to a [Preset].
 * - Filters tools by explicit allow-list from the preset
 * - Validates availability of referenced tools on downstream servers
 * - Renames allowed tools by prefixing with server id: `serverId:toolName`
 * - Filters resources/prompts to servers referenced by the preset (by server id)
 */
interface ToolFilter {
    fun filter(
        all: Map<String, ServerCapabilities>,
        preset: Preset
    ): FilterResult
}

data class FilterResult(
    val capabilities: ServerCapabilities,
    /** Allowed tool names after prefixing, e.g. `serverId:toolName` */
    val allowedPrefixedTools: Set<String>,
    /** Warnings for tools referenced in preset but missing downstream */
    val missingTools: List<ToolReference>,
    /** Map prompt name -> serverId for routing */
    val promptServerByName: Map<String, String>,
    /** Map resource uriOrName -> serverId for routing */
    val resourceServerByUri: Map<String, String>
)

class DefaultToolFilter(
    private val logger: Logger = ConsoleLogger
) : ToolFilter {
    override fun filter(all: Map<String, ServerCapabilities>, preset: Preset): FilterResult {
        // Group desired tools by server for quick lookup
        val desiredByServer: Map<String, List<ToolReference>> = preset.tools
            .filter { it.enabled }
            .groupBy { it.serverId }

        // Determine which servers are in scope (by presence in preset)
        val inScopeServers: Set<String> = desiredByServer.keys

        val allowedPrefixed = mutableSetOf<String>()
        val missing = mutableListOf<ToolReference>()

        val filteredTools = mutableListOf<ToolDescriptor>()
        val filteredResources = mutableListOf<ResourceDescriptor>()
        val filteredPrompts = mutableListOf<PromptDescriptor>()
        val promptServer = mutableMapOf<String, String>()
        val resourceServer = mutableMapOf<String, String>()

        // Build a lookup index of tools per server for existence validation
        val toolIndex: Map<String, Set<String>> = all.mapValues { (_, caps) ->
            caps.tools.map { it.name }.toSet()
        }

        // Tools: allow only those requested by preset and present downstream; prefix names
        desiredByServer.forEach { (serverId, refs) ->
            val caps = all[serverId]
            refs.forEach { ref ->
                val exists = toolIndex[serverId]?.contains(ref.toolName) == true
                if (!exists) {
                    missing += ref
                    logger.warn("Preset references missing tool '${ref.toolName}' on server '$serverId'")
                    return@forEach
                }
                // Fetch descriptor to preserve description
                val tool = caps?.tools?.firstOrNull { it.name == ref.toolName }
                if (tool != null) {
                    val prefixed = "$serverId:${tool.name}"
                    allowedPrefixed += prefixed
                    filteredTools += ToolDescriptor(name = prefixed, description = tool.description)
                }
            }
        }

        // Resources/Prompts: include everything from servers that appear in the preset
        inScopeServers.forEach { serverId ->
            val caps = all[serverId] ?: return@forEach
            filteredResources += caps.resources
            filteredPrompts += caps.prompts
            caps.prompts.forEach { p -> promptServer.putIfAbsent(p.name, serverId) }
            caps.resources.forEach { r ->
                val key = r.uri ?: r.name
                resourceServer.putIfAbsent(key, serverId)
            }
        }

        return FilterResult(
            capabilities = ServerCapabilities(
                tools = filteredTools,
                resources = filteredResources,
                prompts = filteredPrompts
            ),
            allowedPrefixedTools = allowedPrefixed,
            missingTools = missing,
            promptServerByName = promptServer.toMap(),
            resourceServerByUri = resourceServer.toMap()
        )
    }
}
