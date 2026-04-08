package io.qent.broxy.ui.adapter.data

interface ImportedServerHideRepository {
    fun loadHiddenServerKeys(): Set<String>

    fun hideServer(key: String)

    fun clearHiddenServers()

    companion object {
        val Noop: ImportedServerHideRepository =
            object : ImportedServerHideRepository {
                override fun loadHiddenServerKeys(): Set<String> = emptySet()

                override fun hideServer(key: String) {}

                override fun clearHiddenServers() {}
            }
    }
}
