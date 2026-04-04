package io.qent.broxy.core.proxy.inbound

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InboundSessionCleanupTest {
    @Test
    fun cleanup_collects_stale_session_counts() =
        runTest {
            val streamable = FakeRegistry(listOf("s1", "s2"))
            val sse = FakeRegistry(emptyList())
            val cleanup =
                InboundSessionCleanup(
                    logger = NoopLogger,
                    scope = this,
                    registries =
                        listOf(
                            InboundSessionCleanup.RegistryEntry("streamable", streamable),
                            InboundSessionCleanup.RegistryEntry("sse", sse),
                        ),
                )

            val summary = cleanup.runCleanupOnce(nowMillis = 1234L)

            assertEquals(1, streamable.calls)
            assertEquals(1, sse.calls)
            assertEquals(1234L, streamable.lastNowMillis)
            assertEquals(
                mapOf(
                    "streamable" to 2,
                    "sse" to 0,
                ),
                summary.removedByRegistry,
            )
        }
}

private class FakeRegistry(
    private val removed: List<String>,
) : InboundSessionRegistry {
    var calls: Int = 0
    var lastNowMillis: Long? = null

    override suspend fun removeStaleSessions(
        nowMillis: Long,
        ttlMillis: Long,
    ): List<String> {
        calls += 1
        lastNowMillis = nowMillis
        return removed
    }
}
