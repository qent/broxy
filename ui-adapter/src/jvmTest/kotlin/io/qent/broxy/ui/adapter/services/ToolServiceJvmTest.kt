package io.qent.broxy.ui.adapter.services

import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolServiceJvmTest {
    @Test
    fun checkStdioCommandAvailability_returns_unavailable_for_blank_command() =
        runTest {
            val result = checkStdioCommandAvailability("   ").getOrThrow()
            assertFalse(result.isAvailable)
            assertNull(result.resolvedPath)
        }

    @Test
    fun checkStdioCommandAvailability_uses_path_override() =
        runTest {
            val binDir = Files.createTempDirectory("broxy-toolservice-bin")
            val command = "broxy-test-cmd"
            val executable = binDir.resolve(command).toFile()
            executable.writeText("#!/bin/sh\necho ok\n")
            executable.setExecutable(true)

            val result =
                checkStdioCommandAvailability(
                    command = command,
                    env = mapOf("PATH" to binDir.toString()),
                ).getOrThrow()

            assertTrue(result.isAvailable)
            assertTrue(result.resolvedPath?.endsWith(command) == true)
        }

    @Test
    fun checkStdioCommandAvailability_returns_unavailable_for_unknown_command() =
        runTest {
            val result =
                checkStdioCommandAvailability(
                    command = "broxy-nonexistent-command-123456",
                    env = emptyMap(),
                ).getOrThrow()

            assertFalse(result.isAvailable)
            assertNull(result.resolvedPath)
        }

    @Test
    fun fetchServerCapabilities_returns_failure_for_unreachable_http_server() =
        runTest {
            val config =
                UiMcpServerConfig(
                    id = "http",
                    name = "HTTP",
                    transport = UiStreamableHttpTransport(url = "http://127.0.0.1:1/mcp"),
                )

            val result =
                fetchServerCapabilities(
                    config = config,
                    timeoutSeconds = 1,
                    connectionRetryCount = 1,
                    ignoreHttpsCertificateErrors = false,
                )

            assertTrue(result.isFailure)
        }

    @Test
    fun fetchServerCapabilities_returns_failure_for_invalid_stdio_command() =
        runTest {
            val config =
                UiMcpServerConfig(
                    id = "stdio",
                    name = "STDIO",
                    transport = UiStdioTransport(command = "broxy-invalid-stdio-command-987654"),
                )

            val result =
                fetchServerCapabilities(
                    config = config,
                    timeoutSeconds = 1,
                    connectionRetryCount = 1,
                    ignoreHttpsCertificateErrors = false,
                )

            assertTrue(result.isFailure)
        }
}
