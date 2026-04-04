package io.qent.broxy.ui.adapter.data

import io.qent.broxy.ui.adapter.models.UiSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UiSettingsRepositoryJvmTest {
    @Test
    fun loadUiSettings_returns_defaults_when_file_missing() {
        val baseDir = Files.createTempDirectory("broxy-ui-settings-missing")
        val repository = JsonUiSettingsRepository(baseDir = baseDir)

        val settings = repository.loadUiSettings()

        assertEquals(UiSettings(), settings)
    }

    @Test
    fun saveUiSettings_persists_and_loadUiSettings_restores_values() {
        val baseDir = Files.createTempDirectory("broxy-ui-settings-roundtrip")
        val repository = JsonUiSettingsRepository(baseDir = baseDir)

        repository.saveUiSettings(UiSettings(showTrayIcon = false))
        val loaded = repository.loadUiSettings()

        assertEquals(false, loaded.showTrayIcon)
    }

    @Test
    fun loadUiSettings_throws_for_invalid_json() {
        val baseDir = Files.createTempDirectory("broxy-ui-settings-invalid")
        val uiFile = baseDir.resolve("ui.json")
        Files.createDirectories(baseDir)
        Files.writeString(uiFile, "{invalid json}")
        val repository = JsonUiSettingsRepository(baseDir = baseDir)

        assertFailsWith<IllegalStateException> {
            repository.loadUiSettings()
        }
    }

    @Test
    fun defaultConfigDir_points_to_user_config_folder() {
        val dir = defaultConfigDir()
        assertEquals(true, dir.toString().endsWith(".config/broxy"))
    }
}
