package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.capabilities.CapabilityCache
import io.qent.broxy.core.capabilities.ServerCapsSnapshot
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityCacheTest {
    private var currentTime = 0L
    private val cache = CapabilityCache({ currentTime })

    @Test
    fun storesSnapshotsAndRetainsValidIds() {
        val first = snapshotFor("s1", "Server 1")
        val second = snapshotFor("s2", "Server 2")
        cache.put("s1", first)
        cache.put("s2", second)

        assertEquals(first, cache.snapshot("s1"))
        assertEquals(second, cache.snapshot("s2"))

        cache.retain(setOf("s1"))
        assertEquals(first, cache.snapshot("s1"))
        assertNull(cache.snapshot("s2"))
    }

    @Test
    fun shouldRefreshUsesTimestamp() {
        val caps = snapshotFor("s1", "Server 1")
        cache.put("s1", caps)

        assertFalse(cache.shouldRefresh("s1", 5_000))
        currentTime += 10_000
        assertTrue(cache.shouldRefresh("s1", 5_000))
    }

    @Test
    fun updateNameMutatesSnapshot() {
        val caps = snapshotFor("s1", "Server 1")
        cache.put("s1", caps)

        cache.updateName("s1", "Renamed")
        assertEquals("Renamed", cache.snapshot("s1")?.name)
    }

    private fun snapshotFor(
        id: String,
        name: String,
    ) = ServerCapsSnapshot(
        serverId = id,
        name = name,
        tools = emptyList(),
        prompts = emptyList(),
        resources = emptyList(),
    )
}
