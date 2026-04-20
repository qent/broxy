package io.qent.broxy.ui.adapter.data

interface ImportedServerInstallRepository {
    fun loadInstalledMappings(): Map<String, String>

    fun saveInstalledMapping(
        importKey: String,
        serverId: String,
    )

    companion object {
        val Noop: ImportedServerInstallRepository =
            object : ImportedServerInstallRepository {
                override fun loadInstalledMappings(): Map<String, String> = emptyMap()

                override fun saveInstalledMapping(
                    importKey: String,
                    serverId: String,
                ) {}
            }
    }
}
