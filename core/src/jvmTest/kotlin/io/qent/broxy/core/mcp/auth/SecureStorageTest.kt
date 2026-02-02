package io.qent.broxy.core.mcp.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecureStorageTest {
    @Test
    fun macKeychainStorage_uses_stdin_and_avoids_secret_logs() {
        val logger = CapturingLogger()
        val capture = CommandCapture()
        val secret = "top-secret"
        val runner =
            CommandRunner { args, input ->
                capture.args = args
                capture.input = input
                CommandResult(exitCode = 1, output = "error $secret")
            }
        val storage =
            MacKeychainStorage(
                serviceName = "broxy",
                logger = logger,
                commandRunner = runner,
                securityPathOverride = "/usr/bin/security",
            )

        storage.write("server-1", secret)

        assertTrue(capture.args.none { it.contains(secret) })
        assertEquals("$secret\n", capture.input)
        assertTrue(logger.messages.none { it.contains(secret) })
        assertTrue(logger.messages.any { it.contains("Output:") })
    }

    @Test
    fun secretToolStorage_uses_stdin_and_avoids_secret_logs() {
        val logger = CapturingLogger()
        val capture = CommandCapture()
        val secret = "top-secret"
        val runner =
            CommandRunner { args, input ->
                capture.args = args
                capture.input = input
                CommandResult(exitCode = 1, output = "error $secret")
            }
        val storage =
            SecretToolStorage(
                serviceName = "broxy",
                logger = logger,
                commandRunner = runner,
                secretToolPathOverride = "/usr/bin/secret-tool",
            )

        storage.write("server-1", secret)

        assertTrue(capture.args.none { it.contains(secret) })
        assertEquals(secret, capture.input)
        assertTrue(logger.messages.none { it.contains(secret) })
        assertTrue(logger.messages.any { it.contains("Output:") })
    }
}

private class CommandCapture {
    var args: List<String> = emptyList()
    var input: String? = null
}
