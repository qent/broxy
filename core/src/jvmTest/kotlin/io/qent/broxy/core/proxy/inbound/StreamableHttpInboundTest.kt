package io.qent.broxy.core.proxy.inbound

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.ProxyMcpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamableHttpInboundTest {
    @Test
    fun inboundFactory_maps_http_and_streamable_to_streamable_server() {
        val proxy = ProxyMcpServer(emptyList(), logger = NoopLogger)

        val httpInbound =
            InboundServerFactory.create(
                TransportConfig.HttpTransport(url = "http://localhost:9999/mcp"),
                proxy,
                NoopLogger,
            )
        val streamableInbound =
            InboundServerFactory.create(
                TransportConfig.StreamableHttpTransport(url = "http://localhost:9999/mcp"),
                proxy,
                NoopLogger,
            )
        val stdioInbound =
            InboundServerFactory.create(
                TransportConfig.StdioTransport(command = "noop"),
                proxy,
                NoopLogger,
            )

        assertTrue(httpInbound.javaClass.simpleName.contains("StreamableHttpInboundServer"))
        assertTrue(streamableInbound.javaClass.simpleName.contains("StreamableHttpInboundServer"))
        assertTrue(stdioInbound.javaClass.simpleName.contains("StdioInboundServer"))
    }

    @Test
    @Suppress("LongMethod")
    fun streamable_http_routes_handle_errors_and_sessions() =
        testApplication {
            val proxy = ProxyMcpServer(emptyList(), logger = NoopLogger)
            val registry = newRegistry()

            application {
                routing {
                    route("/mcp") {
                        mountStreamableRoute(this, proxy, registry)
                    }
                }
            }

            val getResponse = client.get("/mcp")
            assertEquals(HttpStatusCode.MethodNotAllowed, getResponse.status)

            val deleteMissing = client.delete("/mcp")
            assertEquals(HttpStatusCode.BadRequest, deleteMissing.status)

            val wrongContentType =
                client.post("/mcp") {
                    setBody("{}")
                    contentType(ContentType.Text.Plain)
                }
            assertEquals(HttpStatusCode.BadRequest, wrongContentType.status)

            val invalidJson =
                client.post("/mcp") {
                    setBody("{")
                    contentType(ContentType.Application.Json)
                }
            assertEquals(HttpStatusCode.BadRequest, invalidJson.status)

            val notification = JSONRPCNotification("notifications/test", JsonNull)
            val notificationPayload = McpJson.encodeToString(JSONRPCMessage.serializer(), notification)
            val notificationResponse =
                client.post("/mcp") {
                    setBody(notificationPayload)
                    contentType(ContentType.Application.Json)
                }
            assertEquals(HttpStatusCode.Accepted, notificationResponse.status)
            val sessionId = notificationResponse.headers["mcp-session-id"]
            assertNotNull(sessionId)

            val request = JSONRPCRequest(1L, "tools/list", JsonNull)
            val requestPayload = McpJson.encodeToString(JSONRPCMessage.serializer(), request)
            val requestResponse =
                client.post("/mcp") {
                    header("mcp-session-id", sessionId)
                    setBody(requestPayload)
                    contentType(ContentType.Application.Json)
                }
            assertEquals(HttpStatusCode.OK, requestResponse.status)
            assertEquals(sessionId, requestResponse.headers["mcp-session-id"])
            val responseMessage =
                McpJson.decodeFromString<JSONRPCMessage>(requestResponse.bodyAsText())
            assertTrue(responseMessage is JSONRPCResponse)

            val missingRequest = JSONRPCRequest(2L, "unknown/method", JsonNull)
            val missingPayload = McpJson.encodeToString(JSONRPCMessage.serializer(), missingRequest)
            val missingResponse =
                client.post("/mcp") {
                    header("mcp-session-id", sessionId)
                    setBody(missingPayload)
                    contentType(ContentType.Application.Json)
                }
            assertEquals(HttpStatusCode.OK, missingResponse.status)
            val missingMessage =
                McpJson.decodeFromString<JSONRPCMessage>(missingResponse.bodyAsText())
            val error = assertIs<JSONRPCError>(missingMessage)
            assertEquals(RPCError.ErrorCode.METHOD_NOT_FOUND, error.error.code)

            val deleteResponse =
                client.delete("/mcp") {
                    header("mcp-session-id", sessionId)
                }
            assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

            val responseAfterDelete =
                client.post("/mcp") {
                    header("mcp-session-id", sessionId)
                    setBody(requestPayload)
                    contentType(ContentType.Application.Json)
                }
            assertEquals(HttpStatusCode.OK, responseAfterDelete.status)
            val newSessionId = responseAfterDelete.headers["mcp-session-id"]
            assertNotNull(newSessionId)
            assertTrue(newSessionId != sessionId)
        }

    @Test
    fun streamable_http_request_without_session_header_creates_session() =
        testApplication {
            val proxy = ProxyMcpServer(emptyList(), logger = NoopLogger)
            val registry = newRegistry()

            application {
                routing {
                    route("/mcp") {
                        mountStreamableRoute(this, proxy, registry)
                    }
                }
            }

            val request = JSONRPCRequest(1L, "tools/list", JsonNull)
            val requestPayload = McpJson.encodeToString(JSONRPCMessage.serializer(), request)
            val response =
                client.post("/mcp") {
                    setBody(requestPayload)
                    contentType(ContentType.Application.Json)
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val sessionId = response.headers["mcp-session-id"]
            assertNotNull(sessionId)
            val responseMessage = McpJson.decodeFromString<JSONRPCMessage>(response.bodyAsText())
            assertTrue(responseMessage is JSONRPCResponse)
        }

    @Test
    fun streamable_http_request_uses_configured_timeout() =
        runTest {
            val proxy = ProxyMcpServer(emptyList(), logger = NoopLogger)
            val inbound =
                InboundServerFactory.create(
                    transport = TransportConfig.StreamableHttpTransport(url = "http://localhost:9999/mcp"),
                    proxy = proxy,
                    logger = NoopLogger,
                    requestTimeoutMillis = 12_345L,
                )

            val configurable = inbound as RequestTimeoutConfigurableInbound
            assertEquals(12_345L, configurable.currentRequestTimeoutMillis())
            configurable.updateRequestTimeoutMillis(5_678L)
            assertEquals(5_678L, configurable.currentRequestTimeoutMillis())
        }

    @Test
    fun streamable_registry_cleans_stale_sessions() =
        runTest {
            val proxy = ProxyMcpServer(emptyList(), logger = NoopLogger)
            val registry = InboundStreamableHttpRegistry(NoopLogger)
            val session =
                registry.getOrCreate(requestedSessionId = null, binding = InboundSessionBinding("/mcp")) {
                    createStreamableSession(proxy, InboundSessionBinding("/mcp"))
                }
            val sessionId = session.transport.sessionId
            val now = System.currentTimeMillis()
            session.touch(now - 10_000L)

            val removed = registry.removeStaleSessions(now, ttlMillis = 1_000L)

            assertTrue(removed.contains(sessionId))
            val newSession =
                registry.getOrCreate(requestedSessionId = sessionId, binding = InboundSessionBinding("/mcp")) {
                    createStreamableSession(proxy, InboundSessionBinding("/mcp"))
                }
            assertTrue(newSession.transport.sessionId != sessionId)
        }

    @Test
    fun streamable_http_forced_preset_returns_404_when_preset_missing() =
        testApplication {
            val rawCapabilities = sampleRawCapabilities()
            val defaultProxy = createProxyWithSnapshot(basePreset(), rawCapabilities)
            val presets = mapOf("dev" to devPreset())
            val registry = newRegistry()

            application {
                routing {
                    mountPresetAwareStreamableRoutes(
                        basePath = "/mcp",
                        registry = registry,
                        defaultProxy = defaultProxy,
                        forcedPresets = presets,
                        rawCapabilities = rawCapabilities,
                    )
                }
            }

            val requestPayload =
                McpJson.encodeToString(
                    JSONRPCMessage.serializer(),
                    JSONRPCRequest(1L, "tools/list", JsonNull),
                )
            val response =
                client.post("/mcp/missing") {
                    setBody(requestPayload)
                    contentType(ContentType.Application.Json)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun streamable_http_rejects_reusing_session_id_across_different_route_bindings() =
        testApplication {
            val rawCapabilities = sampleRawCapabilities()
            val defaultProxy = createProxyWithSnapshot(basePreset(), rawCapabilities)
            val registry = newRegistry()

            application {
                routing {
                    mountPresetAwareStreamableRoutes(
                        basePath = "/mcp",
                        registry = registry,
                        defaultProxy = defaultProxy,
                        forcedPresets = mapOf("dev" to devPreset()),
                        rawCapabilities = rawCapabilities,
                    )
                }
            }

            val requestPayload =
                McpJson.encodeToString(
                    JSONRPCMessage.serializer(),
                    JSONRPCRequest(1L, "tools/list", JsonNull),
                )
            val defaultResponse =
                client.post("/mcp") {
                    setBody(requestPayload)
                    contentType(ContentType.Application.Json)
                }
            val sessionId = defaultResponse.headers[MCP_SESSION_ID_HEADER]
            assertNotNull(sessionId)

            val forcedResponse =
                client.post("/mcp/dev") {
                    header(MCP_SESSION_ID_HEADER, sessionId)
                    setBody(requestPayload)
                    contentType(ContentType.Application.Json)
                }

            assertEquals(HttpStatusCode.Conflict, forcedResponse.status)
        }

    @Test
    fun default_session_tracks_global_preset_switch_but_forced_session_stays_pinned() =
        testApplication {
            val rawCapabilities = sampleRawCapabilities()
            val defaultProxy = createProxyWithSnapshot(basePreset(), rawCapabilities)
            val registry = newRegistry()

            application {
                routing {
                    mountPresetAwareStreamableRoutes(
                        basePath = "/mcp",
                        registry = registry,
                        defaultProxy = defaultProxy,
                        forcedPresets = mapOf("dev" to devPreset()),
                        rawCapabilities = rawCapabilities,
                    )
                }
            }

            val requestPayload =
                McpJson.encodeToString(
                    JSONRPCMessage.serializer(),
                    JSONRPCRequest(1L, "tools/list", JsonNull),
                )
            val defaultResponse =
                client.post("/mcp") {
                    setBody(requestPayload)
                    contentType(ContentType.Application.Json)
                }
            val forcedResponse =
                client.post("/mcp/dev") {
                    setBody(requestPayload)
                    contentType(ContentType.Application.Json)
                }

            val defaultSessionId = defaultResponse.headers[MCP_SESSION_ID_HEADER]
            val forcedSessionId = forcedResponse.headers[MCP_SESSION_ID_HEADER]
            assertNotNull(defaultSessionId)
            assertNotNull(forcedSessionId)
            assertTrue(defaultResponse.bodyAsText().contains("alpha_search"))
            assertTrue(forcedResponse.bodyAsText().contains("beta_translate"))

            defaultProxy.applyPreset(Preset.empty())
            registry.allSessions().forEach { syncSdkServer(it.sdkServer, it.proxy, NoopLogger) }

            val afterDefaultSwitch =
                client.post("/mcp") {
                    header(MCP_SESSION_ID_HEADER, defaultSessionId)
                    setBody(requestPayload)
                    contentType(ContentType.Application.Json)
                }
            val afterForcedReuse =
                client.post("/mcp/dev") {
                    header(MCP_SESSION_ID_HEADER, forcedSessionId)
                    setBody(requestPayload)
                    contentType(ContentType.Application.Json)
                }

            assertEquals(HttpStatusCode.OK, afterDefaultSwitch.status)
            assertEquals(HttpStatusCode.OK, afterForcedReuse.status)
            assertTrue(!afterDefaultSwitch.bodyAsText().contains("alpha_search"))
            assertTrue(afterForcedReuse.bodyAsText().contains("beta_translate"))
        }
}

private fun newRegistry(): InboundStreamableHttpRegistry = InboundStreamableHttpRegistry(NoopLogger)

private fun mountStreamableRoute(
    route: io.ktor.server.routing.Route,
    proxy: ProxyMcpServer,
    registry: InboundStreamableHttpRegistry,
    requestTimeoutMillis: Long? = null,
) {
    if (requestTimeoutMillis == null) {
        route.mountStreamableHttpRoute(
            sessions = registry,
            bindingProvider = { InboundSessionBinding("/mcp") },
            sessionFactory = { binding -> createStreamableSession(proxy, binding) },
        )
    } else {
        route.mountStreamableHttpRoute(
            sessions = registry,
            bindingProvider = { InboundSessionBinding("/mcp") },
            sessionFactory = { binding -> createStreamableSession(proxy, binding) },
            requestTimeoutMillis = requestTimeoutMillis,
        )
    }
}

private fun io.ktor.server.routing.Routing.mountPresetAwareStreamableRoutes(
    basePath: String,
    registry: InboundStreamableHttpRegistry,
    defaultProxy: ProxyMcpServer,
    forcedPresets: Map<String, Preset>,
    rawCapabilities: Map<String, ServerCapabilities>,
) {
    route(basePath) {
        mountStreamableHttpRoute(
            sessions = registry,
            bindingProvider = { InboundSessionBinding(basePath) },
            sessionFactory = { binding -> createStreamableSession(defaultProxy, binding) },
        )
        route("/{presetId}") {
            mountStreamableHttpRoute(
                sessions = registry,
                bindingProvider = { call ->
                    val presetId = requireNotNull(call.parameters["presetId"])
                    InboundSessionBinding("$basePath/$presetId", presetId)
                },
                sessionFactory = { binding ->
                    val presetId = requireNotNull(binding.presetId)
                    val forcedPreset = forcedPresets[presetId] ?: throw InboundPresetNotFoundException(presetId)
                    val forcedProxy = createProxyWithSnapshot(forcedPreset, rawCapabilities)
                    createStreamableSession(forcedProxy, binding, ownsProxy = true)
                },
            )
        }
    }
}

private fun createProxyWithSnapshot(
    preset: Preset,
    rawCapabilities: Map<String, ServerCapabilities>,
): ProxyMcpServer =
    ProxyMcpServer(emptyList(), logger = NoopLogger).also { proxy ->
        proxy.start(preset, TransportConfig.StreamableHttpTransport("http://localhost:9999/mcp"))
        proxy.setCapabilitiesSnapshot(rawCapabilities)
    }

private suspend fun createStreamableSession(
    proxy: ProxyMcpServer,
    binding: InboundSessionBinding,
    ownsProxy: Boolean = false,
): StreamableHttpSession {
    val sdkServer = buildSdkServer(proxy, NoopLogger)
    val transport = StreamableHttpServerTransport(logger = NoopLogger)
    val serverSession = sdkServer.createSession(transport)
    return StreamableHttpSession(
        binding = binding,
        proxy = proxy,
        sdkServer = sdkServer,
        transport = transport,
        serverSession = serverSession,
        ownsProxy = ownsProxy,
    )
}

private fun sampleRawCapabilities(): Map<String, ServerCapabilities> =
    mapOf(
        "alpha" to ServerCapabilities(tools = listOf(ToolDescriptor(name = "search"))),
        "beta" to ServerCapabilities(tools = listOf(ToolDescriptor(name = "translate"))),
    )

private fun basePreset(): Preset =
    Preset(
        id = "base",
        name = "Base",
        tools = listOf(ToolReference(serverId = "alpha", toolName = "search")),
    )

private fun devPreset(): Preset =
    Preset(
        id = "dev",
        name = "Dev",
        tools = listOf(ToolReference(serverId = "beta", toolName = "translate")),
    )
