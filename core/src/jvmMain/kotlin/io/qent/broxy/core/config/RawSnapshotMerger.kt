package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig

internal class RawSnapshotMerger(
    private val errors: ConfigErrorHandler,
) {
    data class RawTransportFields(
        val command: String? = null,
        val args: List<String>? = null,
        val url: String? = null,
        val headers: Map<String, String>? = null,
    )

    fun mergeEnv(
        current: Map<String, String>,
        raw: RawServerSnapshot?,
    ): Map<String, String> {
        val snapshot = raw ?: return current
        val rawEnv = snapshot.rawEnv
        val resolvedRaw = snapshot.resolvedEnv
        val canonical =
            if (current == resolvedRaw) {
                when {
                    snapshot.envFilePath != null -> rawEnv
                    rawEnv.isEmpty() -> current
                    else -> rawEnv
                }
            } else {
                val envFileOnlyKeys = resolvedRaw.keys - rawEnv.keys
                val merged = linkedMapOf<String, String>()
                current.forEach { (key, value) ->
                    val resolvedValue = resolvedRaw[key]
                    if (key in envFileOnlyKeys && resolvedValue == value) {
                        return@forEach
                    }
                    val rawValue = rawEnv[key]
                    merged[key] = if (rawValue != null && resolvedValue == value) rawValue else value
                }
                merged
            }
        return canonical
    }

    fun mergeAuth(
        current: AuthConfig?,
        raw: RawServerSnapshot?,
    ): AuthConfig? {
        if (current == null) return null
        val currentOauth = current as? AuthConfig.OAuth
        val rawAuth = raw?.rawAuth as? AuthConfig.OAuth
        val resolvedRaw = raw?.resolvedAuth as? AuthConfig.OAuth
        return if (currentOauth == null || rawAuth == null || resolvedRaw == null) {
            current
        } else {
            val rawScopes = rawAuth.scopes?.filter { it.isNotBlank() }
            currentOauth.copy(
                clientId = preserveRaw(rawAuth.clientId, resolvedRaw.clientId, currentOauth.clientId),
                clientSecret = preserveRaw(rawAuth.clientSecret, resolvedRaw.clientSecret, currentOauth.clientSecret),
                callbackPort =
                    preserveRawInt(
                        rawAuth.callbackPort,
                        resolvedRaw.callbackPort,
                        currentOauth.callbackPort,
                    ),
                clientIdMetadataUrl =
                    preserveRaw(
                        rawAuth.clientIdMetadataUrl,
                        resolvedRaw.clientIdMetadataUrl,
                        currentOauth.clientIdMetadataUrl,
                    ),
                authServerMetadataUrl =
                    preserveRaw(
                        rawAuth.authServerMetadataUrl,
                        resolvedRaw.authServerMetadataUrl,
                        currentOauth.authServerMetadataUrl,
                    ),
                redirectUri = preserveRaw(rawAuth.redirectUri, resolvedRaw.redirectUri, currentOauth.redirectUri),
                clientName = preserveRaw(rawAuth.clientName, resolvedRaw.clientName, currentOauth.clientName),
                tokenEndpointAuthMethod =
                    preserveRaw(
                        rawAuth.tokenEndpointAuthMethod,
                        resolvedRaw.tokenEndpointAuthMethod,
                        currentOauth.tokenEndpointAuthMethod,
                    ),
                authorizationServer =
                    preserveRaw(
                        rawAuth.authorizationServer,
                        resolvedRaw.authorizationServer,
                        currentOauth.authorizationServer,
                    ),
                scopes = preserveRawList(rawScopes, resolvedRaw.scopes, currentOauth.scopes),
            )
        }
    }

    fun mergeTransport(
        current: TransportConfig,
        raw: RawServerSnapshot?,
    ): RawTransportFields? {
        val snapshot = raw ?: return null
        return when (current) {
            is TransportConfig.StdioTransport -> mergeStdioTransport(current, snapshot)
            is TransportConfig.HttpTransport -> mergeHttpTransport(current, snapshot)
            is TransportConfig.StreamableHttpTransport -> mergeStreamableHttpTransport(current, snapshot)
            is TransportConfig.WebSocketTransport -> mergeWebSocketTransport(current, snapshot)
        }
    }

    fun snapshotFromSave(
        config: McpServersConfig,
        root: FileMcpRoot,
    ): RawConfigSnapshot {
        val serverSnapshots =
            config.servers.associate { server ->
                val rawServer =
                    root.mcpServers[server.id]
                        ?: errors.fail("Missing raw config for server '${server.id}' after save")
                server.id to
                    RawServerSnapshot(
                        rawEnv = rawServer.env ?: emptyMap(),
                        envFilePath = rawServer.envFile,
                        rawAuth = rawServer.oauth,
                        resolvedEnv = server.env,
                        resolvedAuth = server.auth,
                        rawCommand = rawServer.command,
                        rawArgs = rawServer.args,
                        rawUrl = rawServer.url,
                        rawHeaders = rawServer.headers,
                        resolvedTransport = server.transport,
                    )
            }
        return RawConfigSnapshot(serverSnapshots)
    }
}

