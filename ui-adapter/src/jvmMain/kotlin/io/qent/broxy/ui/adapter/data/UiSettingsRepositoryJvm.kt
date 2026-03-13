package io.qent.broxy.ui.adapter.data

import io.qent.broxy.ui.adapter.models.UiSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

internal class JsonUiSettingsRepository(
    baseDir: Path = defaultConfigDir(),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        },
) : UiSettingsRepository {
    private val uiFile: Path = baseDir.resolve("ui.json")

    override fun loadUiSettings(): UiSettings {
        if (!uiFile.exists() || !uiFile.isRegularFile()) {
            return UiSettings()
        }
        val text =
            try {
                Files.readString(uiFile)
            } catch (e: IOException) {
                throw IllegalStateException("Failed to read ui.json: ${e.message}", e)
            }
        return try {
            json.decodeFromString(UiSettings.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalStateException("Invalid ui.json format: ${e.message}", e)
        }
    }

    override fun saveUiSettings(settings: UiSettings) {
        try {
            val dir = uiFile.parent
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir)
            }
            Files.writeString(
                uiFile,
                json.encodeToString(UiSettings.serializer(), settings),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        } catch (e: IOException) {
            throw IllegalStateException("Failed to save ui.json: ${e.message}", e)
        }
    }
}

actual fun provideUiSettingsRepository(): UiSettingsRepository = JsonUiSettingsRepository()
