package io.qent.broxy.core.mcp.auth

import io.qent.broxy.core.utils.Logger
import java.nio.charset.StandardCharsets
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

internal class InMemorySecureStorage : SecureStorage {
    override val isAvailable: Boolean = true
    private val data = mutableMapOf<String, String>()

    override fun read(key: String): String? = data[key]

    override fun write(
        key: String,
        value: String,
    ) {
        data[key] = value
    }

    override fun delete(key: String) {
        data.remove(key)
    }
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

private data class CommandResult(
    val exitCode: Int,
    val output: String,
)

private const val COMMAND_TIMEOUT_SECONDS = 10L

private fun runCommand(
    args: List<String>,
    input: String? = null,
): CommandResult {
    return try {
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
    } catch (ex: Exception) {
        CommandResult(exitCode = -1, output = "")
    }
}

private fun commandExists(command: String): Boolean {
    val result = runCommand(listOf("which", command))
    return result.exitCode == 0 && result.output.isNotBlank()
}

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

private class MacKeychainStorage(
    private val serviceName: String,
    private val logger: Logger,
) : SecureStorage {
    override val isAvailable: Boolean = commandExists("security")

    override fun read(key: String): String? {
        if (!isAvailable) return null
        val result =
            runCommand(
                listOf(
                    "security",
                    "find-generic-password",
                    "-a",
                    key,
                    "-s",
                    serviceName,
                    "-w",
                ),
            )
        return when {
            result.exitCode == 0 -> result.output.trimEnd()
            result.exitCode == KEYCHAIN_NOT_FOUND_EXIT -> null
            else -> {
                logger.warn("Failed to read OAuth entry from Keychain for '$key' (exit ${result.exitCode}).")
                null
            }
        }
    }

    override fun write(
        key: String,
        value: String,
    ) {
        if (!isAvailable) return
        val result =
            runCommand(
                listOf(
                    "security",
                    "add-generic-password",
                    "-a",
                    key,
                    "-s",
                    serviceName,
                    "-w",
                    value,
                    "-U",
                ),
            )
        if (result.exitCode != 0) {
            logger.warn("Failed to store OAuth entry in Keychain for '$key' (exit ${result.exitCode}).")
        }
    }

    override fun delete(key: String) {
        if (!isAvailable) return
        val result =
            runCommand(
                listOf(
                    "security",
                    "delete-generic-password",
                    "-a",
                    key,
                    "-s",
                    serviceName,
                ),
            )
        if (result.exitCode != 0 && result.exitCode != KEYCHAIN_NOT_FOUND_EXIT) {
            logger.warn("Failed to delete OAuth entry from Keychain for '$key' (exit ${result.exitCode}).")
        }
    }

    companion object {
        private const val KEYCHAIN_NOT_FOUND_EXIT = 44
    }
}

private class SecretToolStorage(
    private val serviceName: String,
    private val logger: Logger,
) : SecureStorage {
    override val isAvailable: Boolean = commandExists("secret-tool")

    override fun read(key: String): String? {
        if (!isAvailable) return null
        val result =
            runCommand(
                listOf(
                    "secret-tool",
                    "lookup",
                    "service",
                    serviceName,
                    "account",
                    key,
                ),
            )
        return when {
            result.exitCode == 0 -> result.output.trimEnd()
            result.exitCode == SECRET_TOOL_NOT_FOUND_EXIT -> null
            else -> {
                logger.warn("Failed to read OAuth entry from Secret Service for '$key' (exit ${result.exitCode}).")
                null
            }
        }
    }

    override fun write(
        key: String,
        value: String,
    ) {
        if (!isAvailable) return
        val result =
            runCommand(
                listOf(
                    "secret-tool",
                    "store",
                    "--label=broxy oauth $key",
                    "service",
                    serviceName,
                    "account",
                    key,
                ),
                input = value,
            )
        if (result.exitCode != 0) {
            logger.warn("Failed to store OAuth entry in Secret Service for '$key' (exit ${result.exitCode}).")
        }
    }

    override fun delete(key: String) {
        if (!isAvailable) return
        val result =
            runCommand(
                listOf(
                    "secret-tool",
                    "clear",
                    "service",
                    serviceName,
                    "account",
                    key,
                ),
            )
        if (result.exitCode != 0 && result.exitCode != SECRET_TOOL_NOT_FOUND_EXIT) {
            logger.warn("Failed to delete OAuth entry from Secret Service for '$key' (exit ${result.exitCode}).")
        }
    }

    companion object {
        private const val SECRET_TOOL_NOT_FOUND_EXIT = 1
    }
}
