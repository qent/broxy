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
            val server = buildSdkServer(proxy, NoopLogger)
            val registry = newRegistry()

            application {
                routing {
                    route("/mcp") {
                        mountStreamableRoute(this, server, registry)
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
            val server = buildSdkServer(proxy, NoopLogger)
            val registry = newRegistry()

            application {
                routing {
                    route("/mcp") {
                        mountStreamableRoute(this, server, registry)
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
            val server = buildSdkServer(proxy, NoopLogger)
            val registry = InboundStreamableHttpRegistry(NoopLogger)
            val session = registry.getOrCreate(server, null)
            val sessionId = session.transport.sessionId
            val now = System.currentTimeMillis()
            session.touch(now - 10_000L)

            val removed = registry.removeStaleSessions(now, ttlMillis = 1_000L)

            assertTrue(removed.contains(sessionId))
            val newSession = registry.getOrCreate(server, sessionId)
            assertTrue(newSession.transport.sessionId != sessionId)
        }
}

private fun newRegistry(): InboundStreamableHttpRegistry = InboundStreamableHttpRegistry(NoopLogger)

private fun mountStreamableRoute(
    route: io.ktor.server.routing.Route,
    server: io.modelcontextprotocol.kotlin.sdk.server.Server,
    registry: InboundStreamableHttpRegistry,
    requestTimeoutMillis: Long? = null,
) {
    if (requestTimeoutMillis == null) {
        route.mountStreamableHttpRoute(server, registry)
    } else {
        route.mountStreamableHttpRoute(server, registry, requestTimeoutMillis)
    }
}
