package io.qent.broxy.ui.adapter.presetmanagement

import io.qent.broxy.core.capabilities.PersistedCapabilityCacheEntry
import io.qent.broxy.core.capabilities.PersistedCapabilityCacheStore
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.presetmanagement.CreatePresetRequest
import io.qent.broxy.core.presetmanagement.NamedPresetManagementItem
import io.qent.broxy.core.presetmanagement.PresetToolSelection
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPresetManagementBackendTest {
    @Test
    fun createPreset_refreshes_presets_in_running_app_context() =
        runTest {
            val repository =
                FakeConfigurationRepository(
                    config =
                        McpServersConfig(
                            servers = listOf(server("s1", "Server 1")),
                        ),
                    presets = mutableListOf(),
                )
            var refreshCalls = 0
            val backend =
                DesktopPresetManagementBackend(
                    configurationRepository = repository,
                    liveCapabilitiesProvider = { emptyMap<String, ServerCapabilities>() },
                    capabilityCacheStore = FakeCapabilityCacheStore(),
                    logger = NoopLogger,
                    configuredServersProvider = { repository.loadMcpConfig().servers },
                    savedPresetNamesProvider = { repository.listPresets().map { NamedPresetManagementItem(it.id, it.name) } },
                    refreshPresetListAfterCreate = { refreshCalls += 1 },
                )

            backend.createPreset(
                CreatePresetRequest(
                    presetId = "new-preset",
                    presetName = "New preset",
                    tools = listOf(PresetToolSelection(serverId = "s1", toolName = "tool")),
                ),
            )

            assertEquals(1, refreshCalls)
            assertEquals("new-preset", repository.listPresets().single().id)
        }

    private fun server(
        id: String,
        name: String,
    ): McpServerConfig =
        McpServerConfig(
            id = id,
            name = name,
            transport = TransportConfig.StdioTransport(command = "noop"),
        )

    private object NoopLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
}

private class FakeConfigurationRepository(
    private var config: McpServersConfig,
    private val presets: MutableList<Preset>,
) : ConfigurationRepository {
    override fun loadMcpConfig(): McpServersConfig = config

    override fun saveMcpConfig(config: McpServersConfig) {
        this.config = config
    }

    override fun loadPreset(id: String): Preset = presets.firstOrNull { it.id == id } ?: error("Preset '$id' not found")

    override fun savePreset(preset: Preset) {
        presets.removeAll { it.id == preset.id }
        presets += preset
    }

    override fun listPresets(): List<Preset> = presets.toList()

    override fun deletePreset(id: String) {
        presets.removeAll { it.id == id }
    }
}

private class FakeCapabilityCacheStore : PersistedCapabilityCacheStore {
    override fun loadAll(): List<PersistedCapabilityCacheEntry> = emptyList()

    override fun save(entry: PersistedCapabilityCacheEntry) = Unit

    override fun remove(serverId: String) = Unit

    override fun retain(validIds: Set<String>) = Unit
}
