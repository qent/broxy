package io.qent.broxy.agents.infrastructure.secrets

import io.qent.broxy.agents.AgentSecretsStore
import io.qent.broxy.agents.LlmProvider
import io.qent.broxy.agents.infrastructure.persistence.agentStorageLayout
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

private const val COMMAND_TIMEOUT_SECONDS = 10L
private const val KEY_OPENAI = "openai_api_key"
private const val KEY_ANTHROPIC = "anthropic_api_key"
private const val KEY_LM_STUDIO = "lm_studio_api_key"

private class HybridAgentSecretsStore(
    private val secureStorage: SecureStorage,
    private val fallbackStore: FileAgentSecretsStore,
    private val logger: Logger = ConsoleLogger,
) : AgentSecretsStore {
    override fun loadApiKey(provider: LlmProvider): String? {
        val key = mapProviderKey(provider)
        val secure = secureStorage.read(key)
        if (!secure.isNullOrBlank()) {
            return secure
        }
        return fallbackStore.read(key)
    }

    override fun saveApiKey(
        provider: LlmProvider,
        apiKey: String,
    ) {
        val key = mapProviderKey(provider)
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            clearApiKey(provider)
            return
        }

        var savedInSecureStorage = false
        if (secureStorage.isAvailable) {
            savedInSecureStorage =
                runCatching {
                    secureStorage.write(key, trimmed)
                    true
                }.onFailure {
                    logger.warn("Failed to write provider secret to secure storage: ${it.message}")
                }.getOrDefault(false)
        }

        if (!savedInSecureStorage) {
            fallbackStore.write(key, trimmed)
        } else {
            fallbackStore.delete(key)
        }
    }

    override fun clearApiKey(provider: LlmProvider) {
        val key = mapProviderKey(provider)
        runCatching { secureStorage.delete(key) }
        fallbackStore.delete(key)
    }

    private fun mapProviderKey(provider: LlmProvider): String =
        when (provider) {
            LlmProvider.OPENAI -> KEY_OPENAI
            LlmProvider.ANTHROPIC -> KEY_ANTHROPIC
            LlmProvider.LM_STUDIO -> KEY_LM_STUDIO
        }
}

fun defaultAgentSecretsStore(
    baseDir: Path = Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    logger: Logger = ConsoleLogger,
): AgentSecretsStore {
    val storage = agentStorageLayout(baseDir)
    val serviceName = buildServiceName(storage.rootDir)
    val secureStorage = SecureStorageFactory.create(serviceName, logger)
    val fallback = FileAgentSecretsStore(baseDir = baseDir, logger = logger)
    return HybridAgentSecretsStore(secureStorage, fallback, logger)
}

@Serializable
private data class AgentSecretsFile(
    val values: Map<String, String> = emptyMap(),
)

