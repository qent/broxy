package io.qent.broxy.core.capabilities

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class CapabilityRefresher(
    private val scope: CoroutineScope,
    private val capabilityFetcher: CapabilityFetcher,
    private val capabilityCache: CapabilityCache,
    private val statusTracker: ServerStatusTracker,
    private val logger: Logger,
    private val serversProvider: () -> List<McpServerConfig>,
    private val capabilitiesTimeoutProvider: () -> Int,
    private val publishUpdate: () -> Unit,
    private val refreshIntervalMillis: () -> Long,
) {
    private var refreshJob: Job? = null
    private var backgroundEnabled = true
    private val refreshJobs = mutableMapOf<String, Job>()
    private val refreshLock = Any()

    fun syncWithServers(servers: List<McpServerConfig>) {
        val ids = servers.map { it.id }.toSet()
        capabilityCache.retain(ids)
        statusTracker.retain(ids)
        cancelRefreshesNotIn(ids)
    }

    fun updateCachedName(
        serverId: String,
        name: String,
    ) {
        capabilityCache.updateName(serverId, name)
    }

    fun markServerDisabled(serverId: String) {
        cancelRefresh(serverId)
        capabilityCache.remove(serverId)
        statusTracker.set(serverId, ServerConnectionStatus.Disabled)
    }

    fun markServerRemoved(serverId: String) {
        cancelRefresh(serverId)
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
        refreshJob =
            scope.launch {
                while (isActive) {
                    val interval = refreshIntervalMillis()
                    delay(interval)
                    refreshEnabledServers(force = false)
                }
            }
    }

    suspend fun listEnabledServerCaps(): List<ServerCapsSnapshot> {
        val enabledIds = serversProvider().filter { it.enabled }.map { it.id }
        return capabilityCache.list(enabledIds)
    }

    suspend fun getServerCaps(
        serverId: String,
        forceRefresh: Boolean,
    ): ServerCapsSnapshot? {
        val cfg = serversProvider().firstOrNull { it.id == serverId } ?: return null
        if (!forceRefresh) {
            val cached = capabilityCache.snapshot(serverId)
            if (cached != null) {
                return cached
            }
        }
        val fetched =
            runCatching { fetchAndCacheCapabilities(cfg) }
                .onFailure { error ->
                    logger.info("CapabilityRefresher getServerCaps('$serverId') failed: ${error.message}")
                }
                .getOrNull()
        val finalSnapshot = fetched ?: capabilityCache.snapshot(serverId)
        val status = if (finalSnapshot != null) ServerConnectionStatus.Available else ServerConnectionStatus.Error
        statusTracker.set(serverId, status)
        publishUpdate()
        return finalSnapshot
    }

    suspend fun refreshEnabledServers(force: Boolean) {
        val interval = refreshIntervalMillis()
        val targets =
            serversProvider().filter { it.enabled }
                .filter { force || capabilityCache.shouldRefresh(it.id, interval) }
        refreshServers(targets)
    }

    suspend fun refreshServersById(
        targetIds: Set<String>,
        force: Boolean,
    ) {
        if (targetIds.isEmpty()) return
        val interval = refreshIntervalMillis()
        val targets =
            serversProvider().filter { it.id in targetIds && it.enabled }
                .filter { force || capabilityCache.shouldRefresh(it.id, interval) }
        refreshServers(targets)
    }

    fun applyProxySnapshots(snapshots: List<ServerCapsSnapshot>) {
        val servers = serversProvider()
        val enabledIds = servers.filter { it.enabled }.map { it.id }.toSet()
        val byId = snapshots.associateBy { it.serverId }

        byId.forEach { (serverId, snapshot) ->
            capabilityCache.put(serverId, snapshot)
        }
        capabilityCache.retain(byId.keys)

        enabledIds.forEach { serverId ->
            val status =
                if (serverId in byId) {
                    ServerConnectionStatus.Available
                } else {
                    ServerConnectionStatus.Error
                }
            statusTracker.set(serverId, status)
        }
        servers.filterNot { it.enabled }.forEach { cfg ->
            statusTracker.set(cfg.id, ServerConnectionStatus.Disabled)
        }
        publishUpdate()
    }

    private suspend fun refreshServers(targets: List<McpServerConfig>) {
        if (targets.isEmpty()) return
        val targetIds = targets.map { it.id }
        statusTracker.setAll(targetIds, ServerConnectionStatus.Connecting)
        publishUpdate()

        coroutineScope {
            targets.mapNotNull { cfg ->
                if (isRefreshActive(cfg.id)) return@mapNotNull null
                val job =
                    launch {
                        val currentServers = serversProvider().associateBy { it.id }
                        val enabled = currentServers[cfg.id]?.enabled == true
                        if (!enabled) {
                            statusTracker.set(cfg.id, ServerConnectionStatus.Disabled)
                            publishUpdate()
                            return@launch
                        }
                        val snapshot =
                            runCatching { fetchAndCacheCapabilities(cfg) }
                                .onFailure { error ->
                                    error.rethrowIfCancelled()
                                    logger.info("CapabilityRefresher refresh server '${cfg.id}' failed: ${error.message}")
                                }
                                .getOrNull()
                        if (snapshot == null && !capabilityCache.has(cfg.id)) {
                            capabilityCache.remove(cfg.id)
                        }
                        val capsSnapshot = snapshot ?: capabilityCache.snapshot(cfg.id)
                        val refreshedServers = serversProvider().associateBy { it.id }
                        val stillEnabled = refreshedServers[cfg.id]?.enabled == true
                        val status =
                            when {
                                !stillEnabled -> ServerConnectionStatus.Disabled
                                capsSnapshot != null -> ServerConnectionStatus.Available
                                else -> ServerConnectionStatus.Error
                            }
                        statusTracker.set(cfg.id, status)
                        publishUpdate()
                    }
                trackRefreshJob(cfg.id, job)
                job
            }.joinAll()
        }
    }

    private suspend fun fetchAndCacheCapabilities(cfg: McpServerConfig): ServerCapsSnapshot? {
        val timeoutSeconds = capabilitiesTimeoutProvider()
        val result = capabilityFetcher(cfg, timeoutSeconds)
        return if (result.isSuccess) {
            val snapshot = result.getOrThrow().toSnapshot(cfg)
            capabilityCache.put(cfg.id, snapshot)
            snapshot
        } else {
            null
        }
    }

    private fun isRefreshActive(serverId: String): Boolean =
        synchronized(refreshLock) {
            refreshJobs[serverId]?.isActive == true
        }

    private fun trackRefreshJob(
        serverId: String,
        job: Job,
    ) {
        synchronized(refreshLock) {
            refreshJobs[serverId] = job
        }
        job.invokeOnCompletion {
            synchronized(refreshLock) {
                val current = refreshJobs[serverId]
                if (current == job) {
                    refreshJobs.remove(serverId)
                }
            }
        }
    }

    private fun cancelRefresh(serverId: String) {
        synchronized(refreshLock) {
            refreshJobs.remove(serverId)
        }?.cancel()
    }

    private fun cancelRefreshesNotIn(validIds: Set<String>) {
        val toCancel =
            synchronized(refreshLock) {
                val ids = refreshJobs.keys.filterNot { it in validIds }
                ids.mapNotNull { id -> refreshJobs.remove(id) }
            }
        toCancel.forEach { it.cancel() }
    }

    private fun Throwable.rethrowIfCancelled() {
        if (this is CancellationException) {
            throw this
        }
    }
}
