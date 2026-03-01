package io.qent.broxy.ui.adapter.clients

import io.qent.broxy.ui.adapter.models.UiAiClientBroxyConfigMismatchNotice
import io.qent.broxy.ui.adapter.models.UiAiClientMissingConfigNotice
import io.qent.broxy.ui.adapter.models.UiAiClientNoticeSeverity
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexClientConnectorTest {
    @Test
    fun loadStatusReportsMissingConfig() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-config")
            val displayPath = "~/.codex/config.toml"
            val connector = CodexClientConnector(configPath = tempDir.resolve("config.toml"), displayPath = displayPath)

            val status = connector.loadStatus(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            assertFalse(status.isConnected)
            assertFalse(status.canConnect)
            val notice = assertNotNull(status.notice)
            assertEquals(UiAiClientNoticeSeverity.Error, notice.severity)
            val missingNotice = notice as? UiAiClientMissingConfigNotice
            assertNotNull(missingNotice)
            assertEquals("Codex", missingNotice.clientName)
        }

    @Test
    fun loadStatusDetectsActiveBroxyConfig() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-config")
            val configPath = tempDir.resolve("config.toml")
            Files.writeString(
                configPath,
                """
                [mcp_servers.broxy]
                url = "http://localhost:3335/mcp"

                """.trimIndent(),
            )
            val connector = CodexClientConnector(configPath = configPath)

            val status = connector.loadStatus(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            assertTrue(status.isConnected)
            assertTrue(status.canConnect)
            assertNull(status.notice)
        }

    @Test
    fun loadStatusWarnsOnAlternateBroxyConfig() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-config")
            val configPath = tempDir.resolve("config.toml")
            Files.writeString(
                configPath,
                """
                [mcp_servers.broxy]
                url = "http://localhost:4444/mcp"

                """.trimIndent(),
            )
            val connector = CodexClientConnector(configPath = configPath)

            val status = connector.loadStatus(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            assertFalse(status.isConnected)
            assertTrue(status.canConnect)
            val notice = assertNotNull(status.notice)
            assertEquals(UiAiClientNoticeSeverity.Warning, notice.severity)
            val mismatchNotice = notice as? UiAiClientBroxyConfigMismatchNotice
            assertNotNull(mismatchNotice)
            assertEquals("http://localhost:4444/mcp", mismatchNotice.configuredUrl)
        }

    @Test
    fun connectAddsBroxyBlockAndPreservesOtherSections() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-config")
            val configPath = tempDir.resolve("config.toml")
            Files.writeString(
                configPath,
                """
                [mcp_servers.other]
                url = "http://localhost:9999/mcp"

                """.trimIndent(),
            )
            val connector = CodexClientConnector(configPath = configPath)

            connector.connect(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            val content = Files.readString(configPath)
            assertTrue(content.contains("[mcp_servers.other]"))
            assertTrue(content.contains("[mcp_servers.broxy]"))
            assertTrue(content.contains("url = \"http://localhost:3335/mcp\""))
            val separator = if (content.contains("\r\n")) "\r\n" else "\n"
            val baseLines = content.split(separator)
            val lines = if (content.endsWith(separator)) baseLines + "" else baseLines
            val urlIndex = lines.indexOf("url = \"http://localhost:3335/mcp\"")
            assertTrue(urlIndex >= 0)
            assertTrue(lines.getOrNull(urlIndex + 1)?.isBlank() == true)
        }

    @Test
    fun disconnectRemovesBroxyBlockOnly() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-config")
            val configPath = tempDir.resolve("config.toml")
            Files.writeString(
                configPath,
                """
                [mcp_servers.broxy]
                url = "http://localhost:3335/mcp"

                [mcp_servers.other]
                url = "http://localhost:9999/mcp"

                """.trimIndent(),
            )
            val connector = CodexClientConnector(configPath = configPath)

            connector.disconnect(AiClientConnectionRequest("http://localhost:3335/mcp")).getOrThrow()

            val content = Files.readString(configPath)
            assertFalse(content.contains("[mcp_servers.broxy]"))
            assertTrue(content.contains("[mcp_servers.other]"))
        }

    @Test
    fun connectDisconnectDoesNotAccumulateBlankLines() =
        runTest {
            val tempDir = Files.createTempDirectory("codex-config")
            val configPath = tempDir.resolve("config.toml")
            Files.writeString(
                configPath,
                """
                [mcp_servers.other]
                url = "http://localhost:9999/mcp"
                """.trimIndent(),
            )
            val connector = CodexClientConnector(configPath = configPath)
            val request = AiClientConnectionRequest("http://localhost:3335/mcp")

            repeat(2) {
                connector.connect(request).getOrThrow()
                connector.disconnect(request).getOrThrow()
            }
            connector.connect(request).getOrThrow()

            val content = Files.readString(configPath)
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
