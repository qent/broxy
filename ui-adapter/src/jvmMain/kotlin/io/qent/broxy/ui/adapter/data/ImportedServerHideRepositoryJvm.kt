package io.qent.broxy.ui.adapter.data

import java.util.prefs.Preferences

internal class PreferencesImportedServerHideRepository(
    private val preferences: Preferences = Preferences.userRoot().node(PREFERENCES_NODE),
) : ImportedServerHideRepository {
    private val lock = Any()

    override fun loadHiddenServerKeys(): Set<String> =
        synchronized(lock) {
            val raw = preferences.get(HIDDEN_KEYS_PREF, "")
            if (raw.isBlank()) {
                emptySet()
            } else {
                raw
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
        }

    override fun hideServer(key: String) {
        val normalized = key.trim()
        if (normalized.isEmpty()) return
        synchronized(lock) {
            val keys = loadHiddenServerKeys().toMutableSet()
            if (keys.add(normalized)) {
                preferences.put(HIDDEN_KEYS_PREF, keys.joinToString(separator = "\n"))
                preferences.flush()
            }
        }
    }

    override fun clearHiddenServers() {
        synchronized(lock) {
            preferences.remove(HIDDEN_KEYS_PREF)
            preferences.flush()
        }
    }

    private companion object {
        private const val PREFERENCES_NODE = "io.qent.broxy.ui.importedServers"
        private const val HIDDEN_KEYS_PREF = "hidden"
    }
}

actual fun provideImportedServerHideRepository(): ImportedServerHideRepository = PreferencesImportedServerHideRepository()
