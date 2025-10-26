package io.qent.bro.core.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class CapabilitiesCacheTest {
    @Test
    fun get_put_invalidate_and_ttl_expiry() = runBlocking {
        val cache = CapabilitiesCache(ttlMillis = 20)
        assertNull(cache.get())

        val caps = ServerCapabilities(tools = listOf(ToolDescriptor("a")))
        cache.put(caps)
        assertNotNull(cache.get())
        assertEquals("a", cache.get()!!.tools.first().name)

        // After TTL expires, value should be null
        delay(30)
        assertNull(cache.get())

        // Put again and invalidate manually
        cache.put(caps)
        assertNotNull(cache.get())
        cache.invalidate()
        assertNull(cache.get())
    }
}

