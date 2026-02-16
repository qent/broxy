package io.qent.broxy.core.config

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.Logger

internal class ConfigMapper(
    private val envResolver: EnvironmentVariableResolver,
    private val logger: Logger,
    private val errors: ConfigErrorHandler,
    defaults: ConfigDefaults,
) {
    private val transportMapping = TransportMapping(errors)
    private val authMapping = AuthMapping(envResolver, errors)
    private val defaultsApplier = DefaultsApplier(defaults)
    private val rawSnapshotMerger = RawSnapshotMerger(errors)

    data class MappedConfig(
        val config: McpServersConfig,
        val snapshot: RawConfigSnapshot,
    )

    fun mapFileToDomain(root: FileMcpRoot): MappedConfig {
        val servers = mutableListOf<McpServerConfig>()
        val snapshot = mutableMapOf<String, RawServerSnapshot>()
        root.mcpServers.forEach { (id, fileServer) ->
            val mapped = mapFileServer(id, fileServer)
            servers += mapped.server
            snapshot[id] = mapped.snapshot
        }

        val config = defaultsApplier.apply(root, servers)
        return MappedConfig(config, RawConfigSnapshot(snapshot))
    }

    fun mapDomainToFile(
        config: McpServersConfig,
        snapshot: RawConfigSnapshot?,
    ): FileMcpRoot =
        defaultsApplier.normalizeForSave(config).let { normalized ->
            FileMcpRoot(
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

    private fun mapFileServer(
        id: String,
        fileServer: FileMcpServer,
    ): MappedServer {
        val name = fileServer.name ?: id
        val iconPath = fileServer.iconPath?.trim()?.takeIf { it.isNotEmpty() }
        val transport = transportMapping.mapTransport(id, fileServer)
        val envRaw = fileServer.env ?: emptyMap()
        envRaw.forEach { (_, v) ->
            val missing = envResolver.missingVars(v)
            if (missing.isNotEmpty()) {
                errors.fail("Server '$id': missing env vars: ${missing.joinToString()}")
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
        val authResolved = authMapping.resolve(fileServer.auth, id)
        val server =
            McpServerConfig(
                id = id,
                name = name,
                transport = transport,
                env = envResolved,
                enabled = fileServer.enabled ?: true,
                auth = authResolved,
                iconPath = iconPath,
            )
        val snapshot =
            RawServerSnapshot(
                rawEnv = envRaw,
                rawAuth = fileServer.auth,
                resolvedEnv = envResolved,
                resolvedAuth = authResolved,
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
        return transportMapping.mapToFile(server, env, auth)
    }
}
