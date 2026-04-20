package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal interface InboundSessionRegistry {
    suspend fun removeStaleSessions(
        nowMillis: Long,
        ttlMillis: Long,
    ): List<String>
}

internal interface InboundManagedSession {
    val binding: InboundSessionBinding
    val proxy: ProxyMcpServer
    val sdkServer: Server
}

internal class InboundStreamableHttpRegistry(
    private val logger: Logger,
) : InboundSessionRegistry {
    private val sessions = ConcurrentHashMap<String, StreamableHttpSession>()

    suspend fun getOrCreate(
        requestedSessionId: String?,
        binding: InboundSessionBinding,
        createSession: suspend () -> StreamableHttpSession,
    ): StreamableHttpSession {
        if (!requestedSessionId.isNullOrBlank()) {
            sessions[requestedSessionId]?.let {
                if (it.binding != binding) {
                    throw InboundSessionBindingConflictException(bindingConflictMessage(it.binding, binding))
                }
                it.touch()
                return it
            }
        }

        val entry = createSession()
        sessions[entry.transport.sessionId] = entry
        logger.debug("Registered Streamable HTTP session ${entry.transport.sessionId}")
        return entry
    }

    fun touch(sessionId: String) {
        sessions[sessionId]?.touch()
    }

    suspend fun remove(
        sessionId: String?,
        binding: InboundSessionBinding? = null,
    ): Result<Unit> =
        runCatching {
            if (sessionId.isNullOrBlank()) return@runCatching
            val existing = sessions[sessionId] ?: return@runCatching
            if (binding != null && existing.binding != binding) {
                throw InboundSessionBindingConflictException(bindingConflictMessage(existing.binding, binding))
            }
            sessions.remove(sessionId) ?: return@runCatching
            closeSession(existing)
            logger.debug("Removed Streamable HTTP session $sessionId")
        }

    fun allSessions(): List<StreamableHttpSession> = sessions.values.toList()

    override suspend fun removeStaleSessions(
        nowMillis: Long,
        ttlMillis: Long,
    ): List<String> {
        if (ttlMillis <= 0L) return emptyList()
        val expired =
            sessions.entries
                .filter { entry -> entry.value.isExpired(nowMillis, ttlMillis) }
                .map { it.key }
        expired.forEach { remove(it) }
        return expired
    }
}

internal data class StreamableHttpSession(
    override val binding: InboundSessionBinding,
    override val proxy: ProxyMcpServer,
    override val sdkServer: Server,
    val transport: StreamableHttpServerTransport,
    val serverSession: ServerSession,
    val ownsProxy: Boolean,
    private val lastSeenAt: AtomicLong = AtomicLong(System.currentTimeMillis()),
) : InboundManagedSession {
    fun touch(nowMillis: Long = System.currentTimeMillis()) {
        lastSeenAt.set(nowMillis)
    }

    fun isExpired(
        nowMillis: Long,
        ttlMillis: Long,
    ): Boolean = nowMillis - lastSeenAt.get() > ttlMillis
}

internal class InboundSseRegistry(
    private val logger: Logger,
) : InboundSessionRegistry {
    private val sessions = ConcurrentHashMap<String, SseSession>()

    fun register(session: SseSession) {
        sessions[session.transport.sessionId] = session
        logger.debug("Registered SSE session ${session.transport.sessionId}")
    }

    fun get(
        sessionId: String?,
        binding: InboundSessionBinding? = null,
    ): SseSession? =
        sessionId
            ?.takeUnless { it.isBlank() }
            ?.let { resolvedSessionId ->
                val existing = sessions[resolvedSessionId] ?: return null
                if (binding != null && existing.binding != binding) {
                    throw InboundSessionBindingConflictException(bindingConflictMessage(existing.binding, binding))
                }
                existing
            }

    fun touch(sessionId: String) {
        sessions[sessionId]?.touch()
    }

    fun remove(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        val removed = sessions.remove(sessionId) ?: return
        runCatching { closeSession(removed) }
        logger.debug("Removed SSE session $sessionId")
    }

    fun allSessions(): List<SseSession> = sessions.values.toList()

    override suspend fun removeStaleSessions(
        nowMillis: Long,
        ttlMillis: Long,
    ): List<String> {
        if (ttlMillis <= 0L) return emptyList()
        val expired =
            sessions.entries
                .filter { entry -> entry.value.isExpired(nowMillis, ttlMillis) }
                .map { it.key }
        expired.forEach { sessionId ->
            val removed = sessions.remove(sessionId) ?: return@forEach
            runCatching { closeSession(removed) }
            logger.debug("Removed SSE session $sessionId")
        }
        return expired
    }
}

internal data class SseSession(
    override val binding: InboundSessionBinding,
    override val proxy: ProxyMcpServer,
    override val sdkServer: Server,
    val transport: SseServerTransport,
    val serverSession: ServerSession,
    val ownsProxy: Boolean,
    private val lastSeenAt: AtomicLong = AtomicLong(System.currentTimeMillis()),
) : InboundManagedSession {
    fun touch(nowMillis: Long = System.currentTimeMillis()) {
        lastSeenAt.set(nowMillis)
    }

    fun isExpired(
        nowMillis: Long,
        ttlMillis: Long,
    ): Boolean = nowMillis - lastSeenAt.get() > ttlMillis
}

private fun closeSession(session: StreamableHttpSession) {
    runCatching { runBlocking { session.serverSession.close() } }
    runCatching { runBlocking { session.transport.close() } }
    runCatching { runBlocking { session.sdkServer.close() } }
    if (session.ownsProxy) {
        runCatching { session.proxy.stop() }
    }
}

private fun closeSession(session: SseSession) {
    runCatching { runBlocking { session.serverSession.close() } }
    runCatching { runBlocking { session.transport.close() } }
    runCatching { runBlocking { session.sdkServer.close() } }
    if (session.ownsProxy) {
        runCatching { session.proxy.stop() }
    }
}
