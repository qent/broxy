package io.qent.broxy.core.mcp.auth

import io.qent.broxy.core.utils.Logger
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface SecureStorage {
    val isAvailable: Boolean

    fun read(key: String): String?

    fun write(
        key: String,
        value: String,
    )

    fun delete(key: String)
}

internal object SecureStorageFactory {
    fun create(
        serviceName: String,
        logger: Logger,
    ): SecureStorage {
        val storage =
            when (detectOsFamily()) {
                OsFamily.Mac -> MacKeychainStorage(serviceName, logger)
                OsFamily.Linux -> SecretToolStorage(serviceName, logger)
                OsFamily.Windows -> UnavailableSecureStorage(logger, "Windows secure storage is not available.")
                OsFamily.Other -> UnavailableSecureStorage(logger, "Unsupported OS for secure storage.")
            }
        return if (storage.isAvailable) storage else UnavailableSecureStorage(logger, "Secure storage is unavailable.")
    }
}

private enum class OsFamily {
    Mac,
    Linux,
    Windows,
    Other,
}

private fun detectOsFamily(): OsFamily {
    val name = System.getProperty("os.name").lowercase(Locale.ROOT)
    return when {
        "mac" in name -> OsFamily.Mac
        "win" in name -> OsFamily.Windows
        "nux" in name || "nix" in name || "linux" in name -> OsFamily.Linux
        else -> OsFamily.Other
    }
}

internal data class CommandResult(
    val exitCode: Int,
    val output: String,
)

internal fun interface CommandRunner {
    fun run(
        args: List<String>,
        input: String?,
    ): CommandResult
}

private const val COMMAND_TIMEOUT_SECONDS = 10L
private const val REDACTED_OUTPUT = "<redacted>"

private fun runCommand(
    args: List<String>,
    input: String? = null,
): CommandResult =
    try {
        val process =
            ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
        if (input != null) {
            process.outputStream.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) }
        } else {
            process.outputStream.close()
        }
        val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val exitCode = if (finished) process.exitValue() else -1
        CommandResult(exitCode = exitCode, output = output)
    } catch (ex: IOException) {
        CommandResult(exitCode = -1, output = ex.message.orEmpty())
    } catch (ex: InterruptedException) {
        Thread.currentThread().interrupt()
        CommandResult(exitCode = -1, output = ex.message.orEmpty())
    }

private fun sanitizeCommandOutput(
    output: String,
    sensitiveValues: List<String>,
): String {
    if (output.isBlank()) return output
    var sanitized = output
    sensitiveValues.filter { it.isNotBlank() }.forEach { value ->
        sanitized = sanitized.replace(value, "***")
    }
    return if (sanitized == output) REDACTED_OUTPUT else sanitized
}

private class SecureCommandExecutor(
    private val logger: Logger,
    private val runner: CommandRunner,
) {
    fun run(
        args: List<String>,
        input: String?,
        failureMessage: String,
        redactions: List<String> = emptyList(),
        ignoredExitCodes: Set<Int> = emptySet(),
    ): CommandResult {
        val result = runner.run(args, input)
        if (result.exitCode != 0 && !ignoredExitCodes.contains(result.exitCode)) {
            val sanitized = sanitizeCommandOutput(result.output, redactions)
            val details = if (sanitized.isBlank()) "" else " Output: $sanitized"
            logger.warn("$failureMessage (exit ${result.exitCode}).$details")
        }
        return result
    }
}

private fun resolveCommandPath(
    command: String,
    fallbacks: List<String> = emptyList(),
): String? {
    var resolved: String? = null
    if (command.contains('/') || command.contains('\\')) {
        val path = Paths.get(command)
        resolved = if (isExecutable(path)) path.toAbsolutePath().toString() else null
    } else {
        resolved = findInPath(command)
        if (resolved == null) {
            resolved = fallbacks.firstOrNull { isExecutable(Paths.get(it)) }
        }
    }
    return resolved
}

