package io.qent.broxy.ui.adapter.icons

import io.qent.broxy.ui.adapter.catalog.CatalogServerEntry
import io.qent.broxy.ui.adapter.catalog.CatalogServerItem
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiServerIcon
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class MatchedCatalogServerMetadata(
    val iconUrl: String?,
    val websiteUrl: String?,
    val repositoryUrl: String?,
    val description: String?,
) {
    val externalUrl: String?
        get() = websiteUrl ?: repositoryUrl
}

object ServerIconResolver {
    private const val RULES_RESOURCE_PATH = "/server_icons.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val rules: List<CompiledRule> by lazy { loadRules() }

    fun resolve(
        config: UiMcpServerConfig,
        registryEntries: List<CatalogServerEntry> = emptyList(),
    ): UiServerIcon = resolve(config, registryIconUrls(registryEntries))

    fun resolve(
        config: UiMcpServerConfig,
        registryIconUrls: Map<String, String>,
    ): UiServerIcon {
        val customPath = config.iconPath?.trim()?.takeIf { it.isNotEmpty() }
        if (customPath != null) return UiServerIcon.Custom(customPath)
        return resolve(ServerIconInput.fromConfig(config), registryIconUrls)
    }

    fun resolve(
        draft: UiServerDraft,
        registryEntries: List<CatalogServerEntry> = emptyList(),
    ): UiServerIcon = resolve(draft, registryIconUrls(registryEntries))

    fun resolve(
        draft: UiServerDraft,
        registryIconUrls: Map<String, String>,
    ): UiServerIcon {
        val customPath = draft.iconPath?.trim()?.takeIf { it.isNotEmpty() }
        if (customPath != null) return UiServerIcon.Custom(customPath)
        return resolve(ServerIconInput.fromDraft(draft), registryIconUrls)
    }

    fun resolveMatchedMetadata(
        config: UiMcpServerConfig,
        registryMetadata: Map<String, MatchedCatalogServerMetadata>,
    ): MatchedCatalogServerMetadata? = resolveMatchedMetadata(ServerIconInput.fromConfig(config), registryMetadata)

    fun resolveMatchedMetadata(
        draft: UiServerDraft,
        registryMetadata: Map<String, MatchedCatalogServerMetadata>,
    ): MatchedCatalogServerMetadata? = resolveMatchedMetadata(ServerIconInput.fromDraft(draft), registryMetadata)

    fun registryMetadata(entries: List<CatalogServerEntry>): Map<String, MatchedCatalogServerMetadata> =
        buildRegistryMetadataMap(
            entries.asSequence().mapNotNull { entry ->
                val registryId = normalizeRegistryId(entry.detail.name) ?: return@mapNotNull null
                registryId to
                    MatchedCatalogServerMetadata(
                        iconUrl = entry.iconUrl.trimToNull(),
                        websiteUrl = entry.detail.websiteUrl.trimToNull(),
                        repositoryUrl =
                            entry.detail.repository
                                ?.url
                                .trimToNull(),
                        description = entry.detail.description.trimToNull(),
                    )
            },
        )

    fun registryMetadataFromItems(items: List<CatalogServerItem>): Map<String, MatchedCatalogServerMetadata> =
        buildRegistryMetadataMap(
            items.asSequence().mapNotNull { item ->
                val registryId = normalizeRegistryId(item.canonicalName) ?: normalizeRegistryId(item.id) ?: return@mapNotNull null
                registryId to
                    MatchedCatalogServerMetadata(
                        iconUrl = item.iconUrl.trimToNull(),
                        websiteUrl = item.websiteUrl.trimToNull(),
                        repositoryUrl = item.repositoryUrl.trimToNull(),
                        description = item.description.trimToNull(),
                    )
            },
        )

    fun registryIconUrls(entries: List<CatalogServerEntry>): Map<String, String> =
        buildRegistryIconUrlMap(
            registryMetadata(entries)
                .asSequence()
                .mapNotNull { (registryId, metadata) ->
                    val iconUrl = metadata.iconUrl ?: return@mapNotNull null
                    registryId to iconUrl
                },
        )

    fun registryIconUrlsFromMetadata(metadata: Map<String, MatchedCatalogServerMetadata>): Map<String, String> =
        buildRegistryIconUrlMap(
            metadata.asSequence().mapNotNull { (registryId, entryMetadata) ->
                val iconUrl = entryMetadata.iconUrl ?: return@mapNotNull null
                registryId to iconUrl
            },
        )

    fun registryIconUrlsFromItems(items: List<CatalogServerItem>): Map<String, String> =
        registryIconUrlsFromMetadata(registryMetadataFromItems(items))

