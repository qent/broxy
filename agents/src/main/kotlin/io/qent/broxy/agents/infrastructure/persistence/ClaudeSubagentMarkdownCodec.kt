@file:Suppress(
    "TooManyFunctions",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "ThrowsCount",
    "MaxLineLength",
)

package io.qent.broxy.agents.infrastructure.persistence

import io.qent.broxy.agents.AgentDefinition
import io.qent.broxy.agents.AgentMcpServerReference
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConfigurationException
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.LinkedHashMap

data class ParsedClaudeSubagent(
    val name: String,
    val description: String,
    val body: String,
    val model: String?,
    val tools: List<String>?,
    val disallowedTools: List<String>?,
    val permissionMode: String?,
    val mcpServers: List<AgentMcpServerReference>?,
    val frontmatter: LinkedHashMap<String, Any?>,
)

class ClaudeSubagentMarkdownCodec {
    private val loaderOptions = LoaderOptions()
    private val yamlLoader = Yaml(SafeConstructor(loaderOptions))
    private val yamlDumper =
        Yaml(
            DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                isPrettyFlow = true
                indent = 2
                indicatorIndent = 1
                splitLines = false
            },
        )

    fun decode(
        text: String,
        fileName: String,
    ): ParsedClaudeSubagent {
        val normalized = text.replace("\r\n", "\n")
        val (frontmatterText, body) = splitFrontmatterAndBody(normalized, fileName)
        val parsed = parseFrontmatter(frontmatterText, fileName)
        val name = parsed["name"]?.asNonBlankString()
        val description = parsed["description"]?.asNonBlankString()
        if (name == null || description == null) {
            throw ConfigurationException(
                "Invalid subagent file '$fileName': frontmatter fields 'name' and 'description' are required",
            )
        }
        return ParsedClaudeSubagent(
            name = name,
            description = description,
            body = body.trim(),
            model = parsed["model"]?.asTrimmedString(),
            tools = parsed["tools"]?.asStringListOrNull(),
            disallowedTools = parsed["disallowedTools"]?.asStringListOrNull(),
            permissionMode = parsed["permissionMode"]?.asTrimmedString(),
            mcpServers = parseMcpServers(parsed["mcpServers"], fileName),
            frontmatter = parsed,
        )
    }

    fun encode(
        agent: AgentDefinition,
        existingFrontmatter: LinkedHashMap<String, Any?> = linkedMapOf(),
    ): String {
        val merged = buildFrontmatter(agent, existingFrontmatter)
        val dumped = yamlDumper.dump(merged).trimEnd().removeSuffix("...")
        val body = agent.systemPrompt.trim()
        return buildString {
            appendLine("---")
            appendLine(dumped)
            appendLine("---")
            append(body)
            if (body.isNotEmpty()) {
                appendLine()
            }
        }
    }

    fun decodeAgentDefinition(
        text: String,
        fileName: String,
        agentId: String,
        sidecar: AgentSidecarMetadata = AgentSidecarMetadata(),
    ): AgentDefinition {
        val parsed = decode(text = text, fileName = fileName)
        return parsed.toAgentDefinition(
            agentId = agentId,
            sidecar = sidecar,
        )
    }

    private fun splitFrontmatterAndBody(
        content: String,
        fileName: String,
    ): Pair<String, String> {
        val lines = content.split('\n')
        if (lines.isEmpty() || lines.first().trim() != "---") {
            throw ConfigurationException(
                "Invalid subagent file '$fileName': markdown frontmatter must start with ---",
            )
        }
        val closingIndex =
            lines
                .drop(1)
                .indexOfFirst { line -> line.trim() == "---" || line.trim() == "..." }
                .takeIf { it >= 0 }
                ?.plus(1)
                ?: throw ConfigurationException(
                    "Invalid subagent file '$fileName': frontmatter closing separator is missing",
                )
        val frontmatter = lines.subList(1, closingIndex).joinToString("\n")
        val body = lines.drop(closingIndex + 1).joinToString("\n")
        return frontmatter to body
    }

    private fun parseFrontmatter(
        frontmatter: String,
        fileName: String,
    ): LinkedHashMap<String, Any?> {
        val loadedAny =
            runCatching { yamlLoader.load(frontmatter) as Any? }
                .getOrElse { error ->
                    throw ConfigurationException("Invalid subagent file '$fileName': ${error.message}", error)
                }
        if (loadedAny == null) {
            return linkedMapOf()
        }
        val loadedMap = loadedAny as? Map<*, *>
        if (loadedMap == null) {
            throw ConfigurationException("Invalid subagent file '$fileName': frontmatter must be a YAML object")
        }
        val mapped = linkedMapOf<String, Any?>()
        loadedMap.entries.forEach { entry ->
            val key = entry.key
            val value = entry.value
            val name =
                key
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            if (name.isNotBlank()) {
                mapped[name] = value
            }
        }
        return mapped
    }

    private fun parseMcpServers(
        raw: Any?,
        fileName: String,
    ): List<AgentMcpServerReference>? {
        if (raw == null) {
            return null
        }
        val resolved = mutableListOf<AgentMcpServerReference>()
        when (raw) {
            is List<*> ->
                raw.forEach { item ->
                    resolved += parseMcpServerSequenceItem(item, fileName) ?: return@forEach
                }
            is Map<*, *> ->
                raw.forEach { (rawId, rawValue) ->
                    val id = rawId?.toString()?.trim().orEmpty()
                    if (id.isBlank()) {
                        return@forEach
                    }
                    val inline =
                        when (rawValue) {
                            null,
                            is Boolean,
                            is String,
                            is Number,
                            -> null
                            is Map<*, *> -> parseInlineServerConfig(id, rawValue, fileName)
                            else -> null
                        }
                    resolved += AgentMcpServerReference(id = id, inlineConfig = inline)
                }
            else ->
                throw ConfigurationException(
                    "Invalid subagent file '$fileName': mcpServers must be a YAML list or object",
                )
        }
        return resolved
            .asSequence()
            .map { it.copy(id = it.id.trim()) }
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .toList()
            .ifEmpty { null }
    }

    private fun parseMcpServerSequenceItem(
        raw: Any?,
        fileName: String,
    ): AgentMcpServerReference? =
        when (raw) {
            null -> null
            is String -> {
                val id = raw.trim()
                if (id.isBlank()) null else AgentMcpServerReference(id = id)
            }
            is Map<*, *> -> {
                val idField = raw["id"]?.toString()?.trim().orEmpty()
                if (idField.isNotBlank()) {
                    val inline = parseInlineServerConfig(idField, raw, fileName)
                    AgentMcpServerReference(id = idField, inlineConfig = inline)
                } else if (raw.size == 1) {
                    val (singleKey, singleValue) = raw.entries.first()
                    val id = singleKey?.toString()?.trim().orEmpty()
                    if (id.isBlank()) {
                        null
                    } else {
                        val inline =
                            if (singleValue is Map<*, *>) {
                                parseInlineServerConfig(id, singleValue, fileName)
                            } else {
                                null
                            }
                        AgentMcpServerReference(id = id, inlineConfig = inline)
                    }
                } else {
                    null
                }
            }
            else -> null
        }

    private fun parseInlineServerConfig(
        id: String,
        raw: Map<*, *>,
        fileName: String,
    ): McpServerConfig {
        val transportType =
            raw["type"]
                ?.toString()
                ?.trim()
                ?.ifBlank { null }
                ?: raw["transport"]
                    ?.toString()
                    ?.trim()
                    ?.ifBlank { null }
                ?: throw ConfigurationException(
                    "Invalid subagent file '$fileName': mcpServers.$id requires 'type' (or 'transport')",
                )
        val transport =
            when (transportType.lowercase()) {
                "stdio" -> {
                    val command =
                        raw["command"]?.toString()?.trim().orEmpty()
                    if (command.isBlank()) {
                        throw ConfigurationException(
                            "Invalid subagent file '$fileName': mcpServers.$id (stdio) requires 'command'",
                        )
                    }
                    TransportConfig.StdioTransport(
                        command = command,
                        args = raw["args"].asStringListOrNull().orEmpty(),
                    )
                }
                "http" ->
                    TransportConfig.StreamableHttpTransport(
                        url = requireUrl(raw, fileName, id),
                        headers = raw["headers"].asStringMapOrEmpty(),
                    )
                "sse" -> TransportConfig.HttpTransport(url = requireUrl(raw, fileName, id), headers = raw["headers"].asStringMapOrEmpty())
                "ws" ->
                    TransportConfig.WebSocketTransport(
                        url = requireUrl(raw, fileName, id),
                        headers = raw["headers"].asStringMapOrEmpty(),
                    )
                else ->
                    throw ConfigurationException(
                        "Invalid subagent file '$fileName': mcpServers.$id has unsupported type '$transportType'",
                    )
            }
        return McpServerConfig(
            id = id,
            name =
                raw["name"]
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                    .ifBlank { id },
            transport = transport,
            env = raw["env"].asStringMapOrEmpty(),
            enabled = raw["enabled"] as? Boolean ?: true,
            auth = null,
            iconPath = raw["iconPath"]?.toString()?.trim()?.ifBlank { null },
        )
    }

    private fun buildFrontmatter(
        agent: AgentDefinition,
        existing: LinkedHashMap<String, Any?>,
    ): LinkedHashMap<String, Any?> {
        val base = linkedMapOf<String, Any?>()
        base["name"] = agent.name.trim()
        base["description"] =
            agent.description
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: existing["description"]?.asTrimmedString()
                ?: "No description provided."

        upsertKnownFrontmatter(base, existing, "model", existing["model"]?.asTrimmedString())
        upsertKnownFrontmatter(base, existing, "tools", agent.claudeTools?.takeIf { it.isNotEmpty() })
        upsertKnownFrontmatter(base, existing, "disallowedTools", agent.claudeDisallowedTools?.takeIf { it.isNotEmpty() })
        upsertKnownFrontmatter(base, existing, "permissionMode", agent.claudePermissionMode?.trim()?.takeIf { it.isNotBlank() })
        val encodedMcpServers = encodeMcpServers(agent.claudeMcpServers)
        upsertKnownFrontmatter(base, existing, "mcpServers", encodedMcpServers)
        upsertKnownFrontmatter(base, existing, "maxTurns", existing["maxTurns"])
        upsertKnownFrontmatter(base, existing, "background", existing["background"])
        upsertKnownFrontmatter(base, existing, "isolation", existing["isolation"])
        upsertKnownFrontmatter(base, existing, "memory", existing["memory"])
        upsertKnownFrontmatter(base, existing, "hooks", existing["hooks"])
        upsertKnownFrontmatter(base, existing, "skills", existing["skills"])

        existing.forEach { (key, value) ->
            if (key !in KNOWN_FRONTMATTER_KEYS && key !in base) {
                base[key] = value
            }
        }
        return base
    }

    private fun upsertKnownFrontmatter(
        target: LinkedHashMap<String, Any?>,
        existing: LinkedHashMap<String, Any?>,
        key: String,
        explicit: Any?,
    ) {
        when {
            explicit != null -> target[key] = explicit
            existing.containsKey(key) -> target[key] = existing[key]
        }
    }

    private fun encodeMcpServers(mcpServers: List<AgentMcpServerReference>?): Any? {
        if (mcpServers.isNullOrEmpty()) {
            return null
        }
        val map = linkedMapOf<String, Any?>()
        mcpServers.forEach { entry ->
            val id = entry.id.trim()
            if (id.isBlank()) return@forEach
            val inline = entry.inlineConfig
            map[id] =
                if (inline == null) {
                    true
                } else {
                    linkedMapOf<String, Any?>().apply {
                        put("name", inline.name)
                        put("type", inline.transport.toClaudeTransportType())
                        when (val transport = inline.transport) {
                            is TransportConfig.StdioTransport -> {
                                put("command", transport.command)
                                if (transport.args.isNotEmpty()) {
                                    put("args", transport.args)
                                }
                            }
                            is TransportConfig.HttpTransport -> {
                                put("url", transport.url)
                                if (transport.headers.isNotEmpty()) {
                                    put("headers", transport.headers)
                                }
                            }
                            is TransportConfig.StreamableHttpTransport -> {
                                put("url", transport.url)
                                if (transport.headers.isNotEmpty()) {
                                    put("headers", transport.headers)
                                }
                            }
                            is TransportConfig.WebSocketTransport -> {
                                put("url", transport.url)
                                if (transport.headers.isNotEmpty()) {
                                    put("headers", transport.headers)
                                }
                            }
                        }
                        if (inline.env.isNotEmpty()) {
                            put("env", inline.env)
                        }
                        if (!inline.enabled) {
                            put("enabled", false)
                        }
                        inline.iconPath?.takeIf { it.isNotBlank() }?.let { put("iconPath", it) }
                    }
                }
        }
        return map.takeIf { it.isNotEmpty() }
    }

    private fun TransportConfig.toClaudeTransportType(): String =
        when (this) {
            is TransportConfig.StdioTransport -> "stdio"
            is TransportConfig.StreamableHttpTransport -> "http"
            is TransportConfig.HttpTransport -> "sse"
            is TransportConfig.WebSocketTransport -> "ws"
        }

    private fun requireUrl(
        raw: Map<*, *>,
        fileName: String,
        id: String,
    ): String {
        val url = raw["url"]?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            throw ConfigurationException("Invalid subagent file '$fileName': mcpServers.$id requires 'url'")
        }
        return url
    }

    private fun Any?.asTrimmedString(): String? = this?.toString()?.trim()?.takeIf { it.isNotBlank() }

    private fun Any?.asNonBlankString(): String? = asTrimmedString()

    private fun Any?.asStringListOrNull(): List<String>? =
        when (this) {
            null -> null
            is String -> listOf(this.trim()).filter { it.isNotBlank() }
            is List<*> ->
                this
                    .mapNotNull { it?.toString()?.trim() }
                    .filter { it.isNotBlank() }
                    .ifEmpty { null }
            else -> null
        }

    private fun Any?.asStringMapOrEmpty(): Map<String, String> =
        (this as? Map<*, *>)
            ?.mapNotNull { (key, value) ->
                val normalizedKey = key?.toString()?.trim().orEmpty()
                val normalizedValue = value?.toString()?.trim().orEmpty()
                if (normalizedKey.isBlank()) {
                    null
                } else {
                    normalizedKey to normalizedValue
                }
            }?.toMap()
            .orEmpty()

    private fun ParsedClaudeSubagent.toAgentDefinition(
        agentId: String,
        sidecar: AgentSidecarMetadata,
    ): AgentDefinition =
        AgentDefinition(
            id = agentId,
            name = name,
            systemPrompt = body,
            description = description,
            tools = sidecar.tools,
            agentTools = sidecar.agentTools,
            prompts = sidecar.prompts,
            resources = sidecar.resources,
            claudeTools = tools,
            claudeDisallowedTools = disallowedTools,
            claudePermissionMode = permissionMode,
            claudeMcpServers = mcpServers,
            orderIndex = sidecar.orderIndex,
            schedule = sidecar.schedule,
            manualLaunchDefaults = sidecar.manualLaunchDefaults,
        )

    private companion object {
        private val KNOWN_FRONTMATTER_KEYS =
            setOf(
                "name",
                "description",
                "model",
                "tools",
                "disallowedTools",
                "permissionMode",
                "mcpServers",
                "maxTurns",
                "background",
                "isolation",
                "memory",
                "hooks",
                "skills",
            )
    }
}
