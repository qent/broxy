package io.qent.broxy.agents.codex.mcp

import java.net.InetAddress
import java.net.ServerSocket

/**
 * Process-local allocator for short-lived TCP ports used by isolated inbound MCP servers.
 */
class AgentPortRangeAllocator {
    private val reserved = mutableSetOf<Int>()
    private val lock = Any()

    fun acquire(
        rangeStart: Int,
        rangeEnd: Int,
    ): Int {
        require(rangeStart in MIN_PORT..MAX_PORT) { "Invalid port range start: $rangeStart" }
        require(rangeEnd in MIN_PORT..MAX_PORT) { "Invalid port range end: $rangeEnd" }
        require(rangeStart <= rangeEnd) { "Invalid port range: $rangeStart..$rangeEnd" }
        synchronized(lock) {
            for (port in rangeStart..rangeEnd) {
                if (port !in reserved && isPortFree(port)) {
                    reserved += port
                    return port
                }
            }
        }
        error("No free port available in range $rangeStart..$rangeEnd")
    }

    fun release(port: Int) {
        synchronized(lock) {
            reserved -= port
        }
    }

    private fun isPortFree(port: Int): Boolean =
        runCatching {
            ServerSocket(port, 0, InetAddress.getByName("127.0.0.1")).use { socket ->
                socket.reuseAddress = true
            }
        }.isSuccess

    private companion object {
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65_535
    }
}
