package io.qent.broxy.ui.adapter.capabilities

import io.qent.broxy.core.capabilities.FilePersistedCapabilityCacheStore
import io.qent.broxy.core.capabilities.PersistedCapabilityArgument
import io.qent.broxy.core.capabilities.PersistedCapabilityCacheEntry
import io.qent.broxy.core.capabilities.PersistedPromptSummary
import io.qent.broxy.core.capabilities.PersistedResourceSummary
import io.qent.broxy.core.capabilities.PersistedServerCapsSnapshot
import io.qent.broxy.core.capabilities.PersistedToolSummary
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import java.nio.file.Path

internal class FileCapabilityCachePersistence(
    baseDir: Path,
    private val logger: Logger? = null,
    private val json: Json = defaultJson,
) : CapabilityCachePersistence {
    private val store = FilePersistedCapabilityCacheStore(baseDir = baseDir, logger = logger, json = json)

    override fun loadAll(): List<CapabilityCacheEntry> = store.loadAll().map { it.toUiEntry() }

    override fun save(entry: CapabilityCacheEntry) {
        store.save(entry.toPersistedEntry())
    }

    override fun remove(serverId: String) {
        store.remove(serverId)
    }

    override fun retain(validIds: Set<String>) {
        store.retain(validIds)
    }

    private fun PersistedCapabilityCacheEntry.toUiEntry(): CapabilityCacheEntry =
        CapabilityCacheEntry(
            serverId = serverId,
            timestampMillis = timestampMillis,
            snapshot = snapshot.toUiSnapshot(),
        )

    private fun CapabilityCacheEntry.toPersistedEntry(): PersistedCapabilityCacheEntry =
        PersistedCapabilityCacheEntry(
            serverId = serverId,
            timestampMillis = timestampMillis,
            snapshot = snapshot.toPersistedSnapshot(),
        )

    private fun PersistedServerCapsSnapshot.toUiSnapshot(): ServerCapsSnapshot =
        ServerCapsSnapshot(
            serverId = serverId,
            name = name,
            tools =
                tools.map { tool ->
                    ToolSummary(
                        name = tool.name,
                        description = tool.description,
                        arguments =
                            tool.arguments.map { argument ->
                                CapabilityArgument(
                                    name = argument.name,
                                    type = argument.type,
                                    required = argument.required,
                                )
                            },
                    )
                },
            prompts =
                prompts.map { prompt ->
                    PromptSummary(
                        name = prompt.name,
                        description = prompt.description,
                        arguments =
                            prompt.arguments.map { argument ->
                                CapabilityArgument(
                                    name = argument.name,
                                    type = argument.type,
                                    required = argument.required,
                                )
                            },
                    )
                },
            resources =
                resources.map { resource ->
                    ResourceSummary(
                        key = resource.key,
                        name = resource.name,
                        description = resource.description,
                        arguments =
                            resource.arguments.map { argument ->
                                CapabilityArgument(
                                    name = argument.name,
                                    type = argument.type,
                                    required = argument.required,
                                )
                            },
                    )
                },
        )

    private fun ServerCapsSnapshot.toPersistedSnapshot(): PersistedServerCapsSnapshot =
        PersistedServerCapsSnapshot(
            serverId = serverId,
            name = name,
            tools =
                tools.map { tool ->
                    PersistedToolSummary(
                        name = tool.name,
                        description = tool.description,
                        arguments =
                            tool.arguments.map { argument ->
                                PersistedCapabilityArgument(
                                    name = argument.name,
                                    type = argument.type,
                                    required = argument.required,
                                )
                            },
                    )
                },
            prompts =
                prompts.map { prompt ->
                    PersistedPromptSummary(
                        name = prompt.name,
                        description = prompt.description,
                        arguments =
                            prompt.arguments.map { argument ->
                                PersistedCapabilityArgument(
                                    name = argument.name,
                                    type = argument.type,
                                    required = argument.required,
                                )
                            },
                    )
                },
            resources =
                resources.map { resource ->
                    PersistedResourceSummary(
                        key = resource.key,
                        name = resource.name,
                        description = resource.description,
                        arguments =
                            resource.arguments.map { argument ->
                                PersistedCapabilityArgument(
                                    name = argument.name,
                                    type = argument.type,
                                    required = argument.required,
                                )
                            },
                    )
                },
        )

    private companion object {
        private val defaultJson =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
    }
}
