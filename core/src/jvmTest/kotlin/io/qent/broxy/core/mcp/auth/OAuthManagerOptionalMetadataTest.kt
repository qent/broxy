package io.qent.broxy.core.mcp.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.qent.broxy.core.config.ConfigTestLogger
import io.qent.broxy.core.models.AuthConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OAuthManagerOptionalMetadataTest {
    @Test
    fun ensureAuthorized_skips_when_metadata_has_empty_authorization_servers_without_challenge() {
        val resourceMetadata = """{"authorization_servers":[]}"""
        val requests = mutableListOf<String>()
        val engine =
            MockEngine { request ->
                requests += request.url.toString()
                when (request.url.toString()) {
                    "https://mcp.example.com/.well-known/oauth-protected-resource" ->
                        respond(
                            content = ByteReadChannel(resourceMetadata),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        val client = HttpClient(engine)
        val manager =
            OAuthManager(
                config = AuthConfig.OAuth(),
                state = OAuthState(),
                resourceUrl = "https://mcp.example.com",
                logger = ConfigTestLogger,
                httpClientFactory = { client },
                authorizationCodeReceiverFactory = { _, _ -> error("Authorization should not be requested") },
                browserLauncher = NoopBrowserLauncher(),
            )

        val result = runBlocking { manager.ensureAuthorized() }

        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrThrow())
        assertTrue(requests.any { it.contains(".well-known/oauth-protected-resource") })
        assertTrue(requests.none { it.contains(".well-known/oauth-authorization-server") })
        assertTrue(requests.none { it.endsWith("/token") })
    }

    @Test
    fun ensureAuthorized_fails_on_empty_authorization_servers_when_challenge_present() {
        val resourceMetadata = """{"authorization_servers":[]}"""
        val engine =
            MockEngine { request ->
                when (request.url.toString()) {
                    "https://mcp.example.com/.well-known/oauth-protected-resource" ->
                        respond(
                            content = ByteReadChannel(resourceMetadata),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        val client = HttpClient(engine)
        val manager =
            OAuthManager(
                config = AuthConfig.OAuth(),
                state = OAuthState(),
                resourceUrl = "https://mcp.example.com/mcp",
                logger = ConfigTestLogger,
                httpClientFactory = { client },
                authorizationCodeReceiverFactory = { _, _ -> error("Authorization should not be requested") },
                browserLauncher = NoopBrowserLauncher(),
            )

        val challenge =
            OAuthChallenge(
                statusCode = 401,
                resourceMetadataUrl = "https://mcp.example.com/.well-known/oauth-protected-resource",
            )
        val result = runBlocking { manager.ensureAuthorized(challenge) }

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("authorization server list is empty", ignoreCase = true) ==
                true,
        )
    }
}

private class NoopBrowserLauncher : BrowserLauncher {
    override fun open(url: String): Result<Unit> = Result.success(Unit)
}
