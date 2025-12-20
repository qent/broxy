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
        val cache = CapabilityCache { nowMillis }
        val snapshot =
            ServerCapsSnapshot(
                serverId = "s1",
                name = "Server One",
            )

        cache.put("s1", snapshot)
        assertFalse(cache.shouldRefresh("s1", intervalMillis = 1_000))

        nowMillis = 1_000
        assertTrue(cache.shouldRefresh("s1", intervalMillis = 1_000))

        cache.updateName("s1", "Updated")
        assertEquals("Updated", cache.snapshot("s1")?.name)
    }

    @Test
    fun retain_removes_unknown_ids_and_list_respects_requested_order() {
        val cache = CapabilityCache { 0L }
        cache.put("s1", ServerCapsSnapshot(serverId = "s1", name = "One"))
        cache.put("s2", ServerCapsSnapshot(serverId = "s2", name = "Two"))

        cache.retain(setOf("s2"))
        assertNull(cache.snapshot("s1"))
        assertEquals("Two", cache.snapshot("s2")?.name)

        val listed = cache.list(listOf("s2", "s1"))
        assertEquals(listOf("s2"), listed.map { it.serverId })
    }
}
