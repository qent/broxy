package io.qent.broxy.core.config

import io.qent.broxy.core.models.*
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.Logger
import kotlin.test.*

class ConfigurationManagerTest {
    @Test
    fun renameServer_updates_preset_references() {
        val config = McpServersConfig(
            servers = listOf(
                McpServerConfig(
                    id = "old-id",
                    name = "Old",
                    transport = TransportConfig.StdioTransport(command = "old")
                ),
                McpServerConfig(
                    id = "other-id",
                    name = "Other",
                    transport = TransportConfig.StdioTransport(command = "other")
                )
            )
        )
        val presetWithRefs = Preset(
            id = "preset-1",
            name = "Preset 1",
            tools = listOf(
                ToolReference(serverId = "old-id", toolName = "tool-a", enabled = true),
                ToolReference(serverId = "other-id", toolName = "tool-b", enabled = true)
            ),
            prompts = listOf(
                PromptReference(serverId = "old-id", promptName = "prompt-a", enabled = true)
            ),
            resources = listOf(
                ResourceReference(serverId = "old-id", resourceKey = "resource-a", enabled = true)
            )
        )
        val presetWithoutRefs = Preset(
            id = "preset-2",
            name = "Preset 2",
            tools = listOf(
                ToolReference(serverId = "other-id", toolName = "tool-c", enabled = true)
            )
        )
        val repository = InMemoryConfigurationRepository(
            config = config,
            presets = mutableMapOf(
                presetWithRefs.id to presetWithRefs,
                presetWithoutRefs.id to presetWithoutRefs
            )
        )
        val manager = ConfigurationManager(repository, NoopLogger())

        val result = manager.renameServer(
            config = config,
            oldId = "old-id",
            server = McpServerConfig(
                id = "new-id",
                name = "New",
                transport = TransportConfig.StdioTransport(command = "new")
            )
        )

        assertTrue(result.isSuccess)
        val renamed = result.getOrNull()
        assertNotNull(renamed)
        assertNull(renamed.presetMigrationError)

        val updatedConfig = renamed.config
        assertTrue(updatedConfig.servers.any { it.id == "new-id" })
        assertTrue(updatedConfig.servers.none { it.id == "old-id" })

        val updatedPreset = repository.loadPreset("preset-1")
        assertTrue(updatedPreset.tools.any { it.serverId == "new-id" })
        assertTrue(updatedPreset.tools.none { it.serverId == "old-id" })
        assertTrue(updatedPreset.prompts.orEmpty().any { it.serverId == "new-id" })
        assertTrue(updatedPreset.prompts.orEmpty().none { it.serverId == "old-id" })
        assertTrue(updatedPreset.resources.orEmpty().any { it.serverId == "new-id" })
        assertTrue(updatedPreset.resources.orEmpty().none { it.serverId == "old-id" })

        assertEquals(presetWithoutRefs, repository.loadPreset("preset-2"))
    }

    @Test
    fun renameServer_reports_preset_migration_error() {
        val config = McpServersConfig(
            servers = listOf(
                McpServerConfig(
                    id = "old-id",
                    name = "Old",
                    transport = TransportConfig.StdioTransport(command = "old")
                )
            )
        )
        val preset = Preset(
            id = "preset-1",
            name = "Preset 1",
            tools = listOf(
                ToolReference(serverId = "old-id", toolName = "tool-a", enabled = true)
            )
        )
        val repository = InMemoryConfigurationRepository(
            config = config,
            presets = mutableMapOf(preset.id to preset),
            failPresetSave = true
        )
        val manager = ConfigurationManager(repository, NoopLogger())

        val result = manager.renameServer(
            config = config,
            oldId = "old-id",
            server = McpServerConfig(
                id = "new-id",
                name = "New",
                transport = TransportConfig.StdioTransport(command = "new")
            )
        )

        assertTrue(result.isSuccess)
        val renameResult = result.getOrNull()
        assertNotNull(renameResult)
        assertNotNull(renameResult.presetMigrationError)
        assertTrue(renameResult.config.servers.any { it.id == "new-id" })
    }

    private class InMemoryConfigurationRepository(
        private var config: McpServersConfig,
        private val presets: MutableMap<String, Preset>,
        private val failPresetSave: Boolean = false
    ) : ConfigurationRepository {
        override fun loadMcpConfig(): McpServersConfig = config

        override fun saveMcpConfig(config: McpServersConfig) {
            this.config = config
        }

        override fun loadPreset(id: String): Preset =
            presets[id] ?: throw IllegalStateException("Preset $id not found")

        override fun savePreset(preset: Preset) {
            if (failPresetSave) {
                throw IllegalStateException("Preset save failed")
            }
            presets[preset.id] = preset
        }

        override fun listPresets(): List<Preset> = presets.values.toList()

        override fun deletePreset(id: String) {
            presets.remove(id)
        }
    }

    private class NoopLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String, throwable: Throwable?) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
