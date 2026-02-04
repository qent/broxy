package io.qent.broxy.core.mcp.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecureStorageTest {
    @Test
    fun macKeychainStorage_uses_argument_write_and_verifies_without_logging_secret() {
        val logger = CapturingLogger()
        val capture = CommandCapture()
        val secret = "top-secret"
        val results =
            ArrayDeque(
                listOf(
                    CommandResult(exitCode = 0, output = ""),
                    CommandResult(exitCode = 0, output = secret),
                ),
            )
        val runner =
            CommandRunner { args, input ->
                capture.calls += CommandCall(args = args, input = input)
                if (results.isEmpty()) {
                    CommandResult(exitCode = 1, output = "error $secret")
                } else {
                    results.removeFirst()
                }
            }
        val storage =
            MacKeychainStorage(
                serviceName = "broxy",
                logger = logger,
                commandRunner = runner,
                securityPathOverride = "/usr/bin/security",
            )

        storage.write("server-1", secret)

        assertTrue(capture.calls.isNotEmpty())
        val first = capture.calls.first()
        assertTrue(first.args.any { it == "add-generic-password" })
        assertTrue(first.args.any { it == "-w" })
        assertTrue(first.args.any { it == secret })
        assertEquals(null, first.input)
        assertTrue(capture.calls.any { it.args.any { arg -> arg == "find-generic-password" } })
        assertTrue(logger.messages.none { it.contains(secret) })
    }

    @Test
    fun secretToolStorage_uses_stdin_and_avoids_secret_logs() {
        val logger = CapturingLogger()
        val capture = CommandCapture()
        val secret = "top-secret"
        val runner =
            CommandRunner { args, input ->
                capture.calls += CommandCall(args = args, input = input)
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

        assertTrue(capture.calls.isNotEmpty())
        val first = capture.calls.first()
        assertTrue(first.args.none { it.contains(secret) })
        assertEquals(secret, first.input)
        assertTrue(logger.messages.none { it.contains(secret) })
        assertTrue(logger.messages.any { it.contains("Output:") })
    }

    @Test
    fun macKeychainStorage_falls_back_when_verification_fails() {
        val logger = CapturingLogger()
        val capture = CommandCapture()
        val secret = "top-secret"
        val results =
            ArrayDeque(
                listOf(
                    CommandResult(exitCode = 0, output = ""),
                    CommandResult(exitCode = 0, output = ""),
                    CommandResult(exitCode = 0, output = ""),
                    CommandResult(exitCode = 0, output = ""),
                    CommandResult(exitCode = 0, output = ""),
                    CommandResult(exitCode = 0, output = secret),
                ),
            )
        val runner =
            CommandRunner { args, input ->
                capture.calls += CommandCall(args = args, input = input)
                if (results.isEmpty()) {
                    CommandResult(exitCode = 1, output = "error $secret")
                } else {
                    results.removeFirst()
                }
            }
        val storage =
            MacKeychainStorage(
                serviceName = "broxy",
                logger = logger,
                commandRunner = runner,
                securityPathOverride = "/usr/bin/security",
            )

        storage.write("server-1", secret)

        val addCommands = capture.calls.filter { it.args.any { arg -> arg == "add-generic-password" } }
        assertTrue(addCommands.size >= 2)
        assertTrue(addCommands.first().args.any { it == "-U" })
        assertTrue(addCommands.any { call -> call.args.none { it == "-U" } })
        assertTrue(capture.calls.any { it.args.any { arg -> arg == "delete-generic-password" } })
        assertTrue(logger.messages.none { it.contains(secret) })
    }
}

private data class CommandCall(
    val args: List<String>,
    val input: String?,
)

private class CommandCapture {
    val calls = mutableListOf<CommandCall>()
}
