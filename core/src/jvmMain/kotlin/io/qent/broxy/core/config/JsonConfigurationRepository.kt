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

@Suppress("TooManyFunctions")
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
    private val configFile: Path = dir.resolve(CONFIG_FILE_NAME)
    private val defaultMcpFile: Path = dir.resolve(DEFAULT_MCP_FILE_NAME)
    private var rawSnapshot: RawConfigSnapshot = RawConfigSnapshot.Empty

    override fun loadMcpConfig(): McpServersConfig {
        val appConfig = readAppConfigFile()
        val mcpPath = resolveMcpFilePath(appConfig.mcpFilePath)
        val mcpRoot = readMcpFile(mcpPath)
        val mapped =
            mapper.mapFileToDomain(
                appConfig = appConfig,
                mcpRoot = mcpRoot,
                mcpFileDirectory = mcpPath.parent ?: mcpPath.toAbsolutePath().parent ?: dir,
                defaultMcpFilePath = defaultMcpFile.toAbsolutePath().toString(),
            )
        validator.validate(mapped.config)
        warnUnsafeServerIds(mapped.config)
        rawSnapshot = mapped.snapshot
        return mapped.config
    }

    override fun saveMcpConfig(config: McpServersConfig) {
        validator.validate(config)
        val removedServerIds = removedServerIds(config)
        val appConfig = mapper.mapDomainToAppConfigFile(config)
        val mcpFile = resolveMcpFilePath(appConfig.mcpFilePath)
        val mcpRoot = mapper.mapDomainToMcpFile(config, rawSnapshot)
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir)
            Files.writeString(
                configFile,
                json.encodeToString(FileAppConfig.serializer(), appConfig),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            val mcpDir = mcpFile.parent
            if (mcpDir != null && !Files.exists(mcpDir)) {
                Files.createDirectories(mcpDir)
            }
            Files.writeString(
                mcpFile,
                json.encodeToString(FileMcpRoot.serializer(), mcpRoot),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            logger.info("Saved ${config.servers.size} MCP servers to ${mcpFile.toAbsolutePath()}")
        } catch (e: IOException) {
            errors.fail("Failed to save configuration: ${e.message}", e)
        }
        rawSnapshot = mapper.snapshotFromSave(config, mcpRoot)
        removeAuthState(removedServerIds)
    }

    override fun loadPreset(id: String): Preset {
        validatePathSafePresetId(id).getOrElse { throw it }
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
        validatePathSafePresetId(preset.id).getOrElse { throw it }
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
        validatePathSafePresetId(id).getOrElse { throw it }
        val file = dir.resolve("preset_$id.json")
        try {
            Files.deleteIfExists(file)
        } catch (e: IOException) {
            throw ConfigurationException("Failed to delete preset '$id': ${e.message}", e)
        }
    }

    private fun readAppConfigFile(): FileAppConfig {
        if (!configFile.exists()) {
            return FileAppConfig()
        }
        val text =
            try {
                Files.readString(configFile)
            } catch (e: IOException) {
                errors.fail("Failed to read $CONFIG_FILE_NAME: ${e.message}", e)
            }
        return try {
            json.decodeFromString(FileAppConfig.serializer(), text)
        } catch (e: SerializationException) {
            errors.fail("Invalid $CONFIG_FILE_NAME format: ${e.message}", e)
        }
    }

    private fun readMcpFile(mcpFile: Path): FileMcpRoot {
        if (!mcpFile.exists()) {
            logger.warn("mcp.json not found at ${mcpFile.toAbsolutePath()}, using empty config")
            rawSnapshot = RawConfigSnapshot.Empty
            return FileMcpRoot()
        }
        val text =
            try {
                Files.readString(mcpFile)
            } catch (e: IOException) {
                errors.fail("Failed to read mcp.json: ${e.message}", e)
            }
        return try {
            json.decodeFromString(FileMcpRoot.serializer(), text)
        } catch (e: SerializationException) {
            errors.fail("Invalid mcp.json format: ${e.message}", e)
        }
    }

    private fun resolveMcpFilePath(pathValue: String?): Path {
        val raw = pathValue?.trim().takeUnless { it.isNullOrEmpty() }
        val expanded = raw?.let(::expandHomePath)
        val path =
            if (expanded == null) {
                defaultMcpFile
            } else {
                val candidate = Paths.get(expanded)
                if (candidate.isAbsolute) {
                    candidate
                } else {
                    dir.resolve(expanded)
                }
            }
        return path.normalize()
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
        val previous = rawSnapshot.servers.keys
        if (previous.isEmpty()) return emptySet()
        val current = config.servers.map { it.id }.toSet()
        return previous - current
    }

    private fun removeAuthState(serverIds: Set<String>) {
        if (serverIds.isEmpty()) return
        serverIds.forEach { authStateStore.remove(it) }
    }

    private data class PresetListing(
        val preset: Preset,
        val fileName: String,
    )

    companion object {
        const val CONFIG_FILE_NAME: String = "config.json"
        const val DEFAULT_MCP_FILE_NAME: String = "mcp.json"

        internal fun resolveMcpFilePath(
            baseDir: Path,
            mcpFilePath: String?,
        ): Path {
            val raw = mcpFilePath?.trim().takeUnless { it.isNullOrEmpty() }
            val expanded = raw?.let(::expandHomePath)
            val path =
                if (expanded == null) {
                    baseDir.resolve(DEFAULT_MCP_FILE_NAME)
                } else {
                    val candidate = Paths.get(expanded)
                    if (candidate.isAbsolute) {
                        candidate
                    } else {
                        baseDir.resolve(expanded)
                    }
                }
            return path.normalize()
        }

        internal fun readConfiguredMcpPath(
            baseDir: Path,
            json: Json,
            logger: Logger? = null,
        ): Path {
            val configFile = baseDir.resolve(CONFIG_FILE_NAME)
            if (!configFile.exists()) {
                return baseDir.resolve(DEFAULT_MCP_FILE_NAME).normalize()
            }
            return runCatching {
                val content = Files.readString(configFile)
                val parsed = json.decodeFromString(FileAppConfig.serializer(), content)
                resolveMcpFilePath(baseDir, parsed.mcpFilePath)
            }.onFailure { error ->
                logger?.warn("Failed to read $CONFIG_FILE_NAME for MCP path: ${error.message}", error)
            }.getOrDefault(baseDir.resolve(DEFAULT_MCP_FILE_NAME).normalize())
        }

        private fun expandHomePath(path: String): String {
            val home = System.getProperty("user.home") ?: return path
            return when {
                path == "~" -> home
                path.startsWith("~/") -> Paths.get(home).resolve(path.removePrefix("~/")).toString()
                path.startsWith("~\\") -> Paths.get(home).resolve(path.removePrefix("~\\")).toString()
                else -> path
            }
        }
    }
}
