package io.qent.broxy.ui.adapter.icons

import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiServerIcon
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object ServerIconResolver {
    private const val RULES_RESOURCE_PATH = "/server_icons.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val rules: List<CompiledRule> by lazy { loadRules() }

    fun resolve(config: UiMcpServerConfig): UiServerIcon = resolve(ServerIconInput.fromConfig(config))

    fun resolve(draft: UiServerDraft): UiServerIcon = resolve(ServerIconInput.fromDraft(draft))

    private fun resolve(input: ServerIconInput): UiServerIcon {
        val match = rules.firstOrNull { it.matches(input) }
        return match?.let { UiServerIcon.Asset(it.icon) } ?: UiServerIcon.Default
    }

    private fun loadRules(): List<CompiledRule> {
        val raw =
            ServerIconRuleResourceMarker::class.java.getResourceAsStream(RULES_RESOURCE_PATH)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return emptyList()
        val ruleset =
            runCatching {
                json.decodeFromString(ServerIconRuleSet.serializer(), raw)
            }.getOrNull()
                ?: return emptyList()
        return ruleset.rules.mapNotNull { it.compileOrNull() }
    }
}

private object ServerIconRuleResourceMarker

@Serializable
private data class ServerIconRuleSet(val rules: List<ServerIconRuleDefinition> = emptyList())

@Serializable
private data class ServerIconRuleDefinition(
    val icon: String,
    val allOf: List<ServerIconRuleCondition> = emptyList(),
) {
    fun compileOrNull(): CompiledRule? {
        val iconId = icon.trim()
        if (iconId.isBlank()) return null
        if (allOf.isEmpty()) return null
        val compiled =
            allOf.mapNotNull { condition ->
                val field = condition.field.trim()
                val pattern = condition.pattern.trim()
                if (field.isBlank() || pattern.isBlank()) return@mapNotNull null
                runCatching { CompiledCondition(field, Regex(pattern)) }.getOrNull()
            }
        if (compiled.size != allOf.size) return null
        return CompiledRule(iconId, compiled)
    }
}

@Serializable
private data class ServerIconRuleCondition(
    val field: String,
    val pattern: String,
)

private data class CompiledRule(
    val icon: String,
    val conditions: List<CompiledCondition>,
) {
    fun matches(input: ServerIconInput): Boolean = conditions.all { it.matches(input) }
}

private data class CompiledCondition(
    val field: String,
    val regex: Regex,
) {
    fun matches(input: ServerIconInput): Boolean {
        val values = input.valuesFor(field)
        return values.isNotEmpty() && values.any { regex.containsMatchIn(it) }
    }
}

private data class ServerIconInput(
    val id: String,
    val name: String,
    val transport: String?,
    val command: String?,
    val args: List<String>,
    val url: String?,
    val headers: Map<String, String>,
    val env: Map<String, String>,
) {
    fun valuesFor(field: String): List<String> {
        val key = field.trim().lowercase()
        val values =
            when (key) {
                "id" -> listOf(id)
                "name" -> listOf(name)
                "transport" -> listOfNotNull(transport)
                "command" -> listOfNotNull(command)
                "args" -> args
                "url" -> listOfNotNull(url)
                "headers" -> headers.keys + headers.values
                "headers.key", "headers.keys" -> headers.keys.toList()
                "headers.value", "headers.values" -> headers.values.toList()
                "env" -> env.keys + env.values
                "env.key", "env.keys" -> env.keys.toList()
                "env.value", "env.values" -> env.values.toList()
                else -> emptyList()
            }
        return values.mapNotNull { value -> value.trim().takeIf { it.isNotEmpty() } }
    }

    companion object {
        fun fromConfig(config: UiMcpServerConfig): ServerIconInput {
            val transport =
                when (config.transport) {
                    is TransportConfig.StdioTransport -> "stdio"
                    is TransportConfig.HttpTransport -> "http"
                    is TransportConfig.StreamableHttpTransport -> "streamable-http"
                    is TransportConfig.WebSocketTransport -> "websocket"
                }
            val command =
                (config.transport as? TransportConfig.StdioTransport)
                    ?.command
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val args =
                (config.transport as? TransportConfig.StdioTransport)
                    ?.args
                    ?.mapNotNull { it.trim().takeIf { value -> value.isNotEmpty() } }
                    .orEmpty()
            val url =
                when (val cfg = config.transport) {
                    is TransportConfig.HttpTransport -> cfg.url
                    is TransportConfig.StreamableHttpTransport -> cfg.url
                    is TransportConfig.WebSocketTransport -> cfg.url
                    is TransportConfig.StdioTransport -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
            val headers =
                when (val cfg = config.transport) {
                    is TransportConfig.HttpTransport -> cfg.headers
                    is TransportConfig.StreamableHttpTransport -> cfg.headers
                    is TransportConfig.WebSocketTransport -> cfg.headers
                    is TransportConfig.StdioTransport -> emptyMap()
                }.mapNotNull { (key, value) ->
                    val trimmedKey = key.trim()
                    if (trimmedKey.isEmpty()) null else trimmedKey to value.trim()
                }.toMap()
            val env =
                config.env.mapNotNull { (key, value) ->
                    val trimmedKey = key.trim()
                    if (trimmedKey.isEmpty()) null else trimmedKey to value.trim()
                }.toMap()
            return ServerIconInput(
                id = config.id.trim(),
                name = config.name.trim(),
                transport = transport,
                command = command,
                args = args,
                url = url,
                headers = headers,
                env = env,
            )
        }

        fun fromDraft(draft: UiServerDraft): ServerIconInput {
            val transport =
                when (draft.transport) {
                    is UiStdioDraft -> "stdio"
                    is UiHttpDraft -> "http"
                    is UiStreamableHttpDraft -> "streamable-http"
                    is UiWebSocketDraft -> "websocket"
                }
            val command =
                (draft.transport as? UiStdioDraft)
                    ?.command
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val args =
                (draft.transport as? UiStdioDraft)
                    ?.args
                    ?.mapNotNull { it.trim().takeIf { value -> value.isNotEmpty() } }
                    .orEmpty()
            val url =
                when (val cfg = draft.transport) {
                    is UiHttpDraft -> cfg.url
                    is UiStreamableHttpDraft -> cfg.url
                    is UiWebSocketDraft -> cfg.url
                    is UiStdioDraft -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
            val headers =
                when (val cfg = draft.transport) {
                    is UiHttpDraft -> cfg.headers
                    is UiStreamableHttpDraft -> cfg.headers
                    is UiWebSocketDraft -> cfg.headers
                    is UiStdioDraft -> emptyMap()
                }.mapNotNull { (key, value) ->
                    val trimmedKey = key.trim()
                    if (trimmedKey.isEmpty()) null else trimmedKey to value.trim()
                }.toMap()
            val env =
                draft.env.mapNotNull { (key, value) ->
                    val trimmedKey = key.trim()
                    if (trimmedKey.isEmpty()) null else trimmedKey to value.trim()
                }.toMap()
            return ServerIconInput(
                id = draft.id.trim(),
                name = draft.name.trim(),
                transport = transport,
                command = command,
                args = args,
                url = url,
                headers = headers,
                env = env,
            )
        }
    }
}
