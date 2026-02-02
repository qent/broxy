package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig

internal class AuthMapping(
    private val envResolver: EnvironmentVariableResolver,
    private val errors: ConfigErrorHandler,
) {
    fun resolve(
        auth: AuthConfig?,
        serverId: String,
    ): AuthConfig? {
        if (auth == null) return null
        return when (auth) {
            is AuthConfig.OAuth ->
                auth.copy(
                    clientId = resolveAuthValue(auth.clientId, serverId, "auth.clientId"),
                    clientSecret = resolveAuthValue(auth.clientSecret, serverId, "auth.clientSecret"),
                    clientIdMetadataUrl =
                        resolveAuthValue(
                            auth.clientIdMetadataUrl,
                            serverId,
                            "auth.clientIdMetadataUrl",
                        ),
                    redirectUri = resolveAuthValue(auth.redirectUri, serverId, "auth.redirectUri"),
                    clientName = auth.clientName?.takeIf { it.isNotBlank() },
                    tokenEndpointAuthMethod = auth.tokenEndpointAuthMethod?.takeIf { it.isNotBlank() },
                    authorizationServer =
                        resolveAuthValue(
                            auth.authorizationServer,
                            serverId,
                            "auth.authorizationServer",
                        ),
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
            errors.fail("Server '$serverId': missing env vars for $label: ${missing.joinToString()}")
        }
        return envResolver.resolveString(trimmed)
    }
}
