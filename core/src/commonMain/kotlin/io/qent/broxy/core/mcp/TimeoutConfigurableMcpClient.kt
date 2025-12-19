package io.qent.broxy.core.mcp

/**
 * Optional capability for MCP clients that can adjust their internal timeout settings.
 */
interface TimeoutConfigurableMcpClient {
    fun updateTimeouts(
        connectTimeoutMillis: Long,
        capabilitiesTimeoutMillis: Long,
    )
}