class FileAgentSecretsStore(
    private val baseDir: Path,
    private val logger: Logger = ConsoleLogger,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        },
) {
    private val storage = agentStorageLayout(baseDir)
    private val file: Path = storage.secretsFile

    fun read(key: String): String? = load().values[key]?.takeIf { it.isNotBlank() }

    fun write(
        key: String,
        value: String,
    ) {
        val current = load().values.toMutableMap()
        current[key] = value
        save(AgentSecretsFile(current))
    }

    fun delete(key: String) {
        val current = load().values.toMutableMap()
        if (current.remove(key) != null) {
            save(AgentSecretsFile(current))
        }
    }

    private fun load(): AgentSecretsFile {
        if (!file.exists() || !file.isRegularFile()) {
            return AgentSecretsFile()
        }
        return runCatching {
            json.decodeFromString(AgentSecretsFile.serializer(), Files.readString(file))
        }.onFailure {
            logger.warn("Failed to decode agents_secrets.json: ${it.message}")
        }.getOrDefault(AgentSecretsFile())
    }

    private fun save(payload: AgentSecretsFile) {
        try {
            if (!Files.exists(storage.rootDir)) {
                Files.createDirectories(storage.rootDir)
            }
            Files.writeString(
                file,
                json.encodeToString(AgentSecretsFile.serializer(), payload),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        } catch (e: IOException) {
            logger.warn("Failed to save agents_secrets.json: ${e.message}", e)
        }
    }
}

private interface SecureStorage {
    val isAvailable: Boolean

    fun read(key: String): String?

    fun write(
        key: String,
        value: String,
    )

    fun delete(key: String)
}

private object SecureStorageFactory {
    fun create(
        serviceName: String,
        logger: Logger,
    ): SecureStorage =
        when (detectOsFamily()) {
            OsFamily.Mac -> MacKeychainStorage(serviceName, logger)
            OsFamily.Linux -> SecretToolStorage(serviceName, logger)
            OsFamily.Windows,
            OsFamily.Other,
            -> UnavailableSecureStorage
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

private object UnavailableSecureStorage : SecureStorage {
    override val isAvailable: Boolean = false

    override fun read(key: String): String? = null

    override fun write(
        key: String,
        value: String,
    ) = Unit

    override fun delete(key: String) = Unit
}

private class MacKeychainStorage(
    private val serviceName: String,
    private val logger: Logger,
) : SecureStorage {
    private val securityCommand: String? = resolveCommandPath("security", listOf("/usr/bin/security"))

    override val isAvailable: Boolean = securityCommand != null

    override fun read(key: String): String? {
        val command = securityCommand ?: return null
        val result =
            runCommand(
                listOf(
                    command,
                    "find-generic-password",
                    "-a",
                    key,
                    "-s",
                    serviceName,
                    "-w",
                ),
                null,
            )
        return if (result.exitCode == 0) {
            result.output.trim().takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    override fun write(
        key: String,
        value: String,
    ) {
        val command = securityCommand ?: return
        val update =
            runCommand(
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
                null,
            )
        if (update.exitCode != 0) {
            logger.warn("Failed to write provider secret to Keychain for '$key'")
        }
    }

    override fun delete(key: String) {
        val command = securityCommand ?: return
        runCommand(
            listOf(
                command,
                "delete-generic-password",
                "-a",
                key,
                "-s",
                serviceName,
            ),
            null,
        )
    }
}

private class SecretToolStorage(
    private val serviceName: String,
    private val logger: Logger,
) : SecureStorage {
    private val secretTool: String? =
        resolveCommandPath(
            "secret-tool",
            listOf("/usr/bin/secret-tool", "/usr/local/bin/secret-tool"),
        )

    override val isAvailable: Boolean = secretTool != null

    override fun read(key: String): String? {
        val command = secretTool ?: return null
        val result = runCommand(listOf(command, "lookup", "service", serviceName, "account", key), null)
        return if (result.exitCode == 0) {
            result.output.trim().takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    override fun write(
        key: String,
        value: String,
    ) {
        val command = secretTool ?: return
        val result =
            runCommand(
                listOf(
                    command,
                    "store",
                    "--label=Broxy Agent Key",
                    "service",
                    serviceName,
                    "account",
                    key,
                ),
                value,
            )
        if (result.exitCode != 0) {
            logger.warn("Failed to write provider secret using secret-tool for '$key'")
        }
    }

    override fun delete(key: String) {
        val command = secretTool ?: return
        runCommand(listOf(command, "clear", "service", serviceName, "account", key), null)
    }
}

private data class CommandResult(
    val exitCode: Int,
    val output: String,
)

@Suppress("TooGenericExceptionCaught")
private fun runCommand(
    args: List<String>,
    input: String?,
): CommandResult =
    try {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
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
        CommandResult(exitCode, output)
    } catch (e: Exception) {
        CommandResult(-1, e.message.orEmpty())
    }

private fun resolveCommandPath(
    command: String,
    fallbacks: List<String> = emptyList(),
): String? {
    var resolved: String? = null
    if (command.contains('/') || command.contains('\\')) {
        val path = Paths.get(command)
        resolved = if (Files.isRegularFile(path) && Files.isExecutable(path)) path.toString() else null
    }
    if (resolved == null) {
        val pathEnv = System.getenv("PATH").orEmpty()
        if (pathEnv.isNotBlank()) {
            val pathFromEnv =
                pathEnv
                    .split(File.pathSeparatorChar)
                    .asSequence()
                    .map { Paths.get(it, command) }
                    .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            if (pathFromEnv != null) {
                resolved = pathFromEnv.toAbsolutePath().toString()
            }
        }
    }
    if (resolved == null) {
        val fallbackPath =
            fallbacks.firstOrNull { candidate ->
                val path = Paths.get(candidate)
                Files.isRegularFile(path) && Files.isExecutable(path)
            }
        resolved = fallbackPath
    }
    return resolved
}

private fun buildServiceName(baseDir: Path): String {
    val normalized = baseDir.toAbsolutePath().normalize().toString()
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(StandardCharsets.UTF_8))
    val hex = digest.joinToString(separator = "") { "%02x".format(it) }
    return "broxy.agents.$hex"
}
