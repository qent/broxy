package io.qent.broxy.core.mcp.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json

@Suppress("LongParameterList")
/**
 * Requires the caller to hold [OAuthState.mutex].
 */
internal suspend fun fetchProtectedResourceMetadataLocked(
    client: HttpClient,
    challenge: OAuthChallenge?,
    state: OAuthState,
    resourceUrl: String,
    logger: Logger,
    json: Json,
): ProtectedResourceMetadata? {
    val challengeMetadataUrl = challenge?.resourceMetadataUrl
    val cachedMetadataUrl = state.resourceMetadataUrl
    val urls =
        when {
            !challengeMetadataUrl.isNullOrBlank() -> listOf(challengeMetadataUrl)
            !cachedMetadataUrl.isNullOrBlank() -> listOf(cachedMetadataUrl)
            else -> buildProtectedResourceMetadataUrls(resourceUrl)
        }
    for (url in urls) {
        logger.debug("OAuth fetching protected resource metadata from $url")
        val response =
            client.get(url) {
                headers { append(HttpHeaders.Accept, ContentType.Application.Json.toString()) }
            }
        if (response.status.isSuccess()) {
            val body = response.bodyAsText()
            val metadata = json.decodeFromString(ProtectedResourceMetadata.serializer(), body)
            state.resourceMetadataUrl = url
            logger.debug("OAuth protected resource metadata loaded from $url")
            return metadata
        }
    }
    logger.debug("OAuth protected resource metadata not found for $resourceUrl")
    return null
}

internal suspend fun discoverAuthorizationServerMetadata(
    client: HttpClient,
    authorizationServers: List<String>,
    logger: Logger,
    json: Json,
): Pair<String, AuthorizationServerMetadata> {
    val failures = mutableListOf<String>()
    for (issuer in authorizationServers) {
        logger.debug("OAuth discovery probing authorization server $issuer")
        val metadata = probeAuthorizationServerMetadata(client, issuer, logger, json, failures)
        if (metadata != null) {
            return issuer to metadata
        }
    }
    error("Failed to discover OAuth authorization server metadata: ${failures.joinToString()}")
}

private suspend fun probeAuthorizationServerMetadata(
    client: HttpClient,
    issuer: String,
    logger: Logger,
    json: Json,
    failures: MutableList<String>,
): AuthorizationServerMetadata? {
    for (candidate in buildAuthorizationServerMetadataUrls(issuer)) {
        logger.debug("OAuth fetching authorization server metadata from $candidate")
        val response =
            client.get(candidate) {
                headers { append(HttpHeaders.Accept, ContentType.Application.Json.toString()) }
            }
        if (!response.status.isSuccess()) {
            failures.add("${response.status.value} $candidate")
        } else {
            val body = response.bodyAsText()
            val metadata = json.decodeFromString(AuthorizationServerMetadata.serializer(), body)
            val hasEndpoints =
                !metadata.authorizationEndpoint.isNullOrBlank() &&
                    !metadata.tokenEndpoint.isNullOrBlank()
            if (hasEndpoints) {
                logger.debug("OAuth authorization server metadata resolved from $candidate")
                return metadata
            }
            failures.add("missing endpoints $candidate")
        }
    }
    return null
}
