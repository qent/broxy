package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.qent.broxy.core.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal interface InboundSessionRegistry {
    suspend fun removeStaleSessions(
        nowMillis: Long,
        ttlMillis: Long,
    ): List<String>
}

internal class InboundStreamableHttpRegistry(
    private val logger: Logger,
) : InboundSessionRegistry {
    private val sessions = ConcurrentHashMap<String, StreamableHttpSession>()

    suspend fun getOrCreate(
        server: Server,
        requestedSessionId: String?,
    ): StreamableHttpSession {
        if (!requestedSessionId.isNullOrBlank()) {
            sessions[requestedSessionId]?.let {
                it.touch()
                return it
            }
        }

        val transport = StreamableHttpServerTransport(logger = logger)
        val session = server.createSession(transport)
        val entry = StreamableHttpSession(transport, session)
        sessions[transport.sessionId] = entry
        logger.debug("Registered Streamable HTTP session ${transport.sessionId}")
        return entry
    }

    fun touch(sessionId: String) {
        sessions[sessionId]?.touch()
    }

    suspend fun remove(sessionId: String?): Result<Unit> =
        runCatching {
            if (sessionId.isNullOrBlank()) return@runCatching
            val existing = sessions.remove(sessionId) ?: return@runCatching
            runCatching { existing.serverSession.close() }
            runCatching { existing.transport.close() }
            logger.debug("Removed Streamable HTTP session $sessionId")
        }

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
    val transport: StreamableHttpServerTransport,
    val serverSession: ServerSession,
    private val lastSeenAt: AtomicLong = AtomicLong(System.currentTimeMillis()),
) {
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

    fun register(
        transport: SseServerTransport,
        session: ServerSession,
    ) {
        sessions[transport.sessionId] = SseSession(transport, session)
        logger.debug("Registered SSE session ${transport.sessionId}")
    }

    fun get(sessionId: String?): SseSession? =
        if (sessionId.isNullOrBlank()) {
            null
        } else {
            sessions[sessionId]
        }

    fun touch(sessionId: String) {
        sessions[sessionId]?.touch()
    }

    fun remove(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return
        val removed = sessions.remove(sessionId) ?: return
        logger.debug("Removed SSE session $sessionId")
    }

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
            runCatching { removed.serverSession.close() }
            runCatching { removed.transport.close() }
            logger.debug("Removed SSE session $sessionId")
        }
        return expired
    }
}

internal data class SseSession(
    val transport: SseServerTransport,
    val serverSession: ServerSession,
    private val lastSeenAt: AtomicLong = AtomicLong(System.currentTimeMillis()),
) {
    fun touch(nowMillis: Long = System.currentTimeMillis()) {
        lastSeenAt.set(nowMillis)
    }

    fun isExpired(
        nowMillis: Long,
        ttlMillis: Long,
    ): Boolean = nowMillis - lastSeenAt.get() > ttlMillis
}
