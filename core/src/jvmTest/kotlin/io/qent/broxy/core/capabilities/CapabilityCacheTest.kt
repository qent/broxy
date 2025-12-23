package io.qent.broxy.core.capabilities

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityCacheTest {
    @Test
    fun put_updates_cache_and_refresh_logic() {
        var nowMillis = 0L
        val persistence = TestPersistence()
        val cache = CapabilityCache({ nowMillis }, persistence)
        val snapshot =
            ServerCapsSnapshot(
                serverId = "s1",
                name = "Server One",
            )

        cache.put("s1", snapshot)
        assertFalse(cache.shouldRefresh("s1", intervalMillis = 1_000))
        assertEquals(1, persistence.saved.size)
        assertEquals("s1", persistence.saved.first().serverId)

        nowMillis = 1_000
        assertTrue(cache.shouldRefresh("s1", intervalMillis = 1_000))

        cache.updateName("s1", "Updated")
        assertEquals("Updated", cache.snapshot("s1")?.name)
        assertEquals(2, persistence.saved.size)
    }

    @Test
    fun retain_removes_unknown_ids_and_list_respects_requested_order() {
        val persistence = TestPersistence()
        val cache = CapabilityCache({ 0L }, persistence)
        cache.put("s1", ServerCapsSnapshot(serverId = "s1", name = "One"))
        cache.put("s2", ServerCapsSnapshot(serverId = "s2", name = "Two"))

        cache.retain(setOf("s2"))
        assertNull(cache.snapshot("s1"))
        assertEquals("Two", cache.snapshot("s2")?.name)
        assertEquals(listOf(setOf("s2")), persistence.retained)

        val listed = cache.list(listOf("s2", "s1"))
        assertEquals(listOf("s2"), listed.map { it.serverId })
    }

    @Test
    fun init_loads_persisted_entries() {
        val snapshot = ServerCapsSnapshot(serverId = "s1", name = "Server 1")
        val persisted = CapabilityCacheEntry(serverId = "s1", timestampMillis = 900L, snapshot = snapshot)
        val persistence = TestPersistence(loaded = listOf(persisted))
        val cache = CapabilityCache({ 1_000L }, persistence)

        assertEquals("Server 1", cache.snapshot("s1")?.name)
        assertFalse(cache.shouldRefresh("s1", intervalMillis = 200L))
    }

    private class TestPersistence(
        private val loaded: List<CapabilityCacheEntry> = emptyList(),
    ) : CapabilityCachePersistence {
        val saved = mutableListOf<CapabilityCacheEntry>()
        val removed = mutableListOf<String>()
        val retained = mutableListOf<Set<String>>()

        override fun loadAll(): List<CapabilityCacheEntry> = loaded

        override fun save(entry: CapabilityCacheEntry) {
            saved += entry
        }

        override fun remove(serverId: String) {
            removed += serverId
        }

        override fun retain(validIds: Set<String>) {
            retained += validIds
        }
    }
}
