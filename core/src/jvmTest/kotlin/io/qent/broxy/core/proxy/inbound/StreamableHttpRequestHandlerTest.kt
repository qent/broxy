package io.qent.broxy.core.proxy.inbound

import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.qent.broxy.core.proxy.ProxyMcpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StreamableHttpRequestHandlerTest {
    @Test
    fun handle_request_returns_response_with_session_header() =
        runTest {
            val proxy = ProxyMcpServer(emptyList(), logger = NoopLogger)
            val server = buildSdkServer(proxy, NoopLogger)
            val registry = InboundStreamableHttpRegistry(NoopLogger)
            val handler = StreamableHttpRequestHandler(server, registry) { DEFAULT_REQUEST_TIMEOUT_MILLIS }

            val request = JSONRPCRequest(1L, "tools/list", JsonNull)
            val response = handler.handleMessage(null, request)

            assertEquals(HttpStatusCode.OK, response.status)
            assertNotNull(response.headers[MCP_SESSION_ID_HEADER])
            val message = McpJson.decodeFromString<JSONRPCMessage>(response.body!!)
            assertIs<JSONRPCResponse>(message)
        }

    @Test
    fun handle_notification_returns_accepted_without_body() =
        runTest {
            val proxy = ProxyMcpServer(emptyList(), logger = NoopLogger)
            val server = buildSdkServer(proxy, NoopLogger)
            val registry = InboundStreamableHttpRegistry(NoopLogger)
            val handler = StreamableHttpRequestHandler(server, registry) { DEFAULT_REQUEST_TIMEOUT_MILLIS }

            val notification = JSONRPCNotification("notifications/test", JsonNull)
            val response = handler.handleMessage(null, notification)

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertNotNull(response.headers[MCP_SESSION_ID_HEADER])
            assertNull(response.body)
        }
}
