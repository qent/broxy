package io.qent.broxy.agents.codex

import io.qent.broxy.agents.codex.mcp.AgentPortRangeAllocator
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentPortRangeAllocatorTest {
    @Test
    fun acquire_release_allowsReacquireInSameRange() {
        val allocator = AgentPortRangeAllocator()
        val port = findFreePort()

        val acquired = allocator.acquire(port, port)
        assertEquals(port, acquired)
        allocator.release(acquired)

        val reacquired = allocator.acquire(port, port)
        assertEquals(port, reacquired)
        allocator.release(reacquired)
    }

    @Test
    fun acquire_throwsWhenRangeExhausted() {
        val allocator = AgentPortRangeAllocator()
        val port = findFreePort()
        val first = allocator.acquire(port, port)

        val error = assertFailsWith<IllegalStateException> { allocator.acquire(port, port) }
        assertTrue(error.message.orEmpty().contains("No free port available"))
        allocator.release(first)
    }

    @Test
    fun acquire_throwsForInvalidRange() {
        val allocator = AgentPortRangeAllocator()

        assertFailsWith<IllegalArgumentException> { allocator.acquire(0, 10) }
        assertFailsWith<IllegalArgumentException> { allocator.acquire(20, 10) }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { socket -> socket.localPort }
}
