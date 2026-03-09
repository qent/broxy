package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class StreamableHttpServerTransport(
    private val logger: Logger,
) : AbstractTransport() {
    private val initialized: AtomicBoolean = AtomicBoolean(false)
    val sessionId: String = UUID.randomUUID().toString()

    private val responseWaiters = ConcurrentHashMap<RequestId, CompletableDeferred<JSONRPCMessage>>()

    override suspend fun start() {
        if (!initialized.compareAndSet(false, true)) {
            error("StreamableHttpServerTransport already started!")
        }
    }

    suspend fun handleMessage(message: JSONRPCMessage) {
        if (!initialized.get()) error("Transport is not started")
        _onMessage.invoke(message)
    }

    suspend fun awaitResponse(
        request: JSONRPCRequest,
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    ): JSONRPCMessage {
        val deferred = CompletableDeferred<JSONRPCMessage>()
        val previous = responseWaiters.putIfAbsent(request.id, deferred)
        check(previous == null) { "Duplicate in-flight request id ${request.id}" }
        try {
            handleMessage(request)
            return withTimeout(timeoutMillis) { deferred.await() }
        } finally {
            responseWaiters.remove(request.id)
        }
    }

    override suspend fun send(
        message: JSONRPCMessage,
        options: TransportSendOptions?,
    ) {
        if (!initialized.get()) error("Not connected")
        when (message) {
            is JSONRPCResponse -> {
                val waiter = responseWaiters[message.id]
                if (waiter != null && waiter.complete(message)) return
                logger.warn("Dropping response for unknown request id ${message.id}")
            }

            is JSONRPCError -> {
                val waiter = responseWaiters[message.id]
                if (waiter != null && waiter.complete(message)) return
                logger.warn("Dropping error response for unknown request id ${message.id}")
            }

            else -> {
                // JSON-only Streamable HTTP inbound: server-to-client notifications are best-effort dropped.
                logger.debug("Dropping outbound message (no SSE): ${message::class.simpleName}")
            }
        }
    }

    override suspend fun close() {
        if (!initialized.get()) return
        initialized.set(false)
        _onClose.invoke()
    }
}
