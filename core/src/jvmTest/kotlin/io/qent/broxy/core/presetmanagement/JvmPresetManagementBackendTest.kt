package io.qent.broxy.core.presetmanagement

import io.qent.broxy.core.capabilities.PersistedCapabilityCacheEntry
import io.qent.broxy.core.capabilities.PersistedCapabilityCacheStore
import io.qent.broxy.core.capabilities.toPersistedSnapshot
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class JvmPresetManagementBackendTest {
    @Test
    fun list_preset_names_returns_builtins_first() =
        runTest {
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(),
                    presets =
                        mutableListOf(
                            Preset(id = "p-one", name = "One", orderIndex = 1),
                            Preset(id = "p-two", name = "Two", orderIndex = 2),
                        ),
                )
            val backend = backend(repository = repository)

            val names = backend.listPresetNames().presets

            assertEquals(Preset.EMPTY_PRESET_ID, names[0].id)
            assertEquals(Preset.ALL_ENABLED_PRESET_ID, names[1].id)
            assertEquals(Preset.PRESET_MANAGEMENT_ID, names[2].id)
            assertEquals(listOf("p-one", "p-two"), names.drop(3).map { it.id })
        }

    @Test
    fun get_server_description_requires_server_id_when_name_is_ambiguous() =
        runTest {
            val servers =
                listOf(
                    server(id = "alpha-a", name = "Alpha"),
                    server(id = "alpha-b", name = "Alpha"),
                )
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(servers = servers),
                    presets = mutableListOf(),
                )
            val backend = backend(repository = repository)

            val failure =
                runCatching {
                    backend.getServerDescription(ServerDescriptionRequest(serverName = "Alpha"))
                }.exceptionOrNull() ?: fail("Expected ambiguity error")

            val ambiguity = assertIs<PresetManagementAmbiguityException>(failure)
            assertEquals(listOf("alpha-a", "alpha-b"), ambiguity.candidates.map { it.id })
        }

    @Test
    fun get_preset_description_requires_preset_id_when_name_is_ambiguous() =
        runTest {
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(),
                    presets =
                        mutableListOf(
                            Preset(id = "team-1", name = "Team"),
                            Preset(id = "team-2", name = "Team"),
                        ),
                )
            val backend = backend(repository = repository)

            val failure =
                runCatching {
                    backend.getPresetDescription(PresetDescriptionRequest(presetName = "Team"))
                }.exceptionOrNull() ?: fail("Expected ambiguity error")

            val ambiguity = assertIs<PresetManagementAmbiguityException>(failure)
            assertEquals(listOf("team-1", "team-2"), ambiguity.candidates.map { it.id })
        }

    @Test
    fun create_preset_rejects_reserved_duplicate_empty_tools_and_unsafe_ids() =
        runTest {
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(),
                    presets = mutableListOf(Preset(id = "existing", name = "Existing")),
                )
            val backend = backend(repository = repository)

            assertTrue(
                runCatching {
                    backend.createPreset(
                        CreatePresetRequest(
                            presetId = Preset.PRESET_MANAGEMENT_ID,
                            presetName = "Reserved",
                            tools = listOf(PresetToolSelection(serverId = "s1", toolName = "t1")),
                        ),
                    )
                }.exceptionOrNull() is PresetManagementException,
            )
            assertTrue(
                runCatching {
                    backend.createPreset(
                        CreatePresetRequest(
                            presetId = "existing",
                            presetName = "Duplicate",
                            tools = listOf(PresetToolSelection(serverId = "s1", toolName = "t1")),
                        ),
                    )
                }.exceptionOrNull() is PresetManagementException,
            )
            assertTrue(
                runCatching {
                    backend.createPreset(
                        CreatePresetRequest(
                            presetId = "../unsafe",
                            presetName = "Unsafe",
                            tools = listOf(PresetToolSelection(serverId = "s1", toolName = "t1")),
                        ),
                    )
                }.exceptionOrNull() is PresetManagementException,
            )
            assertTrue(
                runCatching {
                    backend.createPreset(
                        CreatePresetRequest(
                            presetId = "empty-tools",
                            presetName = "Empty",
                            tools = emptyList(),
                        ),
                    )
                }.exceptionOrNull() is PresetManagementException,
            )
        }

    @Test
    fun create_preset_persists_minimal_structure_and_appends_order() =
        runTest {
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(),
                    presets =
                        mutableListOf(
                            Preset(id = "a", name = "A", orderIndex = 2),
                            Preset(id = "b", name = "B", orderIndex = 7),
                        ),
                )
            val backend = backend(repository = repository)

            backend.createPreset(
                CreatePresetRequest(
                    presetId = "created",
                    presetName = "Created",
                    tools =
                        listOf(
                            PresetToolSelection(serverId = "s1", toolName = "tool-a"),
                            PresetToolSelection(serverId = "s1", toolName = "tool-a"),
                        ),
                ),
            )

            val persisted = repository.loadPreset("created")
            assertEquals("Created", persisted.name)
            assertEquals(8, persisted.orderIndex)
            assertEquals(listOf(ToolReference(serverId = "s1", toolName = "tool-a", enabled = true)), persisted.tools)
            assertEquals(emptyList(), persisted.prompts)
            assertEquals(emptyList(), persisted.resources)
        }

    @Test
    fun get_server_description_uses_live_then_cached_then_missing_sources() =
        runTest {
            val servers =
                listOf(
                    server(id = "live", name = "Live"),
                    server(id = "cached", name = "Cached"),
                    server(id = "missing", name = "Missing"),
                )
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(servers = servers),
                    presets = mutableListOf(),
                )
            val cacheStore =
                FakeCapabilityCacheStore(
                    entries =
                        mutableListOf(
                            PersistedCapabilityCacheEntry(
                                serverId = "cached",
                                timestampMillis = 10,
                                snapshot = sampleCapabilities("cached-tool").toPersistedSnapshot("cached", "Cached"),
                            ),
                        ),
                )
            val backend =
                backend(
                    repository = repository,
                    liveCapabilitiesProvider = { mapOf("live" to sampleCapabilities("live-tool")) },
                    cacheStore = cacheStore,
                )

            val live = backend.getServerDescription(ServerDescriptionRequest(serverName = "Live"))
            val cached = backend.getServerDescription(ServerDescriptionRequest(serverName = "Cached"))
            val missing = backend.getServerDescription(ServerDescriptionRequest(serverName = "Missing"))

            assertEquals(CapabilitySourceStatus.Live, live.capabilitiesSource)
            assertEquals(CapabilitySourceStatus.Cached, cached.capabilitiesSource)
            assertEquals(CapabilitySourceStatus.Missing, missing.capabilitiesSource)
            assertEquals(listOf("live-tool"), live.tools.map { it.name })
            assertEquals(listOf("cached-tool"), cached.tools.map { it.name })
            assertTrue(missing.tools.isEmpty())
        }

    @Test
    fun get_preset_description_respects_null_vs_empty_prompt_resource_semantics() =
        runTest {
            val servers = listOf(server(id = "s1", name = "Server 1"))
            val nullPreset =
                Preset(
                    id = "null-semantics",
                    name = "Null Semantics",
                    tools = listOf(ToolReference(serverId = "s1", toolName = "tool-1")),
                    prompts = null,
                    resources = null,
                )
            val emptyPreset =
                Preset(
                    id = "empty-semantics",
                    name = "Empty Semantics",
                    tools = listOf(ToolReference(serverId = "s1", toolName = "tool-1")),
                    prompts = emptyList(),
                    resources = emptyList(),
                )
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(servers = servers),
                    presets = mutableListOf(nullPreset, emptyPreset),
                )
            val backend =
                backend(
                    repository = repository,
                    liveCapabilitiesProvider = {
                        mapOf("s1" to sampleCapabilities("tool-1", "prompt-a", "resource://a"))
                    },
                )

            val withNull = backend.getPresetDescription(PresetDescriptionRequest(presetName = "Null Semantics"))
            val withEmpty = backend.getPresetDescription(PresetDescriptionRequest(presetName = "Empty Semantics"))

            assertEquals(listOf("tool-1"), withNull.tools.map { it.name })
            assertEquals(listOf("prompt-a"), withNull.prompts.map { it.name })
            assertEquals(listOf("resource://a"), withNull.resources.map { it.key })
            assertEquals(listOf("tool-1"), withEmpty.tools.map { it.name })
            assertTrue(withEmpty.prompts.isEmpty())
            assertTrue(withEmpty.resources.isEmpty())
        }

    @Test
    fun get_preset_description_reports_missing_capabilities() =
        runTest {
            val servers = listOf(server(id = "s1", name = "Server 1"))
            val preset =
                Preset(
                    id = "missing",
                    name = "Missing",
                    tools = listOf(ToolReference(serverId = "s1", toolName = "absent-tool")),
                    prompts = listOf(PromptReference(serverId = "s1", promptName = "absent-prompt")),
                    resources = listOf(ResourceReference(serverId = "s1", resourceKey = "absent-resource")),
                )
            val repository =
                FakeConfigurationRepository(
                    config = McpServersConfig(servers = servers),
                    presets = mutableListOf(preset),
                )
            val backend =
                backend(
                    repository = repository,
                    liveCapabilitiesProvider = {
                        mapOf("s1" to sampleCapabilities("present-tool", "present-prompt", "resource://ok"))
                    },
                )

            val description = backend.getPresetDescription(PresetDescriptionRequest(presetName = "Missing"))

            assertTrue(description.tools.isEmpty())
            assertTrue(description.prompts.isEmpty())
            assertTrue(description.resources.isEmpty())
            assertEquals(
                setOf("tool:absent-tool", "prompt:absent-prompt", "resource:absent-resource"),
                description.missingCapabilities.map { "${it.type}:${it.key}" }.toSet(),
            )
        }

    private fun backend(
        repository: ConfigurationRepository,
        liveCapabilitiesProvider: () -> Map<String, ServerCapabilities> = { emptyMap() },
        cacheStore: PersistedCapabilityCacheStore = FakeCapabilityCacheStore(),
    ): JvmPresetManagementBackend =
        JvmPresetManagementBackend(
            configurationRepository = repository,
            liveCapabilitiesProvider = liveCapabilitiesProvider,
            capabilityCacheStore = cacheStore,
            logger = NoopLogger,
        )

    private fun sampleCapabilities(
        toolName: String,
        promptName: String = "prompt-1",
        resourceKey: String = "resource://1",
    ): ServerCapabilities =
        ServerCapabilities(
            tools = listOf(ToolDescriptor(name = toolName, description = "$toolName description")),
            prompts = listOf(PromptDescriptor(name = promptName, description = "$promptName description")),
            resources =
                listOf(
                    ResourceDescriptor(
                        name = resourceKey,
                        uri = resourceKey,
                        description = "$resourceKey description",
                    ),
                ),
        )

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
        val existing = presets.indexOfFirst { it.id == preset.id }
        if (existing >= 0) {
            presets[existing] = preset
        } else {
            presets += preset
        }
    }

    override fun listPresets(): List<Preset> = presets.toList()

    override fun deletePreset(id: String) {
        presets.removeAll { it.id == id }
    }
}

private class FakeCapabilityCacheStore(
    private val entries: MutableList<PersistedCapabilityCacheEntry> = mutableListOf(),
) : PersistedCapabilityCacheStore {
    override fun loadAll(): List<PersistedCapabilityCacheEntry> = entries.toList()

    override fun save(entry: PersistedCapabilityCacheEntry) {
        entries.removeAll { it.serverId == entry.serverId }
        entries += entry
    }

    override fun remove(serverId: String) {
        entries.removeAll { it.serverId == serverId }
    }

    override fun retain(validIds: Set<String>) {
        entries.removeAll { it.serverId !in validIds }
    }
}
