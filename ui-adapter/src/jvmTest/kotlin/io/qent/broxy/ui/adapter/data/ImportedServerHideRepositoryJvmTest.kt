package io.qent.broxy.ui.adapter.data

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportedServerHideRepositoryJvmTest {
    @Test
    fun hideServer_ignores_blank_and_deduplicates_keys() {
        val preferences = testPreferences("hide")
        val repository = PreferencesImportedServerHideRepository(preferences)

        repository.hideServer("  ")
        repository.hideServer("client::server")
        repository.hideServer(" client::server ")

        assertEquals(setOf("client::server"), repository.loadHiddenServerKeys())
    }

    @Test
    fun clearHiddenServers_removes_all_saved_keys() {
        val preferences = testPreferences("clear")
        val repository = PreferencesImportedServerHideRepository(preferences)

        repository.hideServer("a::1")
        repository.hideServer("b::2")
        assertTrue(repository.loadHiddenServerKeys().isNotEmpty())

        repository.clearHiddenServers()

        assertTrue(repository.loadHiddenServerKeys().isEmpty())
    }

    private fun testPreferences(suffix: String): Preferences =
        Preferences.userRoot().node("io/qent/broxy/tests/$suffix/${UUID.randomUUID()}")
}
