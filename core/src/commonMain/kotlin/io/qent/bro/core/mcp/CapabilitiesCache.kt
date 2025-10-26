package io.qent.bro.core.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class CapabilitiesCache(private val ttlMillis: Long = DEFAULT_TTL_MS) {
    private val mutex = Mutex()
    private var cached: ServerCapabilities? = null
    private var timestamp: TimeSource.Monotonic.ValueTimeMark? = null
    private val ttl: Duration = ttlMillis.milliseconds

    suspend fun get(): ServerCapabilities? = mutex.withLock {
        val ts = timestamp
        if (cached != null && ts != null && ts.elapsedNow() <= ttl) cached else null
    }

    suspend fun put(capabilities: ServerCapabilities) = mutex.withLock {
        cached = capabilities
        timestamp = TimeSource.Monotonic.markNow()
    }

    suspend fun invalidate() = mutex.withLock {
        cached = null
        timestamp = null
    }

    companion object {
        private const val DEFAULT_TTL_MS: Long = 5 * 60 * 1000 // 5 minutes
    }
}
