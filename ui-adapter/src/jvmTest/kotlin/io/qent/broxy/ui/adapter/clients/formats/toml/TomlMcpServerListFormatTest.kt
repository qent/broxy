package io.qent.broxy.ui.adapter.clients.formats.toml

import io.qent.broxy.ui.adapter.clients.common.BroxyServerEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TomlMcpServerListFormatTest {
    private val format = TomlMcpServerListFormat()

    @Test
    fun listServersReturnsAllMcpServerSections() {
        val input =
            """
            [mcp_servers.alpha]
            url = "http://localhost:1111/mcp"

            [mcp_servers.broxy]
            url = "http://localhost:3335/mcp"
            """.trimIndent()

        val servers = format.listServers(input)

        assertEquals(listOf("alpha", "broxy"), servers)
    }

    @Test
    fun listServerEntriesReadsUrlCommandAndEnabled() {
        val input =
            """
            [mcp_servers.alpha]
            enabled = false
            command = "npx"

            [mcp_servers.beta]
            url = "http://localhost:1111/mcp"
            """.trimIndent()

        val entries = format.listServerEntries(input)

        assertEquals(2, entries.size)
        assertEquals("alpha", entries[0].sourceServerId)
        assertEquals(false, entries[0].enabled)
        assertEquals("npx", entries[0].command)
        assertEquals("beta", entries[1].sourceServerId)
        assertEquals("http://localhost:1111/mcp", entries[1].url)
    }

    @Test
    fun listServerEntriesReadsArgsEnvAndHeaders() {
        val input =
            """
            [mcp_servers.alpha]
            command = "npx"
            args = ["-y", "@modelcontextprotocol/server-github"]
            enabled = false

            [mcp_servers.alpha.env]
            GITHUB_TOKEN = "token"

            [mcp_servers.alpha.http_headers]
            Authorization = "Bearer token"

            [mcp_servers.beta]
            type = "streamable-http"
            url = "https://example.test/mcp"
            env = { API_KEY = "secret" }
            http_headers = { "X-Client" = "broxy" }
            """.trimIndent()

        val entries = format.listServerEntries(input)

        assertEquals(2, entries.size)
        assertEquals("alpha", entries[0].sourceServerId)
        assertEquals("npx", entries[0].command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-github"), entries[0].args)
        assertEquals(false, entries[0].enabled)
        assertEquals("token", entries[0].env["GITHUB_TOKEN"])
        assertEquals("Bearer token", entries[0].headers["Authorization"])
        assertEquals("beta", entries[1].sourceServerId)
        assertEquals("streamable-http", entries[1].type)
        assertEquals("https://example.test/mcp", entries[1].url)
        assertEquals("secret", entries[1].env["API_KEY"])
        assertEquals("broxy", entries[1].headers["X-Client"])
    }

    @Test
    fun upsertBroxyAddsSectionAndKeepsSingleBlankRuns() {
        val input =
            """
            [mcp_servers.other]
            url = "http://localhost:9999/mcp"
            """.trimIndent()

        val updated =
            format.upsertBroxy(
                content = input,
                entry = BroxyServerEntry.UrlEntry("http://localhost:3335/mcp"),
            )

        assertTrue(updated.contains("[mcp_servers.other]"))
        assertTrue(updated.contains("[mcp_servers.broxy]"))
        assertTrue(updated.contains("url = \"http://localhost:3335/mcp\""))
        assertNoConsecutiveBlankLines(updated)
    }

    @Test
    fun removeBroxyDeletesOnlyTargetSection() {
        val input =
            """
            [mcp_servers.broxy]
            url = "http://localhost:3335/mcp"

            [mcp_servers.other]
            url = "http://localhost:9999/mcp"
            """.trimIndent()

        val updated = format.removeBroxy(content = input)

        assertFalse(updated.contains("[mcp_servers.broxy]"))
        assertTrue(updated.contains("[mcp_servers.other]"))
    }

    @Test
    fun readBroxyStatusReturnsConfiguredUrl() {
        val input =
            """
            [mcp_servers.broxy]
            url = "http://localhost:3335/mcp"
            """.trimIndent()

        val status = format.readBroxyStatus(content = input)

        assertTrue(status.isConfigured)
        assertEquals("http://localhost:3335/mcp", status.configuredUrl)
    }

    @Test
    fun repeatedUpsertRemoveKeepsFormattingStable() {
        var content =
            """
            [mcp_servers.other]
            url = "http://localhost:9999/mcp"
            """.trimIndent()

        repeat(2) {
            content =
                format.upsertBroxy(
                    content = content,
                    entry = BroxyServerEntry.UrlEntry("http://localhost:3335/mcp"),
                )
            content = format.removeBroxy(content = content)
        }
        content =
            format.upsertBroxy(
                content = content,
                entry = BroxyServerEntry.UrlEntry("http://localhost:3335/mcp"),
            )

        assertTrue(content.contains("[mcp_servers.broxy]"))
        assertNoConsecutiveBlankLines(content)
    }

    private fun assertNoConsecutiveBlankLines(content: String) {
        val separator = if (content.contains("\r\n")) "\r\n" else "\n"
        val baseLines = content.split(separator)
        val lines = if (content.endsWith(separator)) baseLines + "" else baseLines
        val effectiveLines = if (lines.isNotEmpty() && lines.last().isBlank()) lines.dropLast(1) else lines
        var maxRun = 0
        var current = 0
        for (line in effectiveLines) {
            if (line.isBlank()) {
                current += 1
                maxRun = maxRun.coerceAtLeast(current)
            } else {
                current = 0
            }
        }
        assertTrue(maxRun <= 1, "Found consecutive blank lines (maxRun=$maxRun):\n$content")
    }
}
