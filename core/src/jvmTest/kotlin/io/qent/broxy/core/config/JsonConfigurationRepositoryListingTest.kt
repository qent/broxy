package io.qent.broxy.core.config

import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonConfigurationRepositoryListingTest {
    @Test
    fun list_presets_returns_sorted_and_skips_invalid_files() {
        val tempDir = Files.createTempDirectory("broxy-presets")
        val json =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            }
        val repo = JsonConfigurationRepository(baseDir = tempDir, json = json, logger = NoopLogger)

        val presetA = Preset(id = "a", name = "Preset A", orderIndex = 1)
        val presetB = Preset(id = "b", name = "Preset B", orderIndex = 0)
        Files.writeString(tempDir.resolve("preset_a.json"), json.encodeToString(Preset.serializer(), presetA))
        Files.writeString(tempDir.resolve("preset_b.json"), json.encodeToString(Preset.serializer(), presetB))
        Files.writeString(tempDir.resolve("preset_bad.json"), "{invalid")

        val presets = repo.listPresets()

        assertEquals(2, presets.size)
        assertEquals("b", presets.first().id)
        assertEquals("a", presets.last().id)
        assertEquals(listOf(0, 1), presets.map { it.orderIndex })
    }

    @Test
    fun delete_preset_removes_file() {
        val tempDir = Files.createTempDirectory("broxy-presets")
        val json =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            }
        val repo = JsonConfigurationRepository(baseDir = tempDir, json = json, logger = NoopLogger)

        val preset = Preset(id = "delete-me", name = "Preset")
        val file = tempDir.resolve("preset_delete-me.json")
        Files.writeString(file, json.encodeToString(Preset.serializer(), preset))
        assertTrue(file.exists())

        repo.deletePreset("delete-me")

        assertFalse(file.exists())
    }

    private object NoopLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
}
