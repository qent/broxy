package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.Preset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class CapabilitiesStateStore(
    private val presetEngine: PresetEngine,
) {
    data class FilteredState(
        val capabilities: ServerCapabilities = ServerCapabilities(),
        val allowedTools: Set<String> = emptySet(),
        val promptServerByName: Map<String, String> = emptyMap(),
        val resourceServerByUri: Map<String, String> = emptyMap(),
    )

    @Volatile
    private var filteredState: FilteredState = FilteredState()

    private val refreshMutex = Mutex()
    private var refreshSequence: Long = 0
    private var lastFullRefreshApplied: Long = 0
    private val perServerRefreshSequence: MutableMap<String, Long> = mutableMapOf()
    private var allCapabilities: Map<String, ServerCapabilities> = emptyMap()

    fun currentCapabilities(): ServerCapabilities = filteredState.capabilities

    fun allowedTools(): Set<String> = filteredState.allowedTools

    fun promptServerId(name: String): String? = filteredState.promptServerByName[name]

    fun resourceServerId(uri: String): String? = filteredState.resourceServerByUri[uri]

    fun snapshotCapabilities(): Map<String, ServerCapabilities> = allCapabilities.toMap()

    suspend fun nextRefreshSequence(): Long =
        refreshMutex.withLock {
            refreshSequence += 1
            refreshSequence
        }

    suspend fun applyFullRefresh(
        refreshId: Long,
        all: Map<String, ServerCapabilities>,
        preset: Preset?,
    ): Boolean =
        refreshMutex.withLock {
            if (preset == null) return@withLock false
            if (refreshId < lastFullRefreshApplied) return@withLock false
            val merged = mutableMapOf<String, ServerCapabilities>()
            all.forEach { (serverId, caps) ->
                val serverRefresh = perServerRefreshSequence[serverId]
                val existing = allCapabilities[serverId]
                if (serverRefresh != null && serverRefresh > refreshId && existing != null) {
                    merged[serverId] = existing
                } else {
                    merged[serverId] = caps
                }
            }
            perServerRefreshSequence.forEach { (serverId, serverRefresh) ->
                if (serverRefresh > refreshId && !merged.containsKey(serverId)) {
                    allCapabilities[serverId]?.let { merged[serverId] = it }
                }
            }
            allCapabilities = merged
            updateFilteredState(merged, preset)
            lastFullRefreshApplied = refreshId
            true
        }

    suspend fun applyServerRefresh(
        refreshId: Long,
        serverId: String,
        caps: ServerCapabilities,
        preset: Preset?,
    ): Boolean =
        refreshMutex.withLock {
            if (preset == null) return@withLock false
            if (refreshId < lastFullRefreshApplied) return@withLock false
            val lastServerRefresh = perServerRefreshSequence[serverId] ?: 0
            if (refreshId < lastServerRefresh) return@withLock false
            allCapabilities = allCapabilities + (serverId to caps)
            perServerRefreshSequence[serverId] = refreshId
            updateFilteredState(allCapabilities, preset)
            true
        }

    suspend fun removeServer(
        refreshId: Long,
        serverId: String,
        preset: Preset?,
    ): Boolean =
        refreshMutex.withLock {
            if (preset == null) return@withLock false
            lastFullRefreshApplied = refreshId
            perServerRefreshSequence.remove(serverId)
            allCapabilities = allCapabilities - serverId
            updateFilteredState(allCapabilities, preset)
            true
        }

    private fun updateFilteredState(
        all: Map<String, ServerCapabilities>,
        preset: Preset,
    ) {
        val result = presetEngine.apply(all, preset)
        filteredState =
            FilteredState(
                capabilities = result.capabilities,
                allowedTools = result.allowedPrefixedTools,
                promptServerByName = result.promptServerByName,
                resourceServerByUri = result.resourceServerByUri,
            )
    }
}
