package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.store.CapabilityFetcher
import io.qent.broxy.ui.adapter.store.toSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class CapabilityRefresher(
    private val scope: CoroutineScope,
    private val capabilityFetcher: CapabilityFetcher,
    private val capabilityCache: CapabilityCache,
    private val statusTracker: ServerStatusTracker,
    private val logger: CollectingLogger,
    private val state: StoreStateAccess,
    private val publishReady: () -> Unit,
    private val refreshIntervalMillis: () -> Long
) {
    private var refreshJob: Job? = null
    private var backgroundEnabled = true

    fun syncWithServers(servers: List<UiMcpServerConfig>) {
        val ids = servers.map { it.id }.toSet()
        capabilityCache.retain(ids)
        statusTracker.retain(ids)
    }

    fun updateCachedName(serverId: String, name: String) {
        capabilityCache.updateName(serverId, name)
    }

    fun markServerDisabled(serverId: String) {
        capabilityCache.remove(serverId)
        statusTracker.set(serverId, UiServerConnStatus.Disabled)
    }

    fun markServerRemoved(serverId: String) {
        capabilityCache.remove(serverId)
        statusTracker.remove(serverId)
    }

    fun restartBackgroundJob(enabled: Boolean) {
        backgroundEnabled = enabled
        refreshJob?.cancel()
        if (!backgroundEnabled) {
            refreshJob = null
            return
        }
        refreshJob = scope.launch {
            while (isActive) {
                val interval = refreshIntervalMillis()
                delay(interval)
                refreshEnabledServers(force = false)
            }
        }
    }

    suspend fun listEnabledServerCaps(): List<UiServerCapsSnapshot> {
        val enabledIds = state.snapshot.servers.filter { it.enabled }.map { it.id }
        return capabilityCache.list(enabledIds)
    }

    suspend fun getServerCaps(serverId: String, forceRefresh: Boolean): UiServerCapsSnapshot? {
        val cfg = state.snapshot.servers.firstOrNull { it.id == serverId } ?: return null
        if (!forceRefresh) {
            val cached = capabilityCache.snapshot(serverId)
            if (cached != null) {
                return cached
            }
        }
        val fetched = runCatching { fetchAndCacheCapabilities(cfg) }
            .onFailure { error ->
                logger.info("[AppStore] getServerCaps('$serverId') failed: ${error.message}")
            }
            .getOrNull()
        val finalSnapshot = fetched ?: capabilityCache.snapshot(serverId)
        val status = if (finalSnapshot != null) UiServerConnStatus.Available else UiServerConnStatus.Error
        statusTracker.set(serverId, status)
        publishReady()
        return finalSnapshot
    }

    suspend fun refreshEnabledServers(force: Boolean) {
        val interval = refreshIntervalMillis()
        val targets = state.snapshot.servers.filter { it.enabled }
            .filter { force || capabilityCache.shouldRefresh(it.id, interval) }
        refreshServers(targets)
    }

    suspend fun refreshServersById(targetIds: Set<String>, force: Boolean) {
        if (targetIds.isEmpty()) return
        val interval = refreshIntervalMillis()
        val targets = state.snapshot.servers.filter { it.id in targetIds && it.enabled }
            .filter { force || capabilityCache.shouldRefresh(it.id, interval) }
        refreshServers(targets)
    }

    private suspend fun refreshServers(targets: List<UiMcpServerConfig>) {
        if (targets.isEmpty()) return
        val targetIds = targets.map { it.id }
        statusTracker.setAll(targetIds, UiServerConnStatus.Connecting)
        publishReady()

        val results = coroutineScope {
            targets.map { cfg ->
                async {
                    val snapshot = runCatching { fetchAndCacheCapabilities(cfg) }
                        .onFailure { error ->
                            logger.info("[AppStore] refresh server '${cfg.id}' failed: ${error.message}")
                        }
                        .getOrNull()
                    if (snapshot == null && !capabilityCache.has(cfg.id)) {
                        capabilityCache.remove(cfg.id)
                    }
                    cfg.id to (snapshot ?: capabilityCache.snapshot(cfg.id))
                }
            }.awaitAll()
        }

        val currentServers = state.snapshot.servers.associateBy { it.id }
        results.forEach { (serverId, capsSnapshot) ->
            val enabled = currentServers[serverId]?.enabled == true
            val status = when {
                !enabled -> UiServerConnStatus.Disabled
                capsSnapshot != null -> UiServerConnStatus.Available
                else -> UiServerConnStatus.Error
            }
            statusTracker.set(serverId, status)
        }
        publishReady()
    }

    private suspend fun fetchAndCacheCapabilities(cfg: UiMcpServerConfig): UiServerCapsSnapshot? {
        val timeoutSeconds = state.snapshot.capabilitiesTimeoutSeconds
        val result = capabilityFetcher(cfg, timeoutSeconds)
        return if (result.isSuccess) {
            val snapshot = result.getOrThrow().toSnapshot(cfg)
            capabilityCache.put(cfg.id, snapshot)
            snapshot
        } else {
            null
        }
    }
}
