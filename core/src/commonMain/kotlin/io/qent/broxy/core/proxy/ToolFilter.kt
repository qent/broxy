package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger

/**
 * Filters and rewrites server capabilities according to a [Preset].
 * - Filters tools by explicit allow-list from the preset
 * - Validates availability of referenced tools on downstream servers
 * - Renames allowed tools by prefixing with server id: `serverId_toolName`
 * - Filters resources/prompts to servers referenced by the preset (by server id)
 */
interface ToolFilter {
    fun filter(
        all: Map<String, ServerCapabilities>,
        preset: Preset,
    ): FilterResult
}

data class FilterResult(
    val capabilities: ServerCapabilities,
    /** Allowed tool names after prefixing, e.g. `serverId_toolName` */
    val allowedPrefixedTools: Set<String>,
    /** Warnings for tools referenced in preset but missing downstream */
    val missingTools: List<ToolReference>,
    /** Map prompt name -> serverId for routing */
    val promptServerByName: Map<String, String>,
    /** Map resource uriOrName -> serverId for routing */
    val resourceServerByUri: Map<String, String>,
)

class DefaultToolFilter(
    private val logger: Logger = ConsoleLogger,
) : ToolFilter {
    private data class ToolFilterOutcome(
        val tools: List<ToolDescriptor>,
        val allowedPrefixed: Set<String>,
        val missing: List<ToolReference>,
    )

    private data class PromptResourceOutcome(
        val prompts: List<PromptDescriptor>,
        val resources: List<ResourceDescriptor>,
        val promptServer: Map<String, String>,
        val resourceServer: Map<String, String>,
    )

    override fun filter(
        all: Map<String, ServerCapabilities>,
        preset: Preset,
    ): FilterResult {
        if (preset.id == Preset.ALL_ENABLED_PRESET_ID) {
            return filterAllEnabled(all)
        }
        val desiredToolsByServer = preset.tools.filter { it.enabled }.groupBy { it.serverId }
        val desiredPromptsByServer =
            preset.prompts
                ?.filter { it.enabled }
                ?.groupBy { it.serverId }
                .orEmpty()
        val desiredResourcesByServer =
            preset.resources
                ?.filter { it.enabled }
                ?.groupBy { it.serverId }
                .orEmpty()

        val inScopeServers =
            buildSet {
                addAll(desiredToolsByServer.keys)
                addAll(desiredPromptsByServer.keys)
                addAll(desiredResourcesByServer.keys)
            }

        val toolIndex = buildToolIndex(all)
        val toolOutcome = filterTools(all, desiredToolsByServer, toolIndex)
        val promptResourceOutcome =
            filterPromptsAndResources(
                all = all,
                inScopeServers = inScopeServers,
                promptAllowListByServer = desiredPromptsByServer,
                resourceAllowListByServer = desiredResourcesByServer,
                preset = preset,
            )

        return FilterResult(
            capabilities =
                ServerCapabilities(
                    tools = toolOutcome.tools,
                    resources = promptResourceOutcome.resources,
                    prompts = promptResourceOutcome.prompts,
                ),
            allowedPrefixedTools = toolOutcome.allowedPrefixed,
            missingTools = toolOutcome.missing,
            promptServerByName = promptResourceOutcome.promptServer,
            resourceServerByUri = promptResourceOutcome.resourceServer,
        )
    }

    private fun filterAllEnabled(all: Map<String, ServerCapabilities>): FilterResult {
        val tools = mutableListOf<ToolDescriptor>()
        val prompts = mutableListOf<PromptDescriptor>()
        val resources = mutableListOf<ResourceDescriptor>()
        val allowedPrefixed = mutableSetOf<String>()
        val promptServer = mutableMapOf<String, String>()
        val resourceServer = mutableMapOf<String, String>()

        all.forEach { (serverId, caps) ->
            caps.tools.forEach { tool ->
                val prefixed = "${toFacadeNamespaceServerId(serverId)}_${tool.name}"
                tools += tool.copy(name = prefixed)
                allowedPrefixed += prefixed
            }
            prompts += caps.prompts
            resources += caps.resources
            caps.prompts.forEach { promptServer.putIfAbsent(it.name, serverId) }
            caps.resources.forEach { resource ->
                val key = resource.uri ?: resource.name
                resourceServer.putIfAbsent(key, serverId)
            }
        }

        return FilterResult(
            capabilities =
                ServerCapabilities(
                    tools = tools,
                    resources = resources,
                    prompts = prompts,
                ),
            allowedPrefixedTools = allowedPrefixed,
            missingTools = emptyList(),
            promptServerByName = promptServer.toMap(),
            resourceServerByUri = resourceServer.toMap(),
        )
    }

    private fun buildToolIndex(all: Map<String, ServerCapabilities>): Map<String, Set<String>> =
        all.mapValues { (_, caps) -> caps.tools.map { it.name }.toSet() }

    private fun filterTools(
        all: Map<String, ServerCapabilities>,
        desiredByServer: Map<String, List<ToolReference>>,
        toolIndex: Map<String, Set<String>>,
    ): ToolFilterOutcome {
        val allowedPrefixed = mutableSetOf<String>()
        val missing = mutableListOf<ToolReference>()
        val filteredTools = mutableListOf<ToolDescriptor>()

        desiredByServer.forEach { (serverId, refs) ->
            val caps = all[serverId]
            refs.forEach { ref ->
                val exists = toolIndex[serverId]?.contains(ref.toolName) == true
                if (!exists) {
                    missing += ref
                    logger.warn("Preset references missing tool '${ref.toolName}' on server '$serverId'")
                    return@forEach
                }
                val tool = caps?.tools?.firstOrNull { it.name == ref.toolName } ?: return@forEach
                val prefixed = "${toFacadeNamespaceServerId(serverId)}_${tool.name}"
                allowedPrefixed += prefixed
                filteredTools += tool.copy(name = prefixed)
            }
        }

        return ToolFilterOutcome(
            tools = filteredTools,
            allowedPrefixed = allowedPrefixed,
            missing = missing,
        )
    }

    private fun filterPromptsAndResources(
        all: Map<String, ServerCapabilities>,
        inScopeServers: Set<String>,
        promptAllowListByServer: Map<String, List<PromptReference>>,
        resourceAllowListByServer: Map<String, List<ResourceReference>>,
        preset: Preset,
    ): PromptResourceOutcome {
        val filteredResources = mutableListOf<ResourceDescriptor>()
        val filteredPrompts = mutableListOf<PromptDescriptor>()
        val promptServer = mutableMapOf<String, String>()
        val resourceServer = mutableMapOf<String, String>()
        val restrictPrompts = preset.prompts != null
        val restrictResources = preset.resources != null

        inScopeServers.forEach { serverId ->
            val caps = all[serverId] ?: return@forEach
            val promptAllowList = promptAllowListByServer[serverId]?.map { it.promptName }?.toSet().orEmpty()
            val resourceAllowList = resourceAllowListByServer[serverId]?.map { it.resourceKey }?.toSet().orEmpty()

            val promptsToInclude = selectPrompts(caps, restrictPrompts, promptAllowList)
            val resourcesToInclude = selectResources(caps, restrictResources, resourceAllowList)

            filteredPrompts += promptsToInclude
            filteredResources += resourcesToInclude

            promptsToInclude.forEach { p -> promptServer.putIfAbsent(p.name, serverId) }
            resourcesToInclude.forEach { r ->
                val key = r.uri ?: r.name
                resourceServer.putIfAbsent(key, serverId)
            }
        }

        return PromptResourceOutcome(
            prompts = filteredPrompts,
            resources = filteredResources,
            promptServer = promptServer.toMap(),
            resourceServer = resourceServer.toMap(),
        )
    }

    private fun selectPrompts(
        caps: ServerCapabilities,
        restrictPrompts: Boolean,
        allowList: Set<String>,
    ): List<PromptDescriptor> =
        if (!restrictPrompts) {
            caps.prompts
        } else if (allowList.isEmpty()) {
            emptyList()
        } else {
            caps.prompts.filter { it.name in allowList }
        }

    private fun selectResources(
        caps: ServerCapabilities,
        restrictResources: Boolean,
        allowList: Set<String>,
    ): List<ResourceDescriptor> =
        if (!restrictResources) {
            caps.resources
        } else if (allowList.isEmpty()) {
            emptyList()
        } else {
            caps.resources.filter { (it.uri ?: it.name) in allowList }
        }
}