private fun findInPath(command: String): String? {
    val pathValue = System.getenv("PATH")?.takeIf { it.isNotBlank() } ?: return null
    return pathValue
        .split(File.pathSeparatorChar)
        .asSequence()
        .map { Paths.get(it, command) }
        .firstOrNull { isExecutable(it) }
        ?.toAbsolutePath()
        ?.toString()
}

private fun isExecutable(path: Path): Boolean = Files.isRegularFile(path) && Files.isExecutable(path)

private class UnavailableSecureStorage(
    private val logger: Logger,
    private val reason: String,
) : SecureStorage {
    override val isAvailable: Boolean = false
    private val warned = AtomicBoolean(false)

    override fun read(key: String): String? {
        warnOnce()
        return null
    }

    override fun write(
        key: String,
        value: String,
    ) {
        warnOnce()
    }

    override fun delete(key: String) {
        warnOnce()
    }

    private fun warnOnce() {
        if (warned.compareAndSet(false, true)) {
            logger.warn("OAuth secure storage disabled: $reason")
        }
    }
}

internal class MacKeychainStorage(
    private val serviceName: String,
    private val logger: Logger,
    private val commandRunner: CommandRunner = CommandRunner(::runCommand),
    private val securityPathOverride: String? = null,
) : SecureStorage {
    private val securityPath: String? =
        securityPathOverride ?: resolveCommandPath("security", fallbacks = listOf("/usr/bin/security"))
    private val commandExecutor = SecureCommandExecutor(logger, commandRunner)
    override val isAvailable: Boolean = securityPath != null

    override fun read(key: String): String? {
        val command = securityPath ?: return null
        val result =
            commandExecutor.run(
                listOf(
                    command,
                    "find-generic-password",
                    "-a",
                    key,
                    "-s",
                    serviceName,
                    "-w",
                ),
                input = null,
                failureMessage = "Failed to read OAuth entry from Keychain for '$key'",
                ignoredExitCodes = setOf(KEYCHAIN_NOT_FOUND_EXIT),
            )
        return when {
            result.exitCode == 0 -> {
                val value = result.output.trimEnd()
                if (value.isBlank()) {
                    logger.warn("OAuth Keychain entry for '$key' is empty; treating as missing.")
                    null
                } else {
                    value
                }
            }
            result.exitCode == KEYCHAIN_NOT_FOUND_EXIT -> null
            else -> null
        }
    }

    override fun write(
        key: String,
        value: String,
    ) {
        val command = securityPath ?: return
        for (strategy in buildStrategies(command, key, value)) {
            if (strategy.deleteFirst) {
                delete(key)
            }
            if (writeStrategy(strategy, key, value)) {
                return
            }
        }
    }

    private fun buildStrategies(
        command: String,
        key: String,
        value: String,
    ): List<KeychainWriteStrategy> =
        listOf(
            KeychainWriteStrategy(
                label = "update-with-arg",
                args =
                    listOf(
                        command,
                        "add-generic-password",
                        "-a",
                        key,
                        "-s",
                        serviceName,
                        "-U",
                        "-w",
                        value,
                    ),
                input = null,
                redactions = listOf(value),
                deleteFirst = false,
            ),
            KeychainWriteStrategy(
                label = "recreate-with-arg",
                args =
                    listOf(
                        command,
                        "add-generic-password",
                        "-a",
                        key,
                        "-s",
                        serviceName,
                        "-w",
                        value,
                    ),
                input = null,
                redactions = listOf(value),
                deleteFirst = true,
            ),
            KeychainWriteStrategy(
                label = "update-with-stdin",
                args =
                    listOf(
                        command,
                        "add-generic-password",
                        "-a",
                        key,
                        "-s",
                        serviceName,
                        "-U",
                        "-w",
                    ),
                input = "$value\n",
                redactions = listOf(value, "$value\n"),
                deleteFirst = false,
            ),
        )

    private fun writeStrategy(
        strategy: KeychainWriteStrategy,
        key: String,
        value: String,
    ): Boolean {
        val result =
            commandExecutor.run(
                strategy.args,
                input = strategy.input,
                failureMessage =
                    "Failed to store OAuth entry in Keychain for '$key' using ${strategy.label}",
                redactions = strategy.redactions,
            )
        if (result.exitCode != 0) return false
        val stored = read(key)
        val success = stored == value
        if (!success) {
            logger.warn(
                "OAuth Keychain entry verification failed for '$key' using ${strategy.label}; retrying.",
            )
            delete(key)
        }
        return success
    }

    override fun delete(key: String) {
        val command = securityPath ?: return
        val result =
            commandExecutor.run(
                listOf(
                    command,
                    "delete-generic-password",
                    "-a",
                    key,
                    "-s",
                    serviceName,
                ),
                input = null,
                failureMessage = "Failed to delete OAuth entry from Keychain for '$key'",
                ignoredExitCodes = setOf(KEYCHAIN_NOT_FOUND_EXIT),
            )
        if (result.exitCode != 0 && result.exitCode != KEYCHAIN_NOT_FOUND_EXIT) return
    }

    companion object {
        private const val KEYCHAIN_NOT_FOUND_EXIT = 44
    }
}

