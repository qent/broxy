package io.qent.broxy.core.config

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.Logger
import java.nio.file.Path

internal class ConfigMapper(
    private val envResolver: EnvironmentVariableResolver,
    private val logger: Logger,
    private val errors: ConfigErrorHandler,
    defaults: ConfigDefaults,
) {
    private val transportMapping = TransportMapping(errors)
    private val authMapping = AuthMapping(envResolver, errors)
    private val envFileLoader = EnvFileLoader(errors)
    private val defaultsApplier = DefaultsApplier(defaults)
    private val rawSnapshotMerger = RawSnapshotMerger(errors)

    data class MappedConfig(
        val config: McpServersConfig,
        val snapshot: RawConfigSnapshot,
    )

    fun mapFileToDomain(
        appConfig: FileAppConfig,
        mcpRoot: FileMcpRoot,
        mcpFileDirectory: Path,
        defaultMcpFilePath: String,
    ): MappedConfig {
        val servers = mutableListOf<McpServerConfig>()
        val snapshot = mutableMapOf<String, RawServerSnapshot>()
        mcpRoot.mcpServers.forEach { (id, fileServer) ->
            val mapped = mapFileServer(id, fileServer, mcpFileDirectory)
            servers += mapped.server
            snapshot[id] = mapped.snapshot
        }

        val config = defaultsApplier.apply(appConfig, servers, defaultMcpFilePath)
        return MappedConfig(config, RawConfigSnapshot(snapshot))
    }

    fun mapDomainToAppConfigFile(config: McpServersConfig): FileAppConfig =
        defaultsApplier.normalizeForSave(config).let { normalized ->
            FileAppConfig(
                mcpFilePath = normalized.mcpFilePath.takeIf { it.isNotBlank() },
                defaultPresetId = normalized.defaultPresetId?.takeIf { it.isNotBlank() },
                inboundHttpPort = normalized.inboundHttpPort,
                requestTimeoutSeconds = normalized.requestTimeoutSeconds,
                capabilitiesTimeoutSeconds = normalized.capabilitiesTimeoutSeconds,
                authorizationTimeoutSeconds = normalized.authorizationTimeoutSeconds,
                connectionRetryCount = normalized.connectionRetryCount,
                ignoreHttpsCertificateErrors = normalized.ignoreHttpsCertificateErrors,
                capabilitiesRefreshIntervalSeconds = normalized.capabilitiesRefreshIntervalSeconds,
                fallbackPromptsAndResourcesToTools = normalized.fallbackPromptsAndResourcesToTools,
                adapterMode = normalized.adapterMode,
            )
        }

    fun mapDomainToMcpFile(
        config: McpServersConfig,
        snapshot: RawConfigSnapshot?,
    ): FileMcpRoot =
        defaultsApplier.normalizeForSave(config).let { normalized ->
            FileMcpRoot(
                mcpServers =
                    normalized.servers.associate { server ->
                        server.id to mapServerToFile(server, snapshot)
                    },
            )
        }

    fun snapshotFromSave(
        config: McpServersConfig,
        root: FileMcpRoot,
    ): RawConfigSnapshot = rawSnapshotMerger.snapshotFromSave(config, root)

    private data class MappedServer(
        val server: McpServerConfig,
        val snapshot: RawServerSnapshot,
    )

    private data class ResolvedEnvData(
        val inlineRaw: Map<String, String>,
        val envFilePath: String?,
        val resolved: Map<String, String>,
    )

    private fun mapFileServer(
        id: String,
        fileServer: FileMcpServer,
        mcpFileDirectory: Path,
    ): MappedServer {
        val interpolationContext = EnvironmentVariableResolver.ResolutionContext(workspaceFolder = mcpFileDirectory)
        val name = fileServer.name ?: id
        val iconPath = fileServer.iconPath?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedTransportSource =
            resolveTransportPlaceholders(
                serverId = id,
                fileServer = fileServer,
                context = interpolationContext,
            )
        val transport = transportMapping.mapTransport(id, resolvedTransportSource)
        val envData =
            resolveServerEnv(
                serverId = id,
                fileServer = fileServer,
                transport = transport,
                mcpFileDirectory = mcpFileDirectory,
                context = interpolationContext,
            )
        val rawAuth = fileServer.oauth ?: fileServer.auth
        val authResolved = authMapping.resolve(rawAuth, id, interpolationContext)
        val server =
            McpServerConfig(
                id = id,
                name = name,
                transport = transport,
                env = envData.resolved,
                enabled = fileServer.enabled ?: true,
                auth = authResolved,
                envFile = envData.envFilePath?.takeIf { transport is TransportConfig.StdioTransport },
                iconPath = iconPath,
            )
        val snapshot =
            RawServerSnapshot(
                rawEnv = envData.inlineRaw,
                envFilePath = envData.envFilePath?.takeIf { transport is TransportConfig.StdioTransport },
                rawAuth = rawAuth,
                resolvedEnv = envData.resolved,
                resolvedAuth = authResolved,
                rawCommand = fileServer.command,
                rawArgs = fileServer.args,
                rawUrl = fileServer.url,
                rawHeaders = fileServer.headers,
                resolvedTransport = transport,
            )
        return MappedServer(server, snapshot)
    }

    private fun mapServerToFile(
        server: McpServerConfig,
        snapshot: RawConfigSnapshot?,
    ): FileMcpServer {
        val raw = snapshot?.servers?.get(server.id)
        val env = rawSnapshotMerger.mergeEnv(server.env, raw)
        val auth = rawSnapshotMerger.mergeAuth(server.auth, raw)
        val rawTransport = rawSnapshotMerger.mergeTransport(server.transport, raw)
        return transportMapping.mapToFile(server, env, auth, rawTransport)
    }

    private fun resolveServerEnv(
        serverId: String,
        fileServer: FileMcpServer,
        transport: TransportConfig,
        mcpFileDirectory: Path,
        context: EnvironmentVariableResolver.ResolutionContext,
    ): ResolvedEnvData {
        val envInlineRaw = fileServer.env ?: emptyMap()
        val envFilePath = fileServer.envFile?.trim()?.takeIf { it.isNotEmpty() }
        val envFromFileRaw =
            if (transport is TransportConfig.StdioTransport && envFilePath != null) {
                envFileLoader.load(
                    serverId = serverId,
                    envFileValue = envFilePath,
                    mcpFileDirectory = mcpFileDirectory,
                )
            } else {
                emptyMap()
            }
        val envRaw = envFromFileRaw + envInlineRaw
        envRaw.forEach { (_, value) ->
            val missing = envResolver.missingVars(value)
            if (missing.isNotEmpty()) {
                errors.fail("Server '$serverId': missing env vars: ${missing.joinToString()}")
            }
        }
        val resolved =
            try {
                envResolver.resolveMap(envRaw, context)
            } catch (ex: ConfigurationException) {
                logger.error("Server '$serverId': ${ex.message}")
                throw ex
            }
        envResolver.logResolvedEnv("Loaded server '$serverId'", resolved)
        return ResolvedEnvData(
            inlineRaw = envInlineRaw,
            envFilePath = envFilePath,
            resolved = resolved,
        )
    }

    private fun resolveTransportPlaceholders(
        serverId: String,
        fileServer: FileMcpServer,
        context: EnvironmentVariableResolver.ResolutionContext,
    ): FileMcpServer =
        fileServer.copy(
            command = fileServer.command?.let { resolveTransportValue(serverId, "command", it, context) },
            args =
                fileServer.args?.mapIndexed { index, value ->
                    resolveTransportValue(serverId, "args[$index]", value, context)
                },
            url = fileServer.url?.let { resolveTransportValue(serverId, "url", it, context) },
            headers =
                fileServer.headers?.mapValues { (key, value) ->
                    resolveTransportValue(serverId, "headers.$key", value, context)
                },
        )

    private fun resolveTransportValue(
        serverId: String,
        field: String,
        value: String,
        context: EnvironmentVariableResolver.ResolutionContext,
    ): String {
        if (!envResolver.hasPlaceholders(value)) return value
        return try {
            envResolver.resolveString(value, context)
        } catch (ex: ConfigurationException) {
            errors.fail("Server '$serverId': failed to resolve $field: ${ex.message}", ex)
        }
    }
}
