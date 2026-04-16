package io.qent.broxy.headless

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.utils.Logger
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RawCapabilitiesCacheTest {
    @Test
    fun save_and_load_snapshot() {
        val dir = Files.createTempDirectory("broxy-raw-capabilities-cache")
        val cache = RawCapabilitiesCache(baseDir = dir, logger = NoopLogger)
        val caps = ServerCapabilities(tools = listOf(ToolDescriptor("search")))

        cache.saveSnapshot(mapOf("s1" to caps), timestampMillis = 1234L)

        val entries = cache.loadAll()
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("s1", entry.serverId)
        assertEquals(1234L, entry.timestampMillis)
        assertEquals(caps, entry.capabilities)
    }

    @Test
    fun save_snapshot_prunes_removed_servers() {
        val dir = Files.createTempDirectory("broxy-raw-capabilities-cache")
        val cache = RawCapabilitiesCache(baseDir = dir, logger = NoopLogger)
        val capsA = ServerCapabilities(tools = listOf(ToolDescriptor("a")))
        val capsB = ServerCapabilities(tools = listOf(ToolDescriptor("b")))

        cache.saveSnapshot(mapOf("s1" to capsA, "s2" to capsB), timestampMillis = 10L)
        cache.saveSnapshot(mapOf("s2" to capsB), timestampMillis = 20L)

        val entries = cache.loadAll().associateBy { it.serverId }
        assertEquals(1, entries.size)
        assertNotNull(entries["s2"])
    }

    private object NoopLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
}
