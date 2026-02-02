package io.qent.broxy.core.proxy.inbound

import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class InboundSessionCleanup(
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val registries: List<RegistryEntry>,
    private val intervalMillis: Long = SESSION_CLEANUP_INTERVAL_MILLIS,
    private val ttlMillis: Long = SESSION_TTL_MILLIS,
) {
    data class RegistryEntry(
        val name: String,
        val registry: InboundSessionRegistry,
    )

    data class CleanupSummary(
        val removedByRegistry: Map<String, Int>,
    )

    private var job: Job? = null

    fun start() {
        job?.cancel()
        job =
            scope.launch {
                while (isActive) {
                    delay(intervalMillis)
                    runCleanupOnce()
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun runCleanupOnce(nowMillis: Long = System.currentTimeMillis()): CleanupSummary {
        if (registries.isEmpty()) return CleanupSummary(emptyMap())
        val removedByRegistry = mutableMapOf<String, Int>()
        for (entry in registries) {
            val removed = entry.registry.removeStaleSessions(nowMillis, ttlMillis)
            removedByRegistry[entry.name] = removed.size
        }
        if (removedByRegistry.values.any { it > 0 }) {
            val streamable = removedByRegistry["streamable"] ?: 0
            val sse = removedByRegistry["sse"] ?: 0
            logger.debug("Cleaned up inbound sessions streamable=$streamable sse=$sse")
        }
        return CleanupSummary(removedByRegistry)
    }
}
