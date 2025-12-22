package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class JsonConfigurationRepository(
    baseDir: Path = Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        },
    private val logger: Logger = ConsoleLogger,
    private val envResolver: EnvironmentVariableResolver = EnvironmentVariableResolver(logger = logger),
) : ConfigurationRepository {
    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 60
        private const val DEFAULT_CAPABILITIES_TIMEOUT_SECONDS = 30
        private const val DEFAULT_CAPABILITIES_REFRESH_INTERVAL_SECONDS = 300
        private const val DEFAULT_CONNECTION_RETRY_COUNT = 3
        private const val DEFAULT_INBOUND_SSE_PORT = 3335
    }

    private val dir: Path = baseDir
    private val mcpFile: Path = dir.resolve("mcp.json")

    private fun fail(
        message: String,
        cause: Throwable? = null,
    ): Nothing {
        logger.error(message, cause)
        throw ConfigurationException(message)
    }

    override fun loadMcpConfig(): McpServersConfig {
        if (!mcpFile.exists()) {
            logger.warn("mcp.json not found at ${mcpFile.toAbsolutePath()}, using empty config")
            return McpServersConfig(emptyList())
        }
        val text =
            try {
                Files.readString(mcpFile)
            } catch (e: IOException) {
                fail("Failed to read mcp.json: ${e.message}", e)
            }
        val root =
            try {
                json.decodeFromString(FileMcpRoot.serializer(), text)
            } catch (e: Exception) {
                fail("Invalid mcp.json format: ${e.message}", e)
            }
        val servers =
            root.mcpServers.map { (id, e) ->
                val name = e.name ?: id
                val transportType = e.transport.lowercase()
                val transport: TransportConfig =
                    when (transportType) {
                        "stdio" -> {
                            val cmd =
                                e.command?.takeIf { it.isNotBlank() }
                                    ?: fail("Server '$id' (stdio): 'command' is required")
                            TransportConfig.StdioTransport(command = cmd, args = e.args ?: emptyList())
                        }

                        "http" -> {
                            val url =
                                e.url?.takeIf { it.isNotBlank() }
                                    ?: fail("Server '$id' (http): 'url' is required")
                            TransportConfig.HttpTransport(url = url, headers = e.headers ?: emptyMap())
                        }

                        "streamable-http", "streamhttp", "streamable" -> {
                            val url =
                                e.url?.takeIf { it.isNotBlank() }
                                    ?: fail("Server '$id' (streamable-http): 'url' is required")
                            TransportConfig.StreamableHttpTransport(url = url, headers = e.headers ?: emptyMap())
                        }

                        "ws", "websocket" -> {
                            val url =
                                e.url?.takeIf { it.isNotBlank() }
                                    ?: fail("Server '$id' (websocket): 'url' is required")
                            TransportConfig.WebSocketTransport(url = url, headers = e.headers ?: emptyMap())
                        }

                        else -> fail("Server '$id': unsupported transport '${e.transport}'")
                    }

                val envRaw: Map<String, String> = e.env ?: emptyMap()
                // Validate placeholders exist
                envRaw.forEach { (_, v) ->
                    val missing = envResolver.missingVars(v)
                    if (missing.isNotEmpty()) {
                        fail("Server '$id': missing env vars: ${missing.joinToString()}")
                    }
                }
                val envResolved =
                    try {
                        envResolver.resolveMap(envRaw)
                    } catch (ex: ConfigurationException) {
                        logger.error("Server '$id': ${ex.message}")
                        throw ex
                    }
                envResolver.logResolvedEnv("Loaded server '$id'", envResolved)
                val authResolved = resolveAuthConfig(e.auth, id)

                McpServerConfig(
                    id = id,
                    name = name,
                    transport = transport,
                    env = envResolved,
                    enabled = e.enabled ?: true,
                    auth = authResolved,
                )
            }

        validateServers(servers)

        val timeoutSeconds = root.requestTimeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
        val capabilitiesTimeoutSeconds = root.capabilitiesTimeoutSeconds ?: DEFAULT_CAPABILITIES_TIMEOUT_SECONDS
        val connectionRetryCount = root.connectionRetryCount ?: DEFAULT_CONNECTION_RETRY_COUNT
        val showTrayIcon = root.showTrayIcon ?: true
        val capabilitiesRefreshIntervalSeconds =
            root.capabilitiesRefreshIntervalSeconds
                ?: DEFAULT_CAPABILITIES_REFRESH_INTERVAL_SECONDS
        val inboundSsePort = root.inboundSsePort ?: DEFAULT_INBOUND_SSE_PORT

        return McpServersConfig(
            servers = servers,
            defaultPresetId = root.defaultPresetId?.takeIf { it.isNotBlank() },
            inboundSsePort = inboundSsePort.coerceIn(1, 65535),
            requestTimeoutSeconds = timeoutSeconds,
            capabilitiesTimeoutSeconds = capabilitiesTimeoutSeconds,
            connectionRetryCount = connectionRetryCount.coerceAtLeast(1),
            showTrayIcon = showTrayIcon,
            capabilitiesRefreshIntervalSeconds = capabilitiesRefreshIntervalSeconds,
        )
    }

    override fun saveMcpConfig(config: McpServersConfig) {
        val root =
            FileMcpRoot(
                defaultPresetId = config.defaultPresetId?.takeIf { it.isNotBlank() },
                inboundSsePort = config.inboundSsePort.coerceIn(1, 65535),
                requestTimeoutSeconds = config.requestTimeoutSeconds,
                capabilitiesTimeoutSeconds = config.capabilitiesTimeoutSeconds,
                connectionRetryCount = config.connectionRetryCount.coerceAtLeast(1),
                showTrayIcon = config.showTrayIcon,
                capabilitiesRefreshIntervalSeconds = config.capabilitiesRefreshIntervalSeconds,
                mcpServers =
                    config.servers.associate { s ->
                        s.id to
                            when (val t = s.transport) {
                                is TransportConfig.StdioTransport ->
                                    FileMcpServer(
                                        name = s.name,
                                        enabled = s.enabled,
                                        transport = "stdio",
                                        command = t.command,
                                        args = t.args,
                                        env = s.env,
                                        auth = s.auth,
                                    )

                                is TransportConfig.HttpTransport ->
                                    FileMcpServer(
                                        name = s.name,
                                        enabled = s.enabled,
                                        transport = "http",
                                        url = t.url,
                                        headers = t.headers,
                                        env = s.env,
                                        auth = s.auth,
                                    )

                                is TransportConfig.StreamableHttpTransport ->
                                    FileMcpServer(
                                        name = s.name,
                                        enabled = s.enabled,
                                        transport = "streamable-http",
                                        url = t.url,
                                        headers = t.headers,
                                        env = s.env,
                                        auth = s.auth,
                                    )

                                is TransportConfig.WebSocketTransport ->
                                    FileMcpServer(
                                        name = s.name,
                                        enabled = s.enabled,
                                        transport = "websocket",
                                        url = t.url,
                                        headers = t.headers,
                                        env = s.env,
                                        auth = s.auth,
                                    )
                            }
                    },
            )
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir)
            Files.writeString(
                mcpFile,
                json.encodeToString(FileMcpRoot.serializer(), root),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            logger.info("Saved ${config.servers.size} MCP servers to ${mcpFile.toAbsolutePath()}")
        } catch (e: IOException) {
            fail("Failed to save mcp.json: ${e.message}", e)
        }
    }

    override fun loadPreset(id: String): Preset {
        val file = dir.resolve("preset_$id.json")
        if (!file.exists() || !file.isRegularFile()) throw ConfigurationException("Preset '$id' not found at ${file.toAbsolutePath()}")
        val text =
            try {
                Files.readString(file)
            } catch (e: IOException) {
                throw ConfigurationException("Failed to read ${file.name}: ${e.message}")
            }
        val preset =
            try {
                json.decodeFromString(Preset.serializer(), text)
            } catch (e: Exception) {
                throw ConfigurationException("Invalid preset '${file.name}': ${e.message}")
            }
        if (preset.id != id) throw ConfigurationException("Preset file '${file.name}' id '${preset.id}' does not match requested id '$id'")
        return preset
    }

    override fun savePreset(preset: Preset) {
        val file = dir.resolve("preset_${preset.id}.json")
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir)
            Files.writeString(
                file,
                json.encodeToString(Preset.serializer(), preset),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            logger.info("Saved preset '${preset.id}' to ${file.toAbsolutePath()}")
        } catch (e: IOException) {
            fail("Failed to save preset '${preset.id}': ${e.message}", e)
        }
    }

    override fun listPresets(): List<Preset> {
        if (!Files.exists(dir)) return emptyList()
        val result = mutableListOf<Preset>()
        Files.newDirectoryStream(dir) { p ->
            val n = p.fileName.toString()
            n.startsWith("preset_") && n.endsWith(".json")
        }.use { ds ->
            for (p in ds) {
                val preset =
                    runCatching { json.decodeFromString(Preset.serializer(), Files.readString(p)) }
                        .getOrElse {
                            logger.warn("Failed to load preset file '${p.fileName}': ${it.message}")
                            null
                        }
                if (preset != null) result.add(preset)
            }
        }
        return result
    }

    override fun deletePreset(id: String) {
        val file = dir.resolve("preset_$id.json")
        try {
            Files.deleteIfExists(file)
        } catch (e: IOException) {
            throw ConfigurationException("Failed to delete preset '$id': ${e.message}")
        }
    }

    private fun validateServers(servers: List<McpServerConfig>) {
        if (servers.isEmpty()) return
        val ids = servers.map { it.id }
        val dup = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) fail("Duplicate server IDs: ${dup.joinToString()}")
        servers.forEach { s ->
            if (s.id.isBlank()) fail("Server id cannot be blank")
            if (s.name.isBlank()) fail("Server '${s.id}': name cannot be blank")
            when (val t = s.transport) {
                is TransportConfig.StdioTransport -> if (t.command.isBlank()) fail("Server '${s.id}': stdio.command cannot be blank")
                is TransportConfig.HttpTransport -> if (t.url.isBlank()) fail("Server '${s.id}': http.url cannot be blank")
                is TransportConfig.StreamableHttpTransport ->
                    if (t.url.isBlank()) {
                        fail(
                            "Server '${s.id}': streamable-http.url cannot be blank",
                        )
                    }
                is TransportConfig.WebSocketTransport -> if (t.url.isBlank()) fail("Server '${s.id}': ws.url cannot be blank")
            }
            if (s.auth != null) {
                if (s.transport is TransportConfig.StdioTransport) {
                    fail("Server '${s.id}': auth is not supported for stdio transport")
                }
                validateAuthConfig(s.id, s.auth)
            }
        }
    }

    private fun resolveAuthConfig(
        auth: AuthConfig?,
        serverId: String,
    ): AuthConfig? {
        if (auth == null) return null
        return when (auth) {
            is AuthConfig.OAuth ->
                auth.copy(
                    clientId = resolveAuthValue(auth.clientId, serverId, "auth.clientId"),
                    clientSecret = resolveAuthValue(auth.clientSecret, serverId, "auth.clientSecret"),
                    clientIdMetadataUrl = resolveAuthValue(auth.clientIdMetadataUrl, serverId, "auth.clientIdMetadataUrl"),
                    redirectUri = resolveAuthValue(auth.redirectUri, serverId, "auth.redirectUri"),
                    clientName = auth.clientName?.takeIf { it.isNotBlank() },
                    tokenEndpointAuthMethod = auth.tokenEndpointAuthMethod?.takeIf { it.isNotBlank() },
                    authorizationServer = resolveAuthValue(auth.authorizationServer, serverId, "auth.authorizationServer"),
                    scopes = auth.scopes?.filter { it.isNotBlank() },
                )
        }
    }

    private fun resolveAuthValue(
        value: String?,
        serverId: String,
        label: String,
    ): String? {
        val trimmed = value?.takeIf { it.isNotBlank() } ?: return null
        val missing = envResolver.missingVars(trimmed)
        if (missing.isNotEmpty()) {
            fail("Server '$serverId': missing env vars for $label: ${missing.joinToString()}")
        }
        return envResolver.resolveString(trimmed)
    }

    private fun validateAuthConfig(
        serverId: String,
        auth: AuthConfig,
    ) {
        when (auth) {
            is AuthConfig.OAuth -> validateOAuthConfig(serverId, auth)
        }
    }

    private fun validateOAuthConfig(
        serverId: String,
        auth: AuthConfig.OAuth,
    ) {
        auth.redirectUri?.let {
            val uri = parseUri(serverId, "auth.redirectUri", it)
            val scheme = uri.scheme?.lowercase() ?: fail("Server '$serverId': auth.redirectUri missing scheme")
            val host = uri.host ?: fail("Server '$serverId': auth.redirectUri missing host")
            val isLoopback = host == "localhost" || host == "127.0.0.1"
            if (scheme != "http" || !isLoopback) {
                fail("Server '$serverId': auth.redirectUri must use http://localhost or http://127.0.0.1")
            }
            if (uri.port == -1) {
                fail("Server '$serverId': auth.redirectUri must include an explicit port")
            }
        }
        auth.clientIdMetadataUrl?.let {
            val uri = parseUri(serverId, "auth.clientIdMetadataUrl", it)
            val scheme = uri.scheme?.lowercase() ?: fail("Server '$serverId': auth.clientIdMetadataUrl missing scheme")
            if (scheme != "https") {
                fail("Server '$serverId': auth.clientIdMetadataUrl must use https")
            }
            if (uri.path.isNullOrBlank() || uri.path == "/") {
                fail("Server '$serverId': auth.clientIdMetadataUrl must include a path")
            }
        }
        auth.authorizationServer?.let {
            val uri = parseUri(serverId, "auth.authorizationServer", it)
            val scheme = uri.scheme?.lowercase() ?: fail("Server '$serverId': auth.authorizationServer missing scheme")
            val host = uri.host ?: fail("Server '$serverId': auth.authorizationServer missing host")
            val isLoopback = host == "localhost" || host == "127.0.0.1"
            if (scheme != "https" && !(scheme == "http" && isLoopback)) {
                fail("Server '$serverId': auth.authorizationServer must use https or localhost http")
            }
        }
        auth.tokenEndpointAuthMethod?.let {
            val normalized = it.lowercase()
            val allowed = setOf("none", "client_secret_basic", "client_secret_post")
            if (normalized !in allowed) {
                fail("Server '$serverId': auth.tokenEndpointAuthMethod must be one of ${allowed.joinToString()}")
            }
        }
    }

    private fun parseUri(
        serverId: String,
        label: String,
        value: String,
    ): URI {
        return try {
            URI(value)
        } catch (e: Exception) {
            fail("Server '$serverId': $label is not a valid URI")
        }
    }

    @Serializable
    private data class FileMcpRoot(
        val defaultPresetId: String? = null,
        val inboundSsePort: Int? = null,
        val requestTimeoutSeconds: Int? = null,
        val capabilitiesTimeoutSeconds: Int? = null,
        val connectionRetryCount: Int? = null,
        val showTrayIcon: Boolean? = null,
        val capabilitiesRefreshIntervalSeconds: Int? = null,
        val mcpServers: Map<String, FileMcpServer>,
    )

    @Serializable
    private data class FileMcpServer(
        val name: String? = null,
        val enabled: Boolean? = null,
        val transport: String,
        val command: String? = null,
        val args: List<String>? = null,
        val url: String? = null,
        val headers: Map<String, String>? = null,
        val env: Map<String, String>? = null,
        val auth: AuthConfig? = null,
    )
}
