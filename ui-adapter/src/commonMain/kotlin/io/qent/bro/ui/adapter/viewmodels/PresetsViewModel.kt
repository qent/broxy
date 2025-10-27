package io.qent.bro.ui.adapter.viewmodels

import io.qent.bro.core.models.ToolReference
import io.qent.bro.core.repository.ConfigurationRepository
import io.qent.bro.ui.adapter.data.provideConfigurationRepository
import io.qent.bro.ui.adapter.models.UiPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PresetUiState(
    val showEditor: Boolean = false,
    val editingId: String? = null, // target preset id being edited (null = new)
    val exportJson: String? = null // when not null, show export dialog
)

class PresetsViewModel(
    private val repo: ConfigurationRepository = provideConfigurationRepository()
) {
    private val selections = mutableMapOf<String, List<ToolReference>>()

    // Per-preset UI state
    val uiStates: MutableMap<String, MutableStateFlow<PresetUiState>> = mutableMapOf()

    // Global UI state for adding new preset
    private val _globalUi: MutableStateFlow<PresetUiState> = MutableStateFlow(PresetUiState())
    val globalUi: StateFlow<PresetUiState> = _globalUi

    fun getSelection(presetId: String): List<ToolReference> = selections[presetId] ?: emptyList()

    fun setSelection(presetId: String, tools: List<ToolReference>) { selections[presetId] = tools }

    fun openCreate() { _globalUi.value = _globalUi.value.copy(showEditor = true, editingId = null) }
    fun closeCreate() { _globalUi.value = _globalUi.value.copy(showEditor = false, editingId = null) }

    fun openEdit(id: String) { state(id).value = state(id).value.copy(showEditor = true, editingId = id) }
    fun closeEdit(id: String) { state(id).value = state(id).value.copy(showEditor = false, editingId = null) }

    fun openExport(id: String, json: String) { state(id).value = state(id).value.copy(exportJson = json) }
    fun closeExport(id: String) { state(id).value = state(id).value.copy(exportJson = null) }

    private fun state(id: String) = uiStates.getOrPut(id) { MutableStateFlow(PresetUiState()) }

    fun removePreset(list: MutableList<UiPreset>, id: String) {
        runCatching { repo.deletePreset(id) }
        list.removeAll { it.id == id }
        selections.remove(id)
        uiStates.remove(id)
    }

    fun upsertPreset(list: MutableList<UiPreset>, preset: UiPreset, tools: List<ToolReference>) {
        val idx = list.indexOfFirst { it.id == preset.id }
        val updated = preset.copy(toolsCount = tools.count { it.enabled })
        if (idx >= 0) list[idx] = updated else list.add(updated)
        selections[preset.id] = tools
        runCatching { repo.savePreset(toCorePreset(updated)) }
    }

    fun duplicatePreset(list: MutableList<UiPreset>, sourceId: String): UiPreset? {
        val src = list.firstOrNull { it.id == sourceId } ?: return null
        val baseSlug = slugify(src.name)
        var attempt = 1
        var newId: String
        do {
            newId = if (attempt == 1) "${'$'}{baseSlug}-copy" else "${'$'}{baseSlug}-copy-${'$'}{attempt}"
            attempt++
        } while (list.any { it.id == newId })

        val newName = if (attempt == 2) "${'$'}{src.name} (copy)" else "${'$'}{src.name} (copy ${'$'}{attempt - 1})"
        val newPreset = UiPreset(id = newId, name = newName, description = src.description, toolsCount = 0)
        val tools = selections[sourceId] ?: emptyList()
        upsertPreset(list, newPreset, tools)
        return newPreset
    }

    fun toCorePreset(ui: UiPreset): io.qent.bro.core.models.Preset = io.qent.bro.core.models.Preset(
        id = ui.id,
        name = ui.name,
        description = ui.description ?: "",
        tools = selections[ui.id] ?: emptyList()
    )

    fun exportToJson(ui: UiPreset): String {
        val tools = selections[ui.id] ?: emptyList()
        fun esc(s: String): String = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        val toolsJson = tools.joinToString(",", prefix = "[", postfix = "]") { t ->
            "{" +
                "\"serverId\":\"${'$'}{esc(t.serverId)}\"," +
                "\"toolName\":\"${'$'}{esc(t.toolName)}\"," +
                "\"enabled\":${'$'}{t.enabled}" +
            "}"
        }
        val desc = ui.description ?: ""
        return "{" +
                "\"id\":\"${'$'}{esc(ui.id)}\"," +
                "\"name\":\"${'$'}{esc(ui.name)}\"," +
                "\"description\":\"${'$'}{esc(desc)}\"," +
                "\"tools\":${'$'}toolsJson" +
            "}"
    }

    fun loadIntoState(list: MutableList<UiPreset>) {
        val presets = runCatching { repo.listPresets() }.getOrDefault(emptyList())
        if (presets.isEmpty()) return
        presets.forEach { p ->
            selections[p.id] = p.tools
            val ui = UiPreset(id = p.id, name = p.name, description = p.description.ifBlank { null }, toolsCount = p.tools.count { it.enabled })
            val idx = list.indexOfFirst { it.id == ui.id }
            if (idx >= 0) list[idx] = ui else list.add(ui)
        }
    }
}

fun slugify(name: String): String = name.trim().lowercase()
    .replace("[\\s_]+".toRegex(), "-")
    .replace("[^a-z0-9-]".toRegex(), "")
    .trim('-')

