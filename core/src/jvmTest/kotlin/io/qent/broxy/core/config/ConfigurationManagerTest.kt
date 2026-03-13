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
import kotlin.test.assertNotNull
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
        assertEquals(
            listOf("new"),
            result
                .getOrThrow()
                .config.servers
                .map { it.id },
        )
        assertNull(result.getOrThrow().presetMigrationError)
        assertEquals(1, repo.savedPresets.size)
        val updated = repo.savedPresets.single()
        assertEquals("new", updated.tools.single().serverId)
        assertEquals("new", updated.prompts?.single()?.serverId)
        assertEquals("new", updated.resources?.single()?.serverId)
    }

    @Test
    fun renameServer_merges_existing_server_and_preserves_other_servers() {
        val repo = InMemoryRepo()
        val manager = ConfigurationManager(repo, ConfigTestLogger)
        val config =
            McpServersConfig(
                servers = listOf(testServer("old"), testServer("new"), testServer("keep")),
            )
        val presetOld =
            Preset(
                id = "p1",
                name = "Preset 1",
                tools = listOf(ToolReference(serverId = "old", toolName = "t1")),
                prompts = listOf(PromptReference(serverId = "old", promptName = "p1")),
                resources = listOf(ResourceReference(serverId = "old", resourceKey = "r1")),
            )
        val presetNew =
            Preset(
                id = "p2",
                name = "Preset 2",
                tools = listOf(ToolReference(serverId = "new", toolName = "t2")),
            )
        repo.presets = mutableListOf(presetOld, presetNew)

        val result = manager.renameServer(config, oldId = "old", server = testServer("new").copy(name = "Renamed"))

        assertTrue(result.isSuccess)
        val updatedConfig = result.getOrThrow().config
        assertEquals(2, updatedConfig.servers.size)
        assertTrue(updatedConfig.servers.any { it.id == "new" && it.name == "Renamed" })
        assertTrue(updatedConfig.servers.any { it.id == "keep" })
        assertEquals(null, result.getOrThrow().presetMigrationError)
        assertEquals(1, repo.savedPresets.size)
        val updatedPreset = repo.savedPresets.single()
        assertEquals("p1", updatedPreset.id)
        assertEquals("new", updatedPreset.tools.single().serverId)
        assertEquals("new", updatedPreset.prompts?.single()?.serverId)
        assertEquals("new", updatedPreset.resources?.single()?.serverId)
        assertEquals(2, repo.presets.size)
        val preservedPreset = repo.presets.first { it.id == "p2" }
        assertEquals("new", preservedPreset.tools.single().serverId)
    }

    @Test
    fun updateDefaultPresetId_clears_blank_value() {
        val repo = InMemoryRepo()
        val manager = ConfigurationManager(repo, ConfigTestLogger)
        val config = McpServersConfig(defaultPresetId = "old")

        val result = manager.settings.updateDefaultPresetId(config, "  ")

        assertTrue(result.isSuccess)
        assertEquals(null, repo.savedConfig?.defaultPresetId)
    }

    @Test
    fun upsertServer_rejects_new_id_with_underscore() {
        val repo = InMemoryRepo()
        val manager = ConfigurationManager(repo, ConfigTestLogger)
        val config = McpServersConfig()

        val result = manager.upsertServer(config, testServer("bad_id"))

        assertTrue(result.isFailure)
        assertNull(repo.savedConfig)
    }

    @Test
    fun upsertServer_allows_existing_id_with_underscore() {
        val repo = InMemoryRepo()
        val manager = ConfigurationManager(repo, ConfigTestLogger)
        val existing = testServer("bad_id")
        val config = McpServersConfig(servers = listOf(existing))

        val result = manager.upsertServer(config, existing.copy(name = "Updated"))

        assertTrue(result.isSuccess)
        assertNotNull(repo.savedConfig)
    }

    @Test
    fun renameServer_rejects_new_id_with_underscore() {
        val repo = InMemoryRepo()
        val manager = ConfigurationManager(repo, ConfigTestLogger)
        val config = McpServersConfig(servers = listOf(testServer("good")))

        val result = manager.renameServer(config, oldId = "good", server = testServer("bad_id"))

        assertTrue(result.isFailure)
        assertNull(repo.savedConfig)
    }

    @Test
    fun reorderServers_updates_saved_server_order() {
        val repo = InMemoryRepo()
        val manager = ConfigurationManager(repo, ConfigTestLogger)
        val config =
            McpServersConfig(
                servers = listOf(testServer("s1"), testServer("s2"), testServer("s3")),
            )

        val result = manager.reorderServers(config, orderedServerIds = listOf("s3", "s1", "s2"))

        assertTrue(result.isSuccess)
        assertEquals(listOf("s3", "s1", "s2"), result.getOrThrow().servers.map { it.id })
        assertEquals(listOf("s3", "s1", "s2"), repo.savedConfig?.servers?.map { it.id })
    }

    @Test
    fun reorderPresets_rewrites_orderIndex_and_persists_order() {
        val repo = InMemoryRepo()
        val manager = ConfigurationManager(repo, ConfigTestLogger)
        val p1 = Preset(id = "p1", name = "Preset 1", orderIndex = 0, tools = emptyList())
        val p2 = Preset(id = "p2", name = "Preset 2", orderIndex = 1, tools = emptyList())
        val p3 = Preset(id = "p3", name = "Preset 3", orderIndex = 2, tools = emptyList())
        repo.presets = mutableListOf(p1, p2, p3)

        val result = manager.reorderPresets(orderedPresetIds = listOf("p3", "p1", "p2"))

        assertTrue(result.isSuccess)
        val reordered = result.getOrThrow()
        assertEquals(listOf("p3", "p1", "p2"), reordered.map { it.id })
        assertEquals(listOf(0, 1, 2), reordered.map { it.orderIndex })
        assertEquals(listOf("p3", "p1", "p2"), repo.savedPresets.map { it.id })
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
