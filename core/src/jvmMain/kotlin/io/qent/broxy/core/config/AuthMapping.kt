package io.qent.broxy.core.config

import io.qent.broxy.core.models.AuthConfig

internal class AuthMapping(
    private val envResolver: EnvironmentVariableResolver,
    private val errors: ConfigErrorHandler,
) {
    fun resolve(
        auth: AuthConfig?,
        serverId: String,
        context: EnvironmentVariableResolver.ResolutionContext,
    ): AuthConfig? {
        if (auth == null) return null
        return when (auth) {
            is AuthConfig.OAuth ->
                auth.copy(
                    clientId = resolveAuthValue(auth.clientId, serverId, "oauth.clientId", context),
                    clientSecret = resolveAuthValue(auth.clientSecret, serverId, "oauth.clientSecret", context),
                    callbackPort = auth.callbackPort,
                    clientIdMetadataUrl =
                        resolveAuthValue(
                            auth.clientIdMetadataUrl,
                            serverId,
                            "oauth.clientIdMetadataUrl",
                            context,
                        ),
                    authServerMetadataUrl =
                        resolveAuthValue(
                            auth.authServerMetadataUrl,
                            serverId,
                            "oauth.authServerMetadataUrl",
                            context,
                        ),
                    redirectUri = resolveAuthValue(auth.redirectUri, serverId, "oauth.redirectUri", context),
                    clientName = auth.clientName?.takeIf { it.isNotBlank() },
                    tokenEndpointAuthMethod = auth.tokenEndpointAuthMethod?.takeIf { it.isNotBlank() },
                    authorizationServer =
                        resolveAuthValue(
                            auth.authorizationServer,
                            serverId,
                            "oauth.authorizationServer",
                            context,
                        ),
                    scopes = auth.scopes?.filter { it.isNotBlank() },
                )
        }
    }

    private fun resolveAuthValue(
        value: String?,
        serverId: String,
        label: String,
        context: EnvironmentVariableResolver.ResolutionContext,
    ): String? {
        val trimmed = value?.takeIf { it.isNotBlank() } ?: return null
        val missing = envResolver.missingVars(trimmed)
        if (missing.isNotEmpty()) {
            errors.fail("Server '$serverId': missing env vars for $label: ${missing.joinToString()}")
        }
        return envResolver.resolveString(trimmed, context)
    }
}
