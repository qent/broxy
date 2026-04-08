package io.qent.broxy.ui.adapter.data

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportedServerInstallRepositoryJvmTest {
    @Test
    fun saveInstalledMapping_persists_and_updates_mapping() {
        val preferences = testPreferences("install")
        val repository = PreferencesImportedServerInstallRepository(preferences)

        repository.saveInstalledMapping("client::server", "srv-1")
        repository.saveInstalledMapping("client::server", "srv-2")

        assertEquals(mapOf("client::server" to "srv-2"), repository.loadInstalledMappings())
    }

    @Test
    fun saveInstalledMapping_ignores_blank_values() {
        val preferences = testPreferences("blank")
        val repository = PreferencesImportedServerInstallRepository(preferences)

        repository.saveInstalledMapping(" ", "server")
        repository.saveInstalledMapping("key", " ")

        assertTrue(repository.loadInstalledMappings().isEmpty())
    }

    @Test
    fun loadInstalledMappings_skips_invalid_rows() {
        val preferences = testPreferences("invalid")
        preferences.put(
            "installed",
            listOf(
                "invalid-row-without-separator",
                "key-only\t",
                "\tkey-missing",
                "valid\tkey-1",
            ).joinToString("\n"),
        )
        val repository = PreferencesImportedServerInstallRepository(preferences)

        assertEquals(mapOf("valid" to "key-1"), repository.loadInstalledMappings())
    }

    private fun testPreferences(suffix: String): Preferences =
        Preferences.userRoot().node("io/qent/broxy/tests/$suffix/${UUID.randomUUID()}")
}
