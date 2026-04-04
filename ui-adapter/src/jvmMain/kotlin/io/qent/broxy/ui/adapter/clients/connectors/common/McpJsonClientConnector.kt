package io.qent.broxy.ui.adapter.clients

import io.qent.broxy.ui.adapter.clients.common.BroxyServerEntry
import io.qent.broxy.ui.adapter.clients.common.DEFAULT_BROXY_SERVER_NAME
import io.qent.broxy.ui.adapter.clients.formats.json.JsonMcpServerListFormat
import io.qent.broxy.ui.adapter.models.UiAiClientMissingConfigNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
    private val serverName: String = DEFAULT_BROXY_SERVER_NAME,
    private val serversKey: String = DEFAULT_SERVERS_KEY,
    private val requireConfigFile: Boolean = false,
    private val broxyEntryProvider: (AiClientConnectionRequest) -> JsonObject = { request ->
        defaultBroxyEntry(request.httpEndpoint)
    },
    private val json: Json = defaultJson(),
) : AiClientConnector {
    private val configPath: Path = baseDir.resolve(configFileName)
    private val format = JsonMcpServerListFormat(serversKey = serversKey, json = json)

    override suspend fun loadStatus(request: AiClientConnectionRequest): Result<AiClientStatus> =
        withContext(Dispatchers.IO) {
            runCatching { loadStatusInternal() }
        }

    override suspend fun loadImportableServers(): Result<List<AiClientImportServer>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!baseDir.isDirectory()) return@runCatching emptyList()
                if (!configPath.exists()) return@runCatching emptyList()
                val content = readContentOrEmpty(configPath)
                format
                    .listServerEntries(content)
                    .mapNotNull { it.toImportServerOrNull() }
            }
        }

    override suspend fun connect(request: AiClientConnectionRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConfigAvailable()
                val content = readContentOrEmpty(configPath)
                val updated =
                    format.upsertBroxy(
                        content = content,
                        serverName = serverName,
                        entry = BroxyServerEntry.JsonEntry(broxyEntryProvider(request)),
                    )
                if (updated != content || !configPath.exists()) {
                    writeContent(configPath, updated)
                }
            }
        }

    override suspend fun disconnect(request: AiClientConnectionRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConfigAvailable()
                if (!configPath.exists()) return@runCatching
                val content = readContentOrEmpty(configPath)
                val updated = format.removeBroxy(content = content, serverName = serverName)
                if (updated != content) {
                    writeContent(configPath, updated)
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
        val content = readContentOrEmpty(configPath)
        val status = format.readBroxyStatus(content = content, serverName = serverName)
        val isConnected = status.isConfigured
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

    private fun readContentOrEmpty(path: Path): String = if (!path.exists()) "" else Files.readString(path)

    private fun writeContent(
        path: Path,
        content: String,
    ) {
        Files.writeString(path, content)
    }

    private companion object {
        private const val DEFAULT_CONFIG_FILE = "mcp.json"
        private const val DEFAULT_SERVERS_KEY = "mcpServers"

        private fun defaultBroxyEntry(url: String): JsonObject = JsonObject(mapOf("url" to JsonPrimitive(url)))

        private fun defaultJson(): Json =
            Json {
                prettyPrint = true
            }
    }
}
