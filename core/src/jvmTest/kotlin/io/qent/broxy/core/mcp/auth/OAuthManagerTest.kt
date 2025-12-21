package io.qent.broxy.core.mcp.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.qent.broxy.core.config.ConfigTestLogger
import io.qent.broxy.core.models.AuthConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OAuthManagerTest {
    @Test
    fun ensureAuthorized_uses_scope_from_challenge_and_resource_param() {
        val tokenResponse =
            """{"access_token":"token123","token_type":"Bearer","expires_in":3600,"scope":"files:read"}"""
        val authMetadata =
            """
                |{
                |  "authorization_endpoint": "https://auth.example.com/authorize",
                |  "token_endpoint": "https://auth.example.com/token",
                |  "code_challenge_methods_supported": ["S256"],
                |  "client_id_metadata_document_supported": true
                |}
            """.trimMargin()
        val resourceMetadata =
            """
                |{"resource":"https://mcp.example.com","authorization_servers":["https://auth.example.com"],"scopes_supported":["files:read","files:write"]}
            """.trimMargin()
        val requests = mutableListOf<String>()
        val engine =
            MockEngine { request ->
                requests.add(request.url.toString())
                when (request.url.toString()) {
                    "https://mcp.example.com/.well-known/oauth-protected-resource" ->
                        respond(
                            content = ByteReadChannel(resourceMetadata),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                    "https://auth.example.com/.well-known/oauth-authorization-server" ->
                        respond(
                            content = ByteReadChannel(authMetadata),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                    "https://auth.example.com/token" -> {
                        val body = bodyAsText(request)
                        val params = parseFormBody(body)
                        assertEquals("authorization_code", params["grant_type"])
                        assertEquals("https://mcp.example.com", params["resource"])
                        respond(
                            content = ByteReadChannel(tokenResponse),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }

                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        val client = HttpClient(engine)
        val receiver = FakeAuthorizationCodeReceiver("http://localhost:3333/callback", "code123")
        val manager =
            OAuthManager(
                config = AuthConfig.OAuth(clientId = "client", redirectUri = receiver.redirectUri),
                state = OAuthState(),
                resourceUrl = "https://mcp.example.com/mcp",
                logger = ConfigTestLogger,
                httpClientFactory = { client },
                authorizationCodeReceiverFactory = { receiver },
                browserLauncher = CapturingBrowserLauncher(),
            )

        val challenge =
            OAuthChallenge(
                statusCode = 401,
                resourceMetadataUrl = "https://mcp.example.com/.well-known/oauth-protected-resource",
                scope = "files:read",
            )
        val result = runBlocking { manager.ensureAuthorized(challenge) }
        assertTrue(result.isSuccess)
        assertEquals("token123", result.getOrThrow())
        assertNotNull(receiver.lastAuthorizationUrl)
        assertTrue(receiver.lastAuthorizationUrl!!.contains("scope=files%3Aread"))
        assertTrue(receiver.lastAuthorizationUrl!!.contains("resource=https%3A%2F%2Fmcp.example.com"))
    }

    @Test
    fun ensureAuthorized_falls_back_to_root_well_known() {
        val tokenResponse =
            """{"access_token":"token123","token_type":"Bearer","expires_in":3600}"""
        val authMetadata =
            """
                |{
                |  "authorization_endpoint": "https://auth.example.com/authorize",
                |  "token_endpoint": "https://auth.example.com/token",
                |  "code_challenge_methods_supported": ["S256"]
                |}
            """.trimMargin()
        val resourceMetadata =
            """{"authorization_servers":["https://auth.example.com"]}"""
        val seen = mutableListOf<String>()
        val engine =
            MockEngine { request ->
                seen.add(request.url.toString())
                when (request.url.toString()) {
                    "https://mcp.example.com/.well-known/oauth-protected-resource/public/mcp" ->
                        respondError(HttpStatusCode.NotFound)

                    "https://mcp.example.com/.well-known/oauth-protected-resource" ->
                        respond(
                            content = ByteReadChannel(resourceMetadata),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                    "https://auth.example.com/.well-known/oauth-authorization-server" ->
                        respond(
                            content = ByteReadChannel(authMetadata),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                    "https://auth.example.com/token" ->
                        respond(
                            content = ByteReadChannel(tokenResponse),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        val client = HttpClient(engine)
        val receiver = FakeAuthorizationCodeReceiver("http://localhost:3333/callback", "code123")
        val manager =
            OAuthManager(
                config = AuthConfig.OAuth(clientId = "client", redirectUri = receiver.redirectUri),
                state = OAuthState(),
                resourceUrl = "https://mcp.example.com/public/mcp",
                logger = ConfigTestLogger,
                httpClientFactory = { client },
                authorizationCodeReceiverFactory = { receiver },
                browserLauncher = CapturingBrowserLauncher(),
            )

        val result = runBlocking { manager.ensureAuthorized() }
        assertTrue(result.isSuccess)
        val wellKnownCalls = seen.filter { it.contains(".well-known/oauth-protected-resource") }
        assertEquals(
            listOf(
                "https://mcp.example.com/.well-known/oauth-protected-resource/public/mcp",
                "https://mcp.example.com/.well-known/oauth-protected-resource",
            ),
            wellKnownCalls.take(2),
        )
    }

    @Test
    fun ensureAuthorized_refreshes_expired_token() {
        val tokenResponse =
            """{"access_token":"newtoken","token_type":"Bearer","expires_in":3600}"""
        val engine =
            MockEngine { request ->
                when (request.url.toString()) {
                    "https://auth.example.com/token" -> {
                        val body = bodyAsText(request)
                        val params = parseFormBody(body)
                        assertEquals("refresh_token", params["grant_type"])
                        assertEquals("refresh123", params["refresh_token"])
                        assertEquals("secret", params["client_secret"])
                        respond(
                            content = ByteReadChannel(tokenResponse),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }

                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        val client = HttpClient(engine)
        val state =
            OAuthState().apply {
                token =
                    OAuthToken(
                        accessToken = "old",
                        refreshToken = "refresh123",
                        expiresAtEpochMillis = 0,
                    )
                registration =
                    OAuthClientRegistration(
                        clientId = "client",
                        clientSecret = "secret",
                        tokenEndpointAuthMethod = "client_secret_post",
                    )
                authorizationMetadata =
                    AuthorizationServerMetadata(
                        authorizationEndpoint = "https://auth.example.com/authorize",
                        tokenEndpoint = "https://auth.example.com/token",
                        codeChallengeMethodsSupported = listOf("S256"),
                    )
            }
        val manager =
            OAuthManager(
                config = AuthConfig.OAuth(clientId = "client", redirectUri = "http://localhost:3333/callback"),
                state = state,
                resourceUrl = "https://mcp.example.com/mcp",
                logger = ConfigTestLogger,
                httpClientFactory = { client },
                authorizationCodeReceiverFactory = { FakeAuthorizationCodeReceiver("http://localhost:3333/callback", "code123") },
                browserLauncher = CapturingBrowserLauncher(),
            )

        val result = runBlocking { manager.ensureAuthorized() }
        assertTrue(result.isSuccess)
        assertEquals("newtoken", result.getOrThrow())
    }

    @Test
    fun ensureAuthorized_requires_pkce_support() {
        val authMetadata =
            """{"authorization_endpoint":"https://auth.example.com/authorize","token_endpoint":"https://auth.example.com/token"}"""
        val resourceMetadata =
            """{"authorization_servers":["https://auth.example.com"]}"""
        val engine =
            MockEngine { request ->
                when (request.url.toString()) {
                    "https://mcp.example.com/.well-known/oauth-protected-resource" ->
                        respond(
                            content = ByteReadChannel(resourceMetadata),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                    "https://auth.example.com/.well-known/oauth-authorization-server" ->
                        respond(
                            content = ByteReadChannel(authMetadata),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        val client = HttpClient(engine)
        val receiver = FakeAuthorizationCodeReceiver("http://localhost:3333/callback", "code123")
        val manager =
            OAuthManager(
                config = AuthConfig.OAuth(clientId = "client", redirectUri = receiver.redirectUri),
                state = OAuthState(),
                resourceUrl = "https://mcp.example.com/mcp",
                logger = ConfigTestLogger,
                httpClientFactory = { client },
                authorizationCodeReceiverFactory = { receiver },
                browserLauncher = CapturingBrowserLauncher(),
            )

        val result = runBlocking { manager.ensureAuthorized() }
        assertTrue(result.isFailure)
    }

    @Test
    fun ensureAuthorized_registers_dynamic_client_when_no_client_id() {
        val tokenResponse =
            """{"access_token":"newtoken","token_type":"Bearer","expires_in":3600}"""
        val authMetadata =
            """
                |{
                |  "authorization_endpoint": "https://auth.example.com/authorize",
                |  "token_endpoint": "https://auth.example.com/token",
                |  "registration_endpoint": "https://auth.example.com/register",
                |  "code_challenge_methods_supported": ["S256"],
                |  "token_endpoint_auth_methods_supported": ["none"]
                |}
            """.trimMargin()
        val resourceMetadata =
            """{"authorization_servers":["https://auth.example.com"]}"""
        val receiver = FakeAuthorizationCodeReceiver("http://localhost:3333/callback", "code123")
        val registeredClientId = "dynamic-123"
        var registrationSeen = false
        val engine =
            MockEngine { request ->
                when (request.url.toString()) {
                    "https://mcp.example.com/.well-known/oauth-protected-resource" ->
                        respond(
                            content = ByteReadChannel(resourceMetadata),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                    "https://auth.example.com/.well-known/oauth-authorization-server" ->
                        respond(
                            content = ByteReadChannel(authMetadata),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                    "https://auth.example.com/register" -> {
                        registrationSeen = true
                        val body = bodyAsText(request)
                        assertTrue(body.contains(receiver.redirectUri))
                        respond(
                            content =
                                ByteReadChannel(
                                    """{"client_id":"$registeredClientId","token_endpoint_auth_method":"none"}""",
                                ),
                            status = HttpStatusCode.Created,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }

                    "https://auth.example.com/token" -> {
                        val body = bodyAsText(request)
                        val params = parseFormBody(body)
                        assertEquals(registeredClientId, params["client_id"])
                        respond(
                            content = ByteReadChannel(tokenResponse),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }

                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        val client = HttpClient(engine)
        val manager =
            OAuthManager(
                config = AuthConfig.OAuth(redirectUri = receiver.redirectUri),
                state = OAuthState(),
                resourceUrl = "https://mcp.example.com/mcp",
                logger = ConfigTestLogger,
                httpClientFactory = { client },
                authorizationCodeReceiverFactory = { receiver },
                browserLauncher = CapturingBrowserLauncher(),
            )

        val result = runBlocking { manager.ensureAuthorized() }
        assertTrue(result.isSuccess)
        assertTrue(registrationSeen)
    }

    private class FakeAuthorizationCodeReceiver(
        override val redirectUri: String,
        private val code: String,
    ) : AuthorizationCodeReceiver {
        var lastAuthorizationUrl: String? = null

        override suspend fun awaitCode(
            authorizationUrl: String,
            expectedState: String,
            timeoutMillis: Long,
        ): Result<String> {
            lastAuthorizationUrl = authorizationUrl
            return Result.success(code)
        }

        override fun close() {}
    }

    private class CapturingBrowserLauncher : BrowserLauncher {
        var lastUrl: String? = null

        override fun open(url: String): Result<Unit> {
            lastUrl = url
            return Result.success(Unit)
        }
    }

    private fun parseFormBody(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        return body.split("&").associate { part ->
            val idx = part.indexOf('=')
            if (idx == -1) {
                part to ""
            } else {
                val key = java.net.URLDecoder.decode(part.substring(0, idx), "UTF-8")
                val value = java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                key to value
            }
        }
    }

    private fun bodyAsText(request: HttpRequestData): String {
        return when (val body = request.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            is TextContent -> body.text
            else -> ""
        }
    }
}