private data class KeychainWriteStrategy(
    val label: String,
    val args: List<String>,
    val input: String?,
    val redactions: List<String>,
    val deleteFirst: Boolean,
)

internal class SecretToolStorage(
    private val serviceName: String,
    private val logger: Logger,
    private val commandRunner: CommandRunner = CommandRunner(::runCommand),
    private val secretToolPathOverride: String? = null,
) : SecureStorage {
    private val secretToolPath: String? =
        secretToolPathOverride
            ?: resolveCommandPath(
                "secret-tool",
                fallbacks = listOf("/usr/bin/secret-tool", "/usr/local/bin/secret-tool"),
            )
    private val commandExecutor = SecureCommandExecutor(logger, commandRunner)
    override val isAvailable: Boolean = secretToolPath != null

    override fun read(key: String): String? {
        val command = secretToolPath ?: return null
        val result =
            commandExecutor.run(
                listOf(
                    command,
                    "lookup",
                    "service",
                    serviceName,
                    "account",
                    key,
                ),
                input = null,
                failureMessage = "Failed to read OAuth entry from Secret Service for '$key'",
                ignoredExitCodes = setOf(SECRET_TOOL_NOT_FOUND_EXIT),
            )
        return when {
            result.exitCode == 0 -> result.output.trimEnd()
            result.exitCode == SECRET_TOOL_NOT_FOUND_EXIT -> null
            else -> null
        }
    }

    override fun write(
        key: String,
        value: String,
    ) {
        val command = secretToolPath ?: return
        val result =
            commandExecutor.run(
                listOf(
                    command,
                    "store",
                    "--label=broxy oauth $key",
                    "service",
                    serviceName,
                    "account",
                    key,
                ),
                input = value,
                failureMessage = "Failed to store OAuth entry in Secret Service for '$key'",
                redactions = listOf(value),
            )
        if (result.exitCode != 0) return
    }

    override fun delete(key: String) {
        val command = secretToolPath ?: return
        val result =
            commandExecutor.run(
                listOf(
                    command,
                    "clear",
                    "service",
                    serviceName,
                    "account",
                    key,
                ),
                input = null,
                failureMessage = "Failed to delete OAuth entry from Secret Service for '$key'",
                ignoredExitCodes = setOf(SECRET_TOOL_NOT_FOUND_EXIT),
            )
        if (result.exitCode != 0 && result.exitCode != SECRET_TOOL_NOT_FOUND_EXIT) return
    }

    companion object {
        private const val SECRET_TOOL_NOT_FOUND_EXIT = 1
    }
}
