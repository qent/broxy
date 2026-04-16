package io.qent.broxy.core.mcp.clients

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.Method
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities as SdkServerCapabilities

class RealSdkClientFacadeTest {
    @Test
    fun skips_list_prompts_when_server_reports_no_prompts() =
        runBlocking {
            val client = Client(Implementation(name = "test", version = "0"))
            val transport = FakeTransport(serverCapabilities = SdkServerCapabilities())
            client.connect(transport)
            val facade = RealSdkClientFacade(client)

            val prompts = facade.getPrompts()

            assertTrue(prompts.isEmpty())
        }

    private class FakeTransport(
        private val serverCapabilities: SdkServerCapabilities,
        private val serverInfo: Implementation = Implementation(name = "server", version = "0"),
    ) : Transport {
        private var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
        private var onClose: (() -> Unit)? = null
        private var started = false

        override suspend fun start() {
            started = true
        }

        override suspend fun send(
            message: JSONRPCMessage,
            options: TransportSendOptions?,
        ) {
            check(started) { "Transport not started" }
            when (message) {
                is JSONRPCRequest -> handleRequest(message)
                is JSONRPCNotification -> Unit
                else -> error("Unexpected client message: ${message::class.simpleName}")
            }
        }

        override suspend fun close() {
            if (!started) return
            started = false
            onClose?.invoke()
        }

        override fun onClose(block: () -> Unit) {
            onClose = block
        }

        override fun onError(block: (Throwable) -> Unit) {
            // No-op: test transport does not emit transport errors.
        }

        override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
            onMessage = block
        }

        private suspend fun handleRequest(request: JSONRPCRequest) {
            if (request.method != Method.Defined.Initialize.value) {
                error("Unexpected request method: ${request.method}")
            }
            val response =
                JSONRPCResponse(
                    id = request.id,
                    result = InitializeResult(capabilities = serverCapabilities, serverInfo = serverInfo),
                )
            onMessage?.invoke(response)
        }
    }
}
