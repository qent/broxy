package io.qent.broxy.core.mcp.errors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpErrorTest {
    @Test
    fun error_types_preserve_message_and_cause() {
        val cause = IllegalStateException("root")
        val errors =
            listOf(
                McpError.ConnectionError("conn", cause),
                McpError.TransportError("transport", cause),
                McpError.ProtocolError("protocol", cause),
                McpError.TimeoutError("timeout", cause),
            )

        assertEquals(listOf("conn", "transport", "protocol", "timeout"), errors.map { it.message })
        assertTrue(errors.all { it.cause === cause })
    }
}
