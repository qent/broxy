package io.qent.broxy.ui.adapter.clients.formats.json

import io.qent.broxy.ui.adapter.clients.common.BroxyServerEntry
import io.qent.broxy.ui.adapter.clients.common.BroxyServerStatus
import io.qent.broxy.ui.adapter.clients.common.McpServerListEntry
import io.qent.broxy.ui.adapter.clients.common.McpServerListFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class JsonMcpServerListFormat(
    private val serversKey: String,
    private val json: Json = Json { prettyPrint = true },
) : McpServerListFormat {
    override fun listServerEntries(content: String): List<McpServerListEntry> {
        val servers = readServers(readRoot(content))
        return servers.mapNotNull { (sourceServerId, value) ->
            val server = value as? JsonObject ?: return@mapNotNull null
            val normalizedId = sourceServerId.trim()
            if (normalizedId.isEmpty()) return@mapNotNull null
            val enabled =
                parseBoolean(server["enabled"])
                    ?: parseBoolean(server["disabled"])?.not()
            McpServerListEntry(
                sourceServerId = normalizedId,
                name = parseOptionalString(server["name"]),
                enabled = enabled,
                type = parseOptionalString(server["type"]),
                command = parseOptionalString(server["command"]),
                args = parseStringArray(server["args"]),
                url = firstNonBlankString(server["url"], server["serverUrl"], server["httpUrl"]),
                headers = parseStringMap(server["headers"]),
                env = parseStringMap(server["env"]),
            )
        }
    }

    override fun listServers(content: String): List<String> = listServerEntries(content).map { it.sourceServerId }

    override fun readBroxyStatus(
        content: String,
        serverName: String,
    ): BroxyServerStatus {
        val server = readServers(readRoot(content))[serverName] as? JsonObject ?: return BroxyServerStatus(isConfigured = false)
        val configuredUrl = (server["url"] as? JsonPrimitive)?.content
        return BroxyServerStatus(
            isConfigured = true,
            configuredUrl = configuredUrl,
        )
    }

    override fun upsertBroxy(
        content: String,
        serverName: String,
        entry: BroxyServerEntry,
    ): String {
        val jsonEntry =
            when (entry) {
                is BroxyServerEntry.JsonEntry -> entry.value
                else -> error("JSON format requires BroxyServerEntry.JsonEntry.")
            }
        val root = readRoot(content)
        val updated = updateBroxy(root, serverName, jsonEntry)
        return json.encodeToString(JsonObject.serializer(), updated)
    }

    override fun removeBroxy(
        content: String,
        serverName: String,
    ): String {
        val root = readRoot(content)
        val updated = updateBroxy(root, serverName, null)
        return json.encodeToString(JsonObject.serializer(), updated)
    }

    private fun readRoot(content: String): JsonObject {
        if (content.isBlank()) return JsonObject(emptyMap())
        val parsed = json.parseToJsonElement(content)
        return parsed as? JsonObject ?: error("Invalid mcp.json format: root is not an object.")
    }

    private fun readServers(root: JsonObject): JsonObject {
        val element = root[serversKey] ?: return JsonObject(emptyMap())
        return element as? JsonObject ?: error("Invalid mcp.json format: '$serversKey' must be an object.")
    }

    private fun updateBroxy(
        root: JsonObject,
        serverName: String,
        entry: JsonObject?,
    ): JsonObject {
        val existingServers = root[serversKey]
        val currentServers =
            when (existingServers) {
                null -> JsonObject(emptyMap())
                is JsonObject -> existingServers
                else -> error("Invalid mcp.json format: '$serversKey' must be an object.")
            }
        if (existingServers == null && entry == null) {
            return root
        }
        val updatedServers = updateServerMap(currentServers, serverName, entry)
        return replaceRootField(root, serversKey, updatedServers)
    }

    private fun updateServerMap(
        servers: JsonObject,
        serverName: String,
        entry: JsonObject?,
    ): JsonObject {
        val entries = LinkedHashMap<String, JsonElement>(servers.size + if (entry == null) 0 else 1)
        var sawServer = false
        for ((key, value) in servers) {
            if (key == serverName) {
                sawServer = true
                if (entry != null) {
                    entries[key] = entry
                }
            } else {
                entries[key] = value
            }
        }
        if (!sawServer && entry != null) {
            entries[serverName] = entry
        }
        return JsonObject(entries)
    }

    private fun replaceRootField(
        root: JsonObject,
        field: String,
        value: JsonElement,
    ): JsonObject {
        val entries = LinkedHashMap<String, JsonElement>(root.size + 1)
        var replaced = false
        for ((key, element) in root) {
            if (key == field) {
                entries[key] = value
                replaced = true
            } else {
                entries[key] = element
            }
        }
        if (!replaced) {
            entries[field] = value
        }
        return JsonObject(entries)
    }

    private fun parseStringArray(element: JsonElement?): List<String> =
        (element as? JsonArray)
            ?.mapNotNull { value ->
                parseOptionalString(value)
            }.orEmpty()

    private fun parseStringMap(element: JsonElement?): Map<String, String> {
        val obj = element as? JsonObject ?: return emptyMap()
        return obj
            .mapNotNull { (key, value) ->
                val normalizedKey = key.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val normalizedValue =
                    (value as? JsonPrimitive)
                        ?.content
                        ?.trim()
                        ?: return@mapNotNull null
                normalizedKey to normalizedValue
            }.toMap()
    }

    private fun parseOptionalString(element: JsonElement?): String? =
        (element as? JsonPrimitive)
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun parseBoolean(element: JsonElement?): Boolean? =
        (element as? JsonPrimitive)
            ?.content
            ?.trim()
            ?.lowercase()
            ?.let { normalized ->
                when (normalized) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }

    private fun firstNonBlankString(vararg elements: JsonElement?): String? {
        for (element in elements) {
            val parsed = parseOptionalString(element)
            if (parsed != null) return parsed
        }
        return null
    }
}
