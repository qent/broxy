package io.qent.broxy.core.capabilities

import io.qent.broxy.core.utils.Logger
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileCapabilityCachePersistenceTest {
    private val noopLogger =
        object : Logger {
            override fun debug(message: String) {}

            override fun info(message: String) {}

            override fun warn(
                message: String,
                throwable: Throwable?,
            ) {}

            override fun error(
                message: String,
                throwable: Throwable?,
            ) {}
        }

    @Test
    fun save_load_remove_and_retain_entries() {
        val dir = Files.createTempDirectory("broxy-capabilities-cache")
        val persistence = FileCapabilityCachePersistence(dir, noopLogger)
        val snapshot1 = ServerCapsSnapshot(serverId = "s1", name = "Server 1")
        val snapshot2 = ServerCapsSnapshot(serverId = "s2", name = "Server 2")
        val entry1 = CapabilityCacheEntry(serverId = "s1", timestampMillis = 10L, snapshot = snapshot1)
        val entry2 = CapabilityCacheEntry(serverId = "s2", timestampMillis = 20L, snapshot = snapshot2)

        persistence.save(entry1)
        persistence.save(entry2)

        val loaded = persistence.loadAll().associateBy { it.serverId }
        assertEquals(setOf("s1", "s2"), loaded.keys)
        assertEquals(10L, loaded["s1"]?.timestampMillis)
        assertEquals("Server 2", loaded["s2"]?.snapshot?.name)

        persistence.remove("s1")
        val afterRemove = persistence.loadAll().map { it.serverId }.toSet()
        assertEquals(setOf("s2"), afterRemove)

        persistence.retain(setOf("s2"))
        val afterRetain = persistence.loadAll().map { it.serverId }.toSet()
        assertEquals(setOf("s2"), afterRetain)

        persistence.retain(emptySet())
        val empty = persistence.loadAll()
        assertTrue(empty.isEmpty())
    }
}
