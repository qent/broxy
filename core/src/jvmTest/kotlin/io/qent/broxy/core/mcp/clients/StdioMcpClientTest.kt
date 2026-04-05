package io.qent.broxy.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.mcp.auth.AuthorizationCompletionPageContext
import io.qent.broxy.core.mcp.auth.AuthorizationPopupSessionRegistry
import io.qent.broxy.core.mcp.auth.AuthorizationPresenter
import io.qent.broxy.core.mcp.auth.AuthorizationPresenterRegistry
import io.qent.broxy.core.mcp.auth.AuthorizationRequest
import io.qent.broxy.core.mcp.auth.AuthorizationResult
import io.qent.broxy.core.mcp.auth.BrowserLauncher
import io.qent.broxy.core.models.AuthConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StdioMcpClientTest {
    @Test
    fun connect_and_capabilities_and_callTool_with_mockito() {
        runBlocking {
            val facade: SdkClientFacade = mock()
            whenever(facade.getTools()).thenReturn(listOf(ToolDescriptor("echo", "Echo tool")))
            whenever(facade.getResources()).thenReturn(listOf(ResourceDescriptor("res1", "uri://res1", "R1")))
            whenever(facade.getPrompts()).thenReturn(listOf(PromptDescriptor("p1", "Prompt 1")))
            whenever(facade.callTool(any(), any())).thenReturn(
                CallToolResult(
                    content = emptyList(),
                    structuredContent = buildJsonObject { put("ok", true) },
                    isError = false,
                    meta = JsonObject(emptyMap()),
                ),
            )

            val client =
                StdioMcpClient(
                    serverId = "test",
                    command = "noop",
                    args = emptyList(),
                    env = emptyMap(),
                    connector = SdkConnector { facade },
                )

            val conn = client.connect()
            assertTrue(conn.isSuccess)

            val caps = client.fetchCapabilities()
            assertTrue(caps.isSuccess)
            val c: ServerCapabilities = caps.getOrThrow()
            assertEquals(1, c.tools.size)
            assertEquals("echo", c.tools.first().name)

            val res = client.callTool("echo", JsonObject(emptyMap()))
            assertTrue(res.isSuccess)
            assertTrue(res.getOrThrow().toString().contains("\"ok\":true"))

            verify(facade).getTools()
            verify(facade, times(1)).callTool(any(), any())
        }
    }

    @Test
    fun connect_with_stdio_bootstrap_calls_tool_with_args_and_opens_popup() {
        runBlocking {
            val facade: SdkClientFacade = mock()
            whenever(facade.callTool(any(), any())).thenReturn(
                bootstrapAuthUrlResult(
                    "https://accounts.example.com/oauth?redirect_uri=http%3A%2F%2Flocalhost%3A8111%2Foauth2callback",
                ),
            )

            val presenter = RecordingAuthorizationPresenter(autoComplete = true)
            AuthorizationPresenterRegistry.register(presenter)
            try {
                val client =
                    StdioMcpClient(
                        serverId = "stdio-connect-bootstrap",
                        command = "noop",
                        args = emptyList(),
                        env = emptyMap(),
                        authConfig =
                            AuthConfig.OAuth(
                                stdioBootstrap =
                                    AuthConfig.StdioBootstrap(
                                        tool = "start_google_auth",
                                        args =
                                            mapOf(
                                                "service_name" to "gmail",
                                                "user_google_email" to "to.dolfin@gmail.com",
                                            ),
                                    ),
                            ),
                        connector = SdkConnector { facade },
                    )

                val connectResult = client.connect()
                assertTrue(connectResult.isSuccess)

                val request = presenter.requests.singleOrNull()
                assertNotNull(request)
                assertEquals("broxy://stdio/stdio-connect-bootstrap", request.resourceUrl)
                assertEquals(
                    "https://accounts.example.com/oauth?redirect_uri=http%3A%2F%2Flocalhost%3A8111%2Foauth2callback",
                    request.authorizationUrl,
                )
                assertEquals("http://localhost:8111/oauth2callback", request.redirectUri)
                assertTrue(request.allowDismissWithoutCancel)

                val expectedBootstrapArgs =
                    buildJsonObject {
                        put("service_name", "gmail")
                        put("user_google_email", "to.dolfin@gmail.com")
                    }
                verify(facade, times(1)).callTool(eq("start_google_auth"), eq(expectedBootstrapArgs))
            } finally {
                AuthorizationPresenterRegistry.register(null)
            }
        }
    }

    @Test
    fun connect_does_not_parse_markdown_only_link_without_authorization_label() {
        runBlocking {
            val facade: SdkClientFacade = mock()
            whenever(facade.callTool(any(), any())).thenReturn(
                bootstrapResultWithMessage(
                    "Markdown for hyperlink: [Click](https://accounts.example.com/oauth)",
                ),
            )

            val presenter = RecordingAuthorizationPresenter(autoComplete = true)
            AuthorizationPresenterRegistry.register(presenter)
            try {
                val client =
                    StdioMcpClient(
                        serverId = "stdio-markdown-only",
                        command = "noop",
                        args = emptyList(),
                        env = emptyMap(),
                        authConfig =
                            AuthConfig.OAuth(
                                stdioBootstrap = AuthConfig.StdioBootstrap(tool = "start_google_auth"),
                            ),
                        connector = SdkConnector { facade },
                    )

                val connectResult = client.connect()
                assertTrue(connectResult.isSuccess)
                assertTrue(presenter.requests.isEmpty())
                verify(facade, times(1)).callTool(eq("start_google_auth"), any())
            } finally {
                AuthorizationPresenterRegistry.register(null)
            }
        }
    }

    @Test
    fun connect_does_not_parse_plain_url_without_authorization_label() {
        runBlocking {
            val facade: SdkClientFacade = mock()
            whenever(facade.callTool(any(), any())).thenReturn(
                bootstrapResultWithMessage("Open this link: https://accounts.example.com/oauth"),
            )

            val presenter = RecordingAuthorizationPresenter(autoComplete = true)
            AuthorizationPresenterRegistry.register(presenter)
            try {
                val client =
                    StdioMcpClient(
                        serverId = "stdio-plain-url-only",
                        command = "noop",
                        args = emptyList(),
                        env = emptyMap(),
                        authConfig =
                            AuthConfig.OAuth(
                                stdioBootstrap = AuthConfig.StdioBootstrap(tool = "start_google_auth"),
                            ),
                        connector = SdkConnector { facade },
                    )

                val connectResult = client.connect()
                assertTrue(connectResult.isSuccess)
                assertTrue(presenter.requests.isEmpty())
                verify(facade, times(1)).callTool(eq("start_google_auth"), any())
            } finally {
                AuthorizationPresenterRegistry.register(null)
            }
        }
    }

    @Test
    fun connect_bootstrap_failure_does_not_fail_connection() {
        runBlocking {
            val facade: SdkClientFacade = mock()
            whenever(facade.callTool(any(), any())).thenThrow(IllegalStateException("bootstrap failed"))

            val presenter = RecordingAuthorizationPresenter(autoComplete = true)
            AuthorizationPresenterRegistry.register(presenter)
            try {
                val client =
                    StdioMcpClient(
                        serverId = "stdio-bootstrap-error",
                        command = "noop",
                        args = emptyList(),
                        env = emptyMap(),
                        authConfig =
                            AuthConfig.OAuth(
                                stdioBootstrap = AuthConfig.StdioBootstrap(tool = "start_google_auth"),
                            ),
                        connector = SdkConnector { facade },
                    )

                val connectResult = client.connect()
                assertTrue(connectResult.isSuccess)
                assertTrue(presenter.requests.isEmpty())
                verify(facade, times(1)).callTool(eq("start_google_auth"), any())
            } finally {
                AuthorizationPresenterRegistry.register(null)
            }
        }
    }

    @Test
    fun connect_bootstrap_without_authorization_url_does_not_fail_connection() {
        runBlocking {
            val facade: SdkClientFacade = mock()
            whenever(facade.callTool(any(), any())).thenReturn(
                bootstrapResultWithMessage("Authentication required"),
            )

            val presenter = RecordingAuthorizationPresenter(autoComplete = true)
            AuthorizationPresenterRegistry.register(presenter)
            try {
                val client =
                    StdioMcpClient(
                        serverId = "stdio-bootstrap-no-url",
                        command = "noop",
                        args = emptyList(),
                        env = emptyMap(),
                        authConfig =
                            AuthConfig.OAuth(
                                stdioBootstrap = AuthConfig.StdioBootstrap(tool = "start_google_auth"),
                            ),
                        connector = SdkConnector { facade },
                    )

                val connectResult = client.connect()
                assertTrue(connectResult.isSuccess)
                assertTrue(presenter.requests.isEmpty())
                verify(facade, times(1)).callTool(eq("start_google_auth"), any())
            } finally {
                AuthorizationPresenterRegistry.register(null)
            }
        }
    }

    @Test
    fun connect_runs_bootstrap_on_each_reconnect() {
        runBlocking {
            val facade: SdkClientFacade = mock()
            whenever(facade.callTool(any(), any())).thenReturn(
                bootstrapAuthUrlResult("https://accounts.example.com/oauth"),
            )

            val presenter = RecordingAuthorizationPresenter(autoComplete = true)
            AuthorizationPresenterRegistry.register(presenter)
            try {
                val client =
                    StdioMcpClient(
                        serverId = "stdio-reconnect",
                        command = "noop",
                        args = emptyList(),
                        env = emptyMap(),
                        authConfig =
                            AuthConfig.OAuth(
                                stdioBootstrap = AuthConfig.StdioBootstrap(tool = "start_google_auth"),
                            ),
                        connector = SdkConnector { facade },
                    )

                assertTrue(client.connect().isSuccess)
                client.disconnect()
                assertTrue(client.connect().isSuccess)

                assertEquals(2, presenter.requests.size)
                verify(facade, times(2)).callTool(eq("start_google_auth"), any())
            } finally {
                AuthorizationPresenterRegistry.register(null)
            }
        }
    }

    @Test
    fun connect_waits_until_stdio_popup_is_closed() {
        runBlocking {
            val facade: SdkClientFacade = mock()
            whenever(facade.callTool(any(), any())).thenReturn(
                bootstrapAuthUrlResult("https://accounts.example.com/oauth"),
            )

            val presenter = RecordingAuthorizationPresenter(autoComplete = false)
            AuthorizationPresenterRegistry.register(presenter)
            try {
                val client =
                    StdioMcpClient(
                        serverId = "stdio-wait-popup-close",
                        command = "noop",
                        args = emptyList(),
                        env = emptyMap(),
                        authConfig =
                            AuthConfig.OAuth(
                                stdioBootstrap = AuthConfig.StdioBootstrap(tool = "start_google_auth"),
                            ),
                        connector = SdkConnector { facade },
                    )

                val connectDeferred = async { client.connect() }
                waitFor { presenter.requests.isNotEmpty() }
                assertTrue(!connectDeferred.isCompleted)

                val resourceUrl = presenter.requests.first().resourceUrl
                AuthorizationPopupSessionRegistry.complete(resourceUrl)

                val connectResult = withTimeout(2_000L) { connectDeferred.await() }
                assertTrue(connectResult.isSuccess)
            } finally {
                AuthorizationPresenterRegistry.register(null)
            }
        }
    }

    @Test
    fun connect_does_not_wait_for_popup_without_presenter() {
        runBlocking {
            val facade: SdkClientFacade = mock()
            whenever(facade.callTool(any(), any())).thenReturn(
                bootstrapAuthUrlResult("https://accounts.example.com/oauth"),
            )
            AuthorizationPresenterRegistry.register(null)
            val browserLauncher = CapturingBrowserLauncher()
            val client =
                StdioMcpClient(
                    serverId = "stdio-no-presenter",
                    command = "noop",
                    args = emptyList(),
                    env = emptyMap(),
                    authConfig =
                        AuthConfig.OAuth(
                            stdioBootstrap = AuthConfig.StdioBootstrap(tool = "start_google_auth"),
                        ),
                    browserLauncher = browserLauncher,
                    connector = SdkConnector { facade },
                )

            val connectResult = withTimeout(2_000L) { client.connect() }
            assertTrue(connectResult.isSuccess)
            assertEquals(listOf("https://accounts.example.com/oauth"), browserLauncher.urls)
        }
    }

    @Test
    fun connect_cleans_popup_session_when_presenter_fails() {
        runBlocking {
            val facade: SdkClientFacade = mock()
            whenever(facade.callTool(any(), any())).thenReturn(
                bootstrapAuthUrlResult("https://accounts.example.com/oauth"),
            )

            AuthorizationPresenterRegistry.register(FailingAuthorizationPresenter())
            try {
                val client =
                    StdioMcpClient(
                        serverId = "stdio-popup-presenter-error",
                        command = "noop",
                        args = emptyList(),
                        env = emptyMap(),
                        authConfig =
                            AuthConfig.OAuth(
                                stdioBootstrap = AuthConfig.StdioBootstrap(tool = "start_google_auth"),
                            ),
                        connector = SdkConnector { facade },
                    )

                val connectResult = withTimeout(2_000L) { client.connect() }
                assertTrue(connectResult.isSuccess)
            } finally {
                AuthorizationPresenterRegistry.register(null)
            }
        }
    }

    private fun bootstrapAuthUrlResult(authorizationUrl: String): CallToolResult =
        CallToolResult(
            content = emptyList(),
            structuredContent =
                buildJsonObject {
                    put("message", "Authorization URL: $authorizationUrl")
                },
            isError = false,
            meta = JsonObject(emptyMap()),
        )

    private fun bootstrapResultWithMessage(message: String): CallToolResult =
        CallToolResult(
            content = emptyList(),
            structuredContent =
                buildJsonObject {
                    put("message", message)
                },
            isError = false,
            meta = JsonObject(emptyMap()),
        )
}

private suspend fun waitFor(
    timeoutMillis: Long = 2_000L,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) {
            throw AssertionError("Condition was not met within ${timeoutMillis}ms")
        }
        delay(20L)
    }
}

private class RecordingAuthorizationPresenter(
    private val autoComplete: Boolean = false,
) : AuthorizationPresenter {
    val requests = mutableListOf<AuthorizationRequest>()

    override fun onAuthorizationRequest(request: AuthorizationRequest) {
        requests += request
        if (autoComplete) {
            AuthorizationPopupSessionRegistry.complete(request.resourceUrl)
        }
    }

    override fun onAuthorizationResult(result: AuthorizationResult) = Unit

    override fun resolveCompletionPageContext(resourceUrl: String): AuthorizationCompletionPageContext? = null
}

private class FailingAuthorizationPresenter : AuthorizationPresenter {
    override fun onAuthorizationRequest(request: AuthorizationRequest) {
        error("presenter failed")
    }

    override fun onAuthorizationResult(result: AuthorizationResult) = Unit
}

private class CapturingBrowserLauncher : BrowserLauncher {
    val urls = mutableListOf<String>()

    override fun open(url: String): Result<Unit> =
        runCatching {
            urls += url
        }
}
