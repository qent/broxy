package io.qent.broxy.core.mcp.auth

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import java.util.concurrent.atomic.AtomicReference

class OAuthChallengeRecorder {
    private val lastChallenge = AtomicReference<OAuthChallenge?>(null)

    fun record(response: HttpResponse) {
        val status = response.status.value
        if (status != 401 && status != 403) return
        val headerValues = response.headers.getAll(HttpHeaders.WWWAuthenticate).orEmpty()
        val bearer =
            headerValues.asSequence()
                .flatMap { parseWwwAuthenticateHeader(it).asSequence() }
                .firstOrNull { it.scheme.equals("Bearer", ignoreCase = true) }
        val params = bearer?.params?.mapKeys { it.key.lowercase() }.orEmpty()
        lastChallenge.set(
            OAuthChallenge(
                statusCode = status,
                resourceMetadataUrl = params["resource_metadata"],
                scope = params["scope"],
                error = params["error"],
                errorDescription = params["error_description"],
            ),
        )
    }

    fun consume(): OAuthChallenge? = lastChallenge.getAndSet(null)

    fun reset() {
        lastChallenge.set(null)
    }
}
