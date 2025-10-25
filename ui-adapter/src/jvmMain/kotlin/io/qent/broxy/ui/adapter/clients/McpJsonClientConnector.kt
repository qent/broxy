package io.qent.broxy.ui.adapter.clients

import io.qent.broxy.ui.adapter.models.UiAiClientMissingConfigNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

open class McpJsonClientConnector(
    override val descriptor: AiClientDescriptor,
    private val baseDir: Path,
    private val configFileName: String = DEFAULT_CONFIG_FILE,
    private val serverName: String = DEFAULT_SERVER_NAME,
    private val serversKey: String = DEFAULT_SERVERS_KEY,
    private val requireConfigFile: Boolean = false,
    private val broxyEntryProvider: (AiClientConnectionRequest) -> JsonObject = { request ->
        defaultBroxyEntry(request.httpEndpoint)
    },
    private val json: Json = defaultJson(),
) : AiClientConnector {
    private val configPath: Path = baseDir.resolve(configFileName)

    override suspend fun loadStatus(request: AiClientConnectionRequest): Result<AiClientStatus> =
        withContext(Dispatchers.IO) {
            runCatching { loadStatusInternal() }
        }

    override suspend fun connect(request: AiClientConnectionRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConfigAvailable()
                val root = readRootOrEmpty(configPath)
                val updated = updateBroxy(root, broxyEntryProvider(request))
                if (updated != root || !configPath.exists()) {
                    writeRoot(configPath, updated)
                }
            }
        }

    override suspend fun disconnect(request: AiClientConnectionRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConfigAvailable()
                if (!configPath.exists()) return@runCatching
                val root = readRootOrEmpty(configPath)
                val updated = updateBroxy(root, null)
                if (updated != root) {
                    writeRoot(configPath, updated)
                }
            }
        }

    private fun loadStatusInternal(): AiClientStatus {
        if (!baseDir.isDirectory()) {
            return AiClientStatus(
                isConnected = false,
                canConnect = false,
                notice = missingConfigNotice(),
            )
        }
        if (!configPath.exists()) {
            if (requireConfigFile) {
                return AiClientStatus(
                    isConnected = false,
                    canConnect = false,
                    notice = missingConfigNotice(),
                )
            }
            return AiClientStatus(isConnected = false, canConnect = true)
        }
        val root = readRoot(configPath)
        val servers = readMcpServers(root)
        val isConnected = servers.containsKey(serverName)
        return AiClientStatus(isConnected = isConnected, canConnect = true)
    }

    private fun ensureConfigAvailable() {
        if (!baseDir.isDirectory()) {
            error(missingConfigMessage())
        }
        if (requireConfigFile && !configPath.exists()) {
            error(missingConfigMessage())
        }
    }

    private fun missingConfigMessage(): String = "Configuration for ${descriptor.name} was not found."

    private fun missingConfigNotice(): UiAiClientMissingConfigNotice = UiAiClientMissingConfigNotice(clientName = descriptor.name)

    private fun readRootOrEmpty(path: Path): JsonObject {
        if (!path.exists()) return JsonObject(emptyMap())
        return readRoot(path)
    }

    private fun readRoot(path: Path): JsonObject {
        val content = Files.readString(path)
        if (content.isBlank()) {
            return JsonObject(emptyMap())
        }
        val element = json.parseToJsonElement(content)
        return element as? JsonObject ?: error("Invalid mcp.json format: root is not an object.")
    }

    private fun readMcpServers(root: JsonObject): JsonObject {
        val element = root[serversKey] ?: return JsonObject(emptyMap())
        return element as? JsonObject
            ?: error("Invalid mcp.json format: '$serversKey' must be an object.")
    }

    private fun updateBroxy(
        root: JsonObject,
        entry: JsonObject?,
    ): JsonObject {
        val existingServers = root[serversKey]
        val currentServers =
            when (existingServers) {
                null -> JsonObject(emptyMap())
                is JsonObject -> existingServers
                else ->
                    error("Invalid mcp.json format: '$serversKey' must be an object.")
            }
        if (existingServers == null && entry == null) {
            return root
        }
        val updatedServers = updateServerMap(currentServers, entry)
        return replaceRootField(root, serversKey, updatedServers)
    }

    private fun updateServerMap(
        servers: JsonObject,
        entry: JsonObject?,
    ): JsonObject {
        val entries = LinkedHashMap<String, JsonElement>(servers.size + if (entry == null) 0 else 1)
        var sawBroxy = false
        for ((key, value) in servers) {
            if (key == serverName) {
                sawBroxy = true
                if (entry != null) {
                    entries[key] = entry
                }
            } else {
                entries[key] = value
            }
        }
        if (entry != null && !sawBroxy) {
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

    private fun writeRoot(
        path: Path,
        root: JsonObject,
    ) {
        val content = json.encodeToString(root)
        Files.writeString(path, content)
    }

    private companion object {
        private const val DEFAULT_CONFIG_FILE = "mcp.json"
        private const val DEFAULT_SERVER_NAME = "broxy"
        private const val DEFAULT_SERVERS_KEY = "mcpServers"

        private fun defaultBroxyEntry(url: String): JsonObject = JsonObject(mapOf("url" to JsonPrimitive(url)))

        private fun defaultJson(): Json =
            Json {
                prettyPrint = true
            }
    }
}
