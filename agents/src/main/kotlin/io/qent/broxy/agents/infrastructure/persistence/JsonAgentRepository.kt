@file:Suppress(
    "TooManyFunctions",
    "MaxLineLength",
)

package io.qent.broxy.agents.infrastructure.persistence

import io.qent.broxy.agents.AgentDefinition
import io.qent.broxy.agents.AgentProviderSettings
import io.qent.broxy.agents.AgentRepository
import io.qent.broxy.agents.resolveAgentsDirectory
import io.qent.broxy.core.utils.ConfigurationException
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

class JsonAgentRepository(
    private val baseDir: Path = Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        },
    private val markdownCodec: ClaudeSubagentMarkdownCodec = ClaudeSubagentMarkdownCodec(),
) : AgentRepository {
    private val storage = agentStorageLayout(baseDir)

    override fun listAgents(): List<AgentDefinition> {
        val definitionsDir = resolveDefinitionsDir()
        if (!Files.exists(definitionsDir)) {
            return emptyList()
        }
        val result = mutableListOf<AgentDefinition>()
        Files
            .newDirectoryStream(definitionsDir) { path ->
                path.isRegularFile() && path.name.endsWith(MARKDOWN_FILE_SUFFIX)
            }.use { stream ->
                for (path in stream) {
                    val id = resolveIdFromPath(path) ?: continue
                    runCatching { decode(path, id) }
                        .onSuccess { result += it }
                }
            }
        return result.sortedWith(
            compareBy<AgentDefinition> { it.orderIndex }
                .thenBy { it.id },
        )
    }

    override fun loadAgent(id: String): AgentDefinition {
        val normalizedId = normalizeId(id)
        val definitionsDir = resolveDefinitionsDir()
        val file = storage.agentMarkdownFile(definitionsDir, normalizedId)
        if (!file.exists() || !file.isRegularFile()) {
            throw ConfigurationException("Agent '$normalizedId' not found at ${file.toAbsolutePath()}")
        }
        return decode(file, normalizedId)
    }

    override fun saveAgent(agent: AgentDefinition) {
        val normalizedId = normalizeId(agent.id)
        val normalizedAgent =
            agent.copy(
                id = normalizedId,
                name = agent.name.trim(),
                systemPrompt = agent.systemPrompt.trim(),
                description = agent.description?.trim()?.takeIf { it.isNotBlank() },
            )
        val definitionsDir = resolveDefinitionsDir()
        val file = storage.agentMarkdownFile(definitionsDir, normalizedId)
        val existingFrontmatter =
            if (file.exists() && file.isRegularFile()) {
                runCatching {
                    val text = Files.readString(file)
                    markdownCodec.decode(text, file.name).frontmatter
                }.getOrDefault(linkedMapOf())
            } else {
                linkedMapOf()
            }
        try {
            if (!Files.exists(definitionsDir)) {
                Files.createDirectories(definitionsDir)
            }
            Files.writeString(
                file,
                markdownCodec.encode(normalizedAgent, existingFrontmatter),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            saveSidecar(normalizedAgent)
        } catch (e: IOException) {
            throw ConfigurationException("Failed to save agent '${normalizedAgent.id}': ${e.message}", e)
        }
    }

    override fun deleteAgent(id: String) {
        val normalizedId = normalizeId(id)
        val definitionsDir = resolveDefinitionsDir()
        val file = storage.agentMarkdownFile(definitionsDir, normalizedId)
        val sidecar = storage.agentSidecarFile(normalizedId)
        try {
            Files.deleteIfExists(file)
            Files.deleteIfExists(sidecar)
        } catch (e: IOException) {
            throw ConfigurationException("Failed to delete agent '$normalizedId': ${e.message}", e)
        }
    }

    private fun decode(
        path: Path,
        id: String,
    ): AgentDefinition {
        val text =
            try {
                Files.readString(path)
            } catch (e: IOException) {
                throw ConfigurationException("Failed to read ${path.name}: ${e.message}", e)
            }
        val parsed = markdownCodec.decode(text, path.name)
        val sidecar = loadSidecar(id)
        return AgentDefinition(
            id = id,
            name = parsed.name,
            systemPrompt = parsed.body,
            description = parsed.description,
            tools = sidecar.tools,
            agentTools = sidecar.agentTools,
            prompts = sidecar.prompts,
            resources = sidecar.resources,
            claudeTools = parsed.tools,
            claudeDisallowedTools = parsed.disallowedTools,
            claudePermissionMode = parsed.permissionMode,
            claudeMcpServers = parsed.mcpServers,
            orderIndex = sidecar.orderIndex,
            schedule = sidecar.schedule,
            manualLaunchDefaults = sidecar.manualLaunchDefaults,
        )
    }

    private fun loadSidecar(id: String): AgentSidecarMetadata {
        val file = storage.agentSidecarFile(id)
        if (!file.exists() || !file.isRegularFile()) {
            return AgentSidecarMetadata()
        }
        val text =
            try {
                Files.readString(file)
            } catch (e: IOException) {
                throw ConfigurationException("Failed to read ${file.name}: ${e.message}", e)
            }
        return try {
            json.decodeFromString(AgentSidecarMetadata.serializer(), text)
        } catch (e: SerializationException) {
            throw ConfigurationException("Invalid agent sidecar file '${file.name}': ${e.message}", e)
        }
    }

    private fun saveSidecar(agent: AgentDefinition) {
        val payload =
            AgentSidecarMetadata(
                tools = agent.tools,
                agentTools = agent.agentTools,
                prompts = agent.prompts,
                resources = agent.resources,
                orderIndex = agent.orderIndex,
                schedule = agent.schedule,
                manualLaunchDefaults = agent.manualLaunchDefaults,
            )
        val file = storage.agentSidecarFile(agent.id)
        try {
            if (!Files.exists(storage.rootDir)) {
                Files.createDirectories(storage.rootDir)
            }
            if (!Files.exists(storage.metadataDir)) {
                Files.createDirectories(storage.metadataDir)
            }
            Files.writeString(
                file,
                json.encodeToString(AgentSidecarMetadata.serializer(), payload),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        } catch (e: IOException) {
            throw ConfigurationException("Failed to save agent sidecar '${agent.id}': ${e.message}", e)
        }
    }

    private fun resolveDefinitionsDir(): Path = loadProviderSettings().resolveAgentsDirectory(storage.defaultDefinitionsDir)

    private fun loadProviderSettings(): AgentProviderSettings {
        val file = storage.settingsFile
        if (!file.exists() || !file.isRegularFile()) {
            return AgentProviderSettings()
        }
        val text =
            try {
                Files.readString(file)
            } catch (e: IOException) {
                throw ConfigurationException("Failed to read agents_settings.json: ${e.message}", e)
            }
        return try {
            json.decodeFromString(AgentProviderSettings.serializer(), text)
        } catch (e: SerializationException) {
            throw ConfigurationException("Invalid agents_settings.json format: ${e.message}", e)
        }
    }

    private fun resolveIdFromPath(path: Path): String? {
        val fileName = path.name
        if (!fileName.endsWith(MARKDOWN_FILE_SUFFIX)) {
            return null
        }
        val stem = fileName.removeSuffix(MARKDOWN_FILE_SUFFIX).trim()
        return stem.takeIf { it.isNotBlank() }
    }

    private fun normalizeId(id: String): String {
        val normalized = id.trim()
        require(normalized.isNotBlank()) { "Agent id cannot be blank" }
        return normalized
    }

    private companion object {
        private const val MARKDOWN_FILE_SUFFIX = ".md"
    }
}