    private fun resolve(
        input: ServerIconInput,
        registryIconUrls: Map<String, String>,
    ): UiServerIcon {
        val registryId = matchedRegistryId(input) ?: return UiServerIcon.Default
        val iconUrl = registryIconUrls[registryId] ?: return UiServerIcon.Default
        return UiServerIcon.Remote(iconUrl)
    }

    private fun resolveMatchedMetadata(
        input: ServerIconInput,
        registryMetadata: Map<String, MatchedCatalogServerMetadata>,
    ): MatchedCatalogServerMetadata? {
        val registryId = matchedRegistryId(input) ?: return null
        return registryMetadata[registryId]
    }

    private fun matchedRegistryId(input: ServerIconInput): String? = rules.firstOrNull { it.matches(input) }?.registryId

    private fun loadRules(): List<CompiledRule> {
        val raw =
            ServerIconRuleResourceMarker::class.java
                .getResourceAsStream(RULES_RESOURCE_PATH)
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
private data class ServerIconRuleSet(
    val rules: List<ServerIconRuleDefinition> = emptyList(),
)

@Serializable
private data class ServerIconRuleDefinition(
    val registryId: String? = null,
    // Backward compatibility for older rule files.
    val icon: String? = null,
    val allOf: List<ServerIconRuleCondition> = emptyList(),
) {
    fun compileOrNull(): CompiledRule? {
        val resolvedRegistryId =
            normalizeRegistryId(registryId ?: icon.orEmpty())
                ?: return null
        if (allOf.isEmpty()) return null
        val compiled =
            allOf.mapNotNull { condition ->
                val field = condition.field.trim()
                val pattern = condition.pattern.trim()
                if (field.isBlank() || pattern.isBlank()) return@mapNotNull null
                runCatching { CompiledCondition(field, Regex(pattern)) }.getOrNull()
            }
        if (compiled.size != allOf.size) return null
        return CompiledRule(resolvedRegistryId, compiled)
    }
}

@Serializable
private data class ServerIconRuleCondition(
    val field: String,
    val pattern: String,
)

private data class CompiledRule(
    val registryId: String,
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
                    is UiStdioTransport -> "stdio"
                    is UiHttpTransport -> "sse"
                    is UiStreamableHttpTransport -> "http"
                    is UiWebSocketTransport -> "ws"
                }
            val command =
                (config.transport as? UiStdioTransport)
                    ?.command
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val args =
                (config.transport as? UiStdioTransport)
                    ?.args
                    ?.mapNotNull { it.trim().takeIf { value -> value.isNotEmpty() } }
                    .orEmpty()
            val url =
                when (val cfg = config.transport) {
                    is UiHttpTransport -> cfg.url
                    is UiStreamableHttpTransport -> cfg.url
                    is UiWebSocketTransport -> cfg.url
                    is UiStdioTransport -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
            val headers =
                when (val cfg = config.transport) {
                    is UiHttpTransport -> cfg.headers
                    is UiStreamableHttpTransport -> cfg.headers
                    is UiWebSocketTransport -> cfg.headers
                    is UiStdioTransport -> emptyMap()
                }.mapNotNull { (key, value) ->
                    val trimmedKey = key.trim()
                    if (trimmedKey.isEmpty()) null else trimmedKey to value.trim()
                }.toMap()
            val env =
                config.env
                    .mapNotNull { (key, value) ->
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
                    is UiHttpDraft -> "sse"
                    is UiStreamableHttpDraft -> "http"
                    is UiWebSocketDraft -> "ws"
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
                draft.env
                    .mapNotNull { (key, value) ->
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

private fun buildRegistryIconUrlMap(pairs: Sequence<Pair<String, String>>): Map<String, String> {
    val resolved = linkedMapOf<String, String>()
    pairs.forEach { (registryId, iconUrl) ->
        resolved.putIfAbsent(registryId, iconUrl)
    }
    return resolved
}

private fun buildRegistryMetadataMap(
    pairs: Sequence<Pair<String, MatchedCatalogServerMetadata>>,
): Map<String, MatchedCatalogServerMetadata> {
    val resolved = linkedMapOf<String, MatchedCatalogServerMetadata>()
    pairs.forEach { (registryId, metadata) ->
        resolved.putIfAbsent(registryId, metadata)
    }
    return resolved
}

private fun String?.trimToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun normalizeRegistryId(value: String): String? =
    value
        .trim()
        .lowercase()
        .substringAfterLast('/')
        .takeIf { it.isNotEmpty() }
