package io.qent.broxy.ui.adapter.clients

import io.qent.broxy.ui.adapter.clients.common.McpServerListEntry
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ImportableServerMapperTest {
    @Test
    fun toImportServerOrNullMapsStreamableHttpType() {
        val entry =
            McpServerListEntry(
                sourceServerId = "remote",
                type = "streamable-http",
                url = "https://example.test/mcp",
                headers = mapOf("Authorization" to "Bearer token"),
            )

        val mapped = entry.toImportServerOrNull()

        val importServer = assertNotNull(mapped)
        val transport = assertIs<UiStreamableHttpTransport>(importServer.transport)
        assertEquals("https://example.test/mcp", transport.url)
        assertEquals("Bearer token", transport.headers["Authorization"])
    }

    @Test
    fun toImportServerOrNullMapsStreamableHttpUnderscoreAlias() {
        val entry =
            McpServerListEntry(
                sourceServerId = "remote",
                type = "streamable_http",
                url = "https://example.test/mcp",
            )

        val mapped = entry.toImportServerOrNull()

        val importServer = assertNotNull(mapped)
        assertIs<UiStreamableHttpTransport>(importServer.transport)
    }
}
