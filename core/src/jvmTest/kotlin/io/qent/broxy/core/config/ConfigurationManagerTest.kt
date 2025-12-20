package io.qent.broxy.core.config

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.repository.ConfigurationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigurationManagerTest {
    @Test
    fun renameServer_updates_presets_and_servers() {
        val repo = InMemoryRepo()
        val manager = ConfigurationManager(repo, ConfigTestLogger)
        val config =
            McpServersConfig(
                servers = listOf(testServer("old")),
            )
        val preset =
            Preset(
                id = "p1",
                name = "Preset",
                tools = listOf(ToolReference(serverId = "old", toolName = "t1")),
                prompts = listOf(PromptReference(serverId = "old", promptName = "p1")),
                resources = listOf(ResourceReference(serverId = "old", resourceKey = "r1")),
            )
        repo.presets = mutableListOf(preset)

        val result = manager.renameServer(config, oldId = "old", server = testServer("new"))

        assertTrue(result.isSuccess)
        assertEquals(listOf("new"), result.getOrThrow().config.servers.map { it.id })
        assertNull(result.getOrThrow().presetMigrationError)
        assertEquals(1, repo.savedPresets.size)
        val updated = repo.savedPresets.single()
        assertEquals("new", updated.tools.single().serverId)
        assertEquals("new", updated.prompts?.single()?.serverId)
        assertEquals("new", updated.resources?.single()?.serverId)
    }

    @Test
    fun updateDefaultPresetId_clears_blank_value() {
        val repo = InMemoryRepo()
        val manager = ConfigurationManager(repo, ConfigTestLogger)
        val config = McpServersConfig(defaultPresetId = "old")

        val result = manager.updateDefaultPresetId(config, "  ")

        assertTrue(result.isSuccess)
        assertEquals(null, repo.savedConfig?.defaultPresetId)
    }
}

private class InMemoryRepo : ConfigurationRepository {
    var savedConfig: McpServersConfig? = null
    var presets: MutableList<Preset> = mutableListOf()
    val savedPresets = mutableListOf<Preset>()

    override fun loadMcpConfig(): McpServersConfig = savedConfig ?: McpServersConfig()

    override fun saveMcpConfig(config: McpServersConfig) {
        savedConfig = config
    }

    override fun loadPreset(id: String): Preset = presets.first { it.id == id }

    override fun savePreset(preset: Preset) {
        savedPresets += preset
        val idx = presets.indexOfFirst { it.id == preset.id }
        if (idx >= 0) {
            presets[idx] = preset
        } else {
            presets += preset
        }
    }

    override fun listPresets(): List<Preset> = presets

    override fun deletePreset(id: String) {
        presets.removeAll { it.id == id }
    }
}

private fun testServer(id: String): McpServerConfig =
    McpServerConfig(
        id = id,
        name = "Server $id",
        transport = TransportConfig.StdioTransport(command = "noop"),
    )
