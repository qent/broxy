package io.qent.broxy.ui.adapter.data

import java.util.prefs.Preferences

internal class PreferencesImportedServerInstallRepository(
    private val preferences: Preferences = Preferences.userRoot().node(PREFERENCES_NODE),
) : ImportedServerInstallRepository {
    private val lock = Any()

    override fun loadInstalledMappings(): Map<String, String> =
        synchronized(lock) {
            val raw = preferences.get(INSTALLED_MAPPINGS_PREF, "")
            if (raw.isBlank()) {
                emptyMap()
            } else {
                raw
                    .lineSequence()
                    .mapNotNull { line ->
                        val separatorIndex = line.indexOf(MAPPING_SEPARATOR)
                        if (separatorIndex <= 0 || separatorIndex == line.lastIndex) {
                            null
                        } else {
                            val key = line.substring(0, separatorIndex).trim()
                            val serverId = line.substring(separatorIndex + 1).trim()
                            if (key.isEmpty() || serverId.isEmpty()) {
                                null
                            } else {
                                key to serverId
                            }
                        }
                    }.toMap()
            }
        }

    override fun saveInstalledMapping(
        importKey: String,
        serverId: String,
    ) {
        val normalizedKey = importKey.trim()
        val normalizedServerId = serverId.trim()
        if (normalizedKey.isEmpty() || normalizedServerId.isEmpty()) return
        synchronized(lock) {
            val mappings = loadInstalledMappings().toMutableMap()
            if (mappings[normalizedKey] == normalizedServerId) return
            mappings[normalizedKey] = normalizedServerId
            val serialized =
                mappings.entries.joinToString(separator = "\n") { (key, value) ->
                    "$key$MAPPING_SEPARATOR$value"
                }
            preferences.put(INSTALLED_MAPPINGS_PREF, serialized)
            preferences.flush()
        }
    }

    private companion object {
        private const val PREFERENCES_NODE = "io.qent.broxy.ui.importedServers"
        private const val INSTALLED_MAPPINGS_PREF = "installed"
        private const val MAPPING_SEPARATOR = '\t'
    }
}

actual fun provideImportedServerInstallRepository(): ImportedServerInstallRepository = PreferencesImportedServerInstallRepository()
