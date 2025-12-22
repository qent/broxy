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
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
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
            assertEquals(HttpStatusCode.OK, notificationResponse.status)
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
            assertTrue(missingMessage is JSONRPCError)
            val error = missingMessage as JSONRPCError
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
}

private fun newRegistry(): Any {
    val clazz = Class.forName("io.qent.broxy.core.proxy.inbound.InboundStreamableHttpRegistry")
    val ctor = clazz.getDeclaredConstructor(Logger::class.java)
    ctor.isAccessible = true
    return ctor.newInstance(NoopLogger)
}

private fun mountStreamableRoute(
    route: io.ktor.server.routing.Route,
    server: io.modelcontextprotocol.kotlin.sdk.server.Server,
    registry: Any,
) {
    val clazz = Class.forName("io.qent.broxy.core.proxy.inbound.InboundServersKt")
    val method =
        clazz.getDeclaredMethod(
            "mountStreamableHttpRoute",
            io.ktor.server.routing.Route::class.java,
            io.modelcontextprotocol.kotlin.sdk.server.Server::class.java,
            registry.javaClass,
        )
    method.isAccessible = true
    method.invoke(null, route, server, registry)
}

private object NoopLogger : Logger {
    override fun debug(message: String) {}

    override fun info(message: String) {}

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) {}

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {}
}
