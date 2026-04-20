package io.qent.broxy.core.models

object BuiltInPresetResolver {
    private val builtInsInOrder: List<Preset> =
        listOf(
            Preset.empty(),
            Preset.allEnabled(),
            Preset.presetManagement(),
        )

    private val byId: Map<String, Preset> = builtInsInOrder.associateBy { it.id }

    val builtInIds: Set<String>
        get() = byId.keys

    fun listBuiltIns(): List<Preset> = builtInsInOrder

    fun resolve(id: String?): Preset? {
        val normalized = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return byId[normalized]
    }

    fun isBuiltIn(id: String?): Boolean = resolve(id) != null
}