private fun preserveRaw(
    rawValue: String?,
    resolvedRaw: String?,
    current: String?,
): String? {
    val normalizedRaw = rawValue?.takeIf { it.isNotBlank() }
    return if (normalizedRaw != null && current == resolvedRaw) normalizedRaw else current
}

private fun preserveRawList(
    rawValue: List<String>?,
    resolvedRaw: List<String>?,
    current: List<String>?,
): List<String>? = if (rawValue != null && current == resolvedRaw) rawValue else current

private fun preserveRawInt(
    rawValue: Int?,
    resolvedRaw: Int?,
    current: Int?,
): Int? = if (rawValue != null && current == resolvedRaw) rawValue else current

private fun mergeRawMap(
    current: Map<String, String>,
    raw: Map<String, String>?,
    resolvedRaw: Map<String, String>,
): Map<String, String> {
    val rawMap = raw ?: return current
    return if (current == resolvedRaw) {
        rawMap
    } else {
        val merged = linkedMapOf<String, String>()
        current.forEach { (key, value) ->
            val rawValue = rawMap[key]
            val resolvedValue = resolvedRaw[key]
            merged[key] = if (rawValue != null && resolvedValue == value) rawValue else value
        }
        merged
    }
}

private fun mergeStdioTransport(
    current: TransportConfig.StdioTransport,
    snapshot: RawServerSnapshot,
): RawSnapshotMerger.RawTransportFields? =
    (snapshot.resolvedTransport as? TransportConfig.StdioTransport)?.let { resolved ->
        RawSnapshotMerger.RawTransportFields(
            command = preserveRaw(snapshot.rawCommand, resolved.command, current.command),
            args = preserveRawList(snapshot.rawArgs, resolved.args, current.args),
        )
    }

private fun mergeHttpTransport(
    current: TransportConfig.HttpTransport,
    snapshot: RawServerSnapshot,
): RawSnapshotMerger.RawTransportFields? =
    (snapshot.resolvedTransport as? TransportConfig.HttpTransport)?.let { resolved ->
        RawSnapshotMerger.RawTransportFields(
            url = preserveRaw(snapshot.rawUrl, resolved.url, current.url),
            headers = mergeRawMap(current.headers, snapshot.rawHeaders, resolved.headers),
        )
    }

private fun mergeStreamableHttpTransport(
    current: TransportConfig.StreamableHttpTransport,
    snapshot: RawServerSnapshot,
): RawSnapshotMerger.RawTransportFields? =
    (snapshot.resolvedTransport as? TransportConfig.StreamableHttpTransport)?.let { resolved ->
        RawSnapshotMerger.RawTransportFields(
            url = preserveRaw(snapshot.rawUrl, resolved.url, current.url),
            headers = mergeRawMap(current.headers, snapshot.rawHeaders, resolved.headers),
        )
    }

private fun mergeWebSocketTransport(
    current: TransportConfig.WebSocketTransport,
    snapshot: RawServerSnapshot,
): RawSnapshotMerger.RawTransportFields? =
    (snapshot.resolvedTransport as? TransportConfig.WebSocketTransport)?.let { resolved ->
        RawSnapshotMerger.RawTransportFields(
            url = preserveRaw(snapshot.rawUrl, resolved.url, current.url),
            headers = mergeRawMap(current.headers, snapshot.rawHeaders, resolved.headers),
        )
    }
