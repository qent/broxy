package io.qent.broxy.agents.infrastructure.persistence

import io.qent.broxy.agents.AgentProviderSettings
import io.qent.broxy.agents.AgentProviderSettingsRepository
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

class JsonAgentProviderSettingsRepository(
    private val baseDir: Path = Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        },
) : AgentProviderSettingsRepository {
    private val storage = agentStorageLayout(baseDir)
    private val settingsFile: Path = storage.settingsFile

    override fun loadSettings(): AgentProviderSettings {
        if (!settingsFile.exists() || !settingsFile.isRegularFile()) {
            return AgentProviderSettings()
        }
        val text =
            try {
                Files.readString(settingsFile)
            } catch (e: IOException) {
                throw ConfigurationException("Failed to read agents_settings.json: ${e.message}", e)
            }
        return try {
            json.decodeFromString(AgentProviderSettings.serializer(), text)
        } catch (e: SerializationException) {
            throw ConfigurationException("Invalid agents_settings.json format: ${e.message}", e)
        }
    }

    override fun saveSettings(settings: AgentProviderSettings) {
        try {
            if (!Files.exists(storage.rootDir)) {
                Files.createDirectories(storage.rootDir)
            }
            Files.writeString(
                settingsFile,
                json.encodeToString(AgentProviderSettings.serializer(), settings),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        } catch (e: IOException) {
            throw ConfigurationException("Failed to save agents_settings.json: ${e.message}", e)
        }
    }
}
