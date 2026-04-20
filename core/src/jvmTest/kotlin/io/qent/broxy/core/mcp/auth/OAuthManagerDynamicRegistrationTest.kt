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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OAuthManagerDynamicRegistrationTest {
    @Test
    @Suppress("LongMethod")
    fun ensureAuthorized_dynamic_registration_infers_client_secret_post_when_server_omits_method() {
        val tokenResponse =
            """{"access_token":"newtoken","token_type":"Bearer","expires_in":3600}"""
        val authMetadata =
            """
                |{
                |  "authorization_endpoint": "https://auth.example.com/authorize",
                |  "token_endpoint": "https://auth.example.com/token",
                |  "registration_endpoint": "https://auth.example.com/register",
                |  "code_challenge_methods_supported": ["S256"],
                |  "token_endpoint_auth_methods_supported": ["client_secret_post"]
                |}
            """.trimMargin()
        val resourceMetadata =
            """{"authorization_servers":["https://auth.example.com"]}"""
        val receiver = FakeAuthorizationCodeReceiver("http://localhost:3333/callback", "code123")
        val registeredClientId = "dynamic-123"
        val registeredClientSecret = "dynamic-secret"
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
                        val body = bodyAsText(request)
                        val payload = Json.parseToJsonElement(body).jsonObject
                        assertEquals(null, payload["token_endpoint_auth_method"])
                        respond(
                            content =
                                ByteReadChannel(
                                    """{"client_id":"$registeredClientId","client_secret":"$registeredClientSecret"}""",
                                ),
                            status = HttpStatusCode.Created,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }

                    "https://auth.example.com/token" -> {
                        val body = bodyAsText(request)
                        val params = parseFormBody(body)
                        assertEquals(registeredClientId, params["client_id"])
                        assertEquals(registeredClientSecret, params["client_secret"])
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
                authorizationCodeReceiverFactory = { _, _ -> receiver },
                browserLauncher = CapturingBrowserLauncher(),
            )

        val result = runBlocking { manager.ensureAuthorized() }
        assertTrue(result.isSuccess)
    }

    @Test
    @Suppress("LongMethod")
    fun ensureAuthorized_dynamic_registration_prefers_registered_method_when_metadata_differs() {
        val tokenResponse =
            """{"access_token":"newtoken","token_type":"Bearer","expires_in":3600}"""
        val authMetadata =
            """
                |{
                |  "authorization_endpoint": "https://auth.example.com/authorize",
                |  "token_endpoint": "https://auth.example.com/token",
                |  "registration_endpoint": "https://auth.example.com/register",
                |  "code_challenge_methods_supported": ["S256"],
                |  "token_endpoint_auth_methods_supported": ["client_secret_post"]
                |}
            """.trimMargin()
        val resourceMetadata =
            """{"authorization_servers":["https://auth.example.com"]}"""
        val receiver = FakeAuthorizationCodeReceiver("http://localhost:3333/callback", "code123")
        val registeredClientId = "dynamic-123"
        val registeredClientSecret = "dynamic-secret"
        var tokenCalls = 0
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

                    "https://auth.example.com/register" ->
                        respond(
                            content =
                                ByteReadChannel(
                                    """
                                    |{"client_id":"$registeredClientId","client_secret":"$registeredClientSecret","token_endpoint_auth_method":"client_secret_basic"}
                                    """.trimMargin(),
                                ),
                            status = HttpStatusCode.Created,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )

                    "https://auth.example.com/token" -> {
                        tokenCalls += 1
                        val body = bodyAsText(request)
                        val params = parseFormBody(body)
                        assertEquals(registeredClientId, params["client_id"])
                        when (tokenCalls) {
                            1 -> {
                                assertEquals(null, params["client_secret"])
                                val authorization = request.headers[HttpHeaders.Authorization]
                                assertTrue(authorization?.startsWith("Basic ") == true)
                                respond(
                                    content =
                                        ByteReadChannel(
                                            """
                                            |{"error":"invalid_client","error_description":"unsupported authentication method"}
                                            """.trimMargin(),
                                        ),
                                    status = HttpStatusCode.Unauthorized,
                                    headers =
                                        headersOf(
                                            HttpHeaders.ContentType,
                                            ContentType.Application.Json.toString(),
                                        ),
                                )
                            }

                            else -> {
                                assertEquals(registeredClientSecret, params["client_secret"])
                                assertEquals(null, request.headers[HttpHeaders.Authorization])
                                respond(
                                    content = ByteReadChannel(tokenResponse),
                                    status = HttpStatusCode.OK,
                                    headers =
                                        headersOf(
                                            HttpHeaders.ContentType,
                                            ContentType.Application.Json.toString(),
                                        ),
                                )
                            }
                        }
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
                authorizationCodeReceiverFactory = { _, _ -> receiver },
                browserLauncher = CapturingBrowserLauncher(),
            )

        val result = runBlocking { manager.ensureAuthorized() }
        assertTrue(result.isSuccess)
        assertEquals(2, tokenCalls)
    }

    private class FakeAuthorizationCodeReceiver(
        override val redirectUri: String,
        private val code: String,
    ) : AuthorizationCodeReceiver {
        override suspend fun awaitCode(
            authorizationUrl: String,
            expectedState: String,
            timeoutMillis: Long,
        ): Result<String> = Result.success(code)

        override fun close() = Unit
    }

    private class CapturingBrowserLauncher : BrowserLauncher {
        override fun open(url: String): Result<Unit> = Result.success(Unit)
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

    private fun bodyAsText(request: HttpRequestData): String =
        when (val body = request.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            is TextContent -> body.text
            else -> ""
        }
}
