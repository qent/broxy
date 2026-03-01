package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServersConfig

internal class RawSnapshotMerger(
    private val errors: ConfigErrorHandler,
) {
    fun mergeEnv(
        current: Map<String, String>,
        raw: RawServerSnapshot?,
    ): Map<String, String> {
        if (raw == null) return current
        val rawEnv = raw.rawEnv
        val resolvedRaw = raw.resolvedEnv
        return current.mapValues { (key, value) ->
            val rawValue = rawEnv[key]
            val resolvedValue = resolvedRaw[key]
            if (rawValue != null && resolvedValue == value) rawValue else value
        }
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
                clientIdMetadataUrl =
                    preserveRaw(
                        rawAuth.clientIdMetadataUrl,
                        resolvedRaw.clientIdMetadataUrl,
                        currentOauth.clientIdMetadataUrl,
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
                        rawAuth = rawServer.auth,
                        resolvedEnv = server.env,
                        resolvedAuth = server.auth,
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
