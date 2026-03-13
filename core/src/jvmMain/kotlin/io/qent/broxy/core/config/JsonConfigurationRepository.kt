package io.qent.broxy.core.config

import io.qent.broxy.core.mcp.auth.OAuthStateStore
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.proxy.isSafeNamespaceServerId
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.ConfigurationException
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

class JsonConfigurationRepository(
    baseDir: Path = Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        },
    private val logger: Logger = ConsoleLogger,
    private val envResolver: EnvironmentVariableResolver = EnvironmentVariableResolver(logger = logger),
    private val authStateStore: OAuthStateStore = OAuthStateStore(baseDir = baseDir, logger = logger),
) : ConfigurationRepository {
    private val defaults = ConfigDefaults()
    private val errors = ConfigErrorHandler(logger)
    private val mapper = ConfigMapper(envResolver, logger, errors, defaults)
    private val validator = ConfigValidator(errors)

    private val dir: Path = baseDir
    private val mcpFile: Path = dir.resolve("mcp.json")
    private var rawSnapshot: RawConfigSnapshot = RawConfigSnapshot.Empty

    override fun loadMcpConfig(): McpServersConfig {
        if (!mcpFile.exists()) {
            logger.warn("mcp.json not found at ${mcpFile.toAbsolutePath()}, using empty config")
            rawSnapshot = RawConfigSnapshot.Empty
            return McpServersConfig(emptyList())
        }
        val text =
            try {
                Files.readString(mcpFile)
            } catch (e: IOException) {
                errors.fail("Failed to read mcp.json: ${e.message}", e)
            }
        val root =
            try {
                json.decodeFromString(FileMcpRoot.serializer(), text)
            } catch (e: SerializationException) {
                errors.fail("Invalid mcp.json format: ${e.message}", e)
            }
        val mapped = mapper.mapFileToDomain(root)
        validator.validate(mapped.config)
        warnUnsafeServerIds(mapped.config)
        rawSnapshot = mapped.snapshot
        return mapped.config
    }

    override fun saveMcpConfig(config: McpServersConfig) {
        validator.validate(config)
        val removedServerIds = removedServerIds(config)
        val root = mapper.mapDomainToFile(config, rawSnapshot)
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir)
            Files.writeString(
                mcpFile,
                json.encodeToString(FileMcpRoot.serializer(), root),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            logger.info("Saved ${config.servers.size} MCP servers to ${mcpFile.toAbsolutePath()}")
        } catch (e: IOException) {
            errors.fail("Failed to save mcp.json: ${e.message}", e)
        }
        rawSnapshot = mapper.snapshotFromSave(config, root)
        removeAuthState(removedServerIds)
    }

    override fun loadPreset(id: String): Preset {
        val file = dir.resolve("preset_$id.json")
        if (!file.exists() || !file.isRegularFile()) {
            throw ConfigurationException("Preset '$id' not found at ${file.toAbsolutePath()}")
        }
        val text =
            try {
                Files.readString(file)
            } catch (e: IOException) {
                throw ConfigurationException("Failed to read ${file.name}: ${e.message}", e)
            }
        val preset =
            try {
                json.decodeFromString(Preset.serializer(), text)
            } catch (e: SerializationException) {
                throw ConfigurationException("Invalid preset '${file.name}': ${e.message}", e)
            }
        if (preset.id != id) {
            throw ConfigurationException(
                "Preset file '${file.name}' id '${preset.id}' does not match requested id '$id'",
            )
        }
        return preset
    }

    override fun savePreset(preset: Preset) {
        val file = dir.resolve("preset_${preset.id}.json")
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir)
            Files.writeString(
                file,
                json.encodeToString(Preset.serializer(), preset),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            logger.info("Saved preset '${preset.id}' to ${file.toAbsolutePath()}")
        } catch (e: IOException) {
            errors.fail("Failed to save preset '${preset.id}': ${e.message}", e)
        }
    }

    override fun listPresets(): List<Preset> {
        if (!Files.exists(dir)) return emptyList()
        val result = mutableListOf<PresetListing>()
        Files
            .newDirectoryStream(dir) { p ->
                val n = p.fileName.toString()
                n.startsWith("preset_") && n.endsWith(".json")
            }.use { ds ->
                for (p in ds) {
                    val preset =
                        runCatching { json.decodeFromString(Preset.serializer(), Files.readString(p)) }
                            .getOrElse {
                                logger.warn("Failed to load preset file '${p.fileName}': ${it.message}")
                                null
                            }
                    if (preset != null) {
                        result.add(
                            PresetListing(
                                preset = preset,
                                fileName = p.fileName.toString(),
                            ),
                        )
                    }
                }
            }
        return result
            .sortedWith(
                compareBy<PresetListing> { it.preset.orderIndex }
                    .thenBy { it.fileName },
            ).map { listing -> listing.preset }
    }

    override fun deletePreset(id: String) {
        val file = dir.resolve("preset_$id.json")
        try {
            Files.deleteIfExists(file)
        } catch (e: IOException) {
            throw ConfigurationException("Failed to delete preset '$id': ${e.message}", e)
        }
    }

    private fun warnUnsafeServerIds(config: McpServersConfig) {
        val unsafe = config.servers.map { it.id }.filterNot(::isSafeNamespaceServerId)
        if (unsafe.isNotEmpty()) {
            val message =
                "Detected serverId values with '_' which can break tool namespace parsing: " +
                    "${unsafe.joinToString()}. Existing configs are supported, but new ids should avoid underscores."
            logger.warn(message)
        }
    }

    private fun removedServerIds(config: McpServersConfig): Set<String> {
        val existing =
            if (!Files.exists(mcpFile)) {
                emptySet()
            } else {
                runCatching {
                    val text = Files.readString(mcpFile)
                    json.decodeFromString(FileMcpRoot.serializer(), text).mcpServers.keys
                }.onFailure { error ->
                    logger.warn(
                        "Failed to read existing mcp.json for OAuth cleanup: ${error.message}",
                        error,
                    )
                }.getOrDefault(emptySet())
            }
        val current = config.servers.map { it.id }.toSet()
        return if (existing.isEmpty()) {
            emptySet()
        } else {
            existing - current
        }
    }

    private fun removeAuthState(serverIds: Set<String>) {
        if (serverIds.isEmpty()) return
        serverIds.forEach { authStateStore.remove(it) }
    }

    private data class PresetListing(
        val preset: Preset,
        val fileName: String,
    )
}
