package io.qent.broxy.ui.adapter.capabilities

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.auth.AuthorizationStatusListener
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.proxy.runtime.ServerConnectionStatus
import io.qent.broxy.core.proxy.runtime.ServerConnectionUpdate
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

@Suppress("LongParameterList")
class CapabilityRefresher(
    private val scope: CoroutineScope,
    private val capabilityFetcher: CapabilityFetcher,
    private val capabilityCache: CapabilityCache,
    private val statusTracker: ServerStatusTracker,
    private val logger: Logger,
    private val serversProvider: () -> List<McpServerConfig>,
    private val capabilitiesTimeoutProvider: () -> Int,
    private val connectionRetryCountProvider: () -> Int,
    private val publishUpdate: () -> Unit,
    private val refreshIntervalMillis: () -> Long,
) {
    private var refreshJob: Job? = null
    private var backgroundEnabled = true
    private val refreshJobTracker = RefreshJobTracker()
    private val refreshRunner = RefreshRunner()

    private data class FetchResult(
        val snapshot: ServerCapsSnapshot?,
        val error: Throwable?,
    )

    fun syncWithServers(servers: List<McpServerConfig>) {
        val ids = servers.map { it.id }.toSet()
        capabilityCache.retain(ids)
        statusTracker.retain(ids)
        refreshJobTracker.cancelRefreshesNotIn(ids)
    }

    fun updateCachedName(
        serverId: String,
        name: String,
    ) {
        capabilityCache.updateName(serverId, name)
    }

    fun updateServerState(
        serverId: String,
        update: ServerStateUpdate,
    ) {
        when (update) {
            ServerStateUpdate.Disabled -> {
                refreshJobTracker.cancelRefresh(serverId)
                statusTracker.set(serverId, ServerConnectionStatus.Disabled)
            }
            ServerStateUpdate.Connecting -> {
                statusTracker.set(serverId, ServerConnectionStatus.Connecting)
                publishUpdate()
            }
            ServerStateUpdate.Removed -> {
                refreshJobTracker.cancelRefresh(serverId)
                capabilityCache.remove(serverId)
                statusTracker.remove(serverId)
            }
        }
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
        val cached = if (forceRefresh) null else capabilityCache.snapshot(serverId)
        val fetched = if (cached == null) refreshRunner.fetchAndCacheCapabilities(cfg) else null
        fetched?.error?.let { error ->
            logger.info("CapabilityRefresher getServerCaps('$serverId') failed: ${error.message}")
        }
        val finalSnapshot = cached ?: fetched?.snapshot ?: capabilityCache.snapshot(serverId)
        val status =
            when {
                fetched?.error != null -> ServerConnectionStatus.Error
                finalSnapshot != null -> ServerConnectionStatus.Available
                else -> ServerConnectionStatus.Error
            }
        if (status == ServerConnectionStatus.Error) {
            statusTracker.setError(serverId, fetched?.error?.message)
        } else {
            statusTracker.set(serverId, status)
        }
        publishUpdate()
        return finalSnapshot
    }

    suspend fun refreshEnabledServers(force: Boolean) {
        val interval = refreshIntervalMillis()
        val targets =
            serversProvider()
                .filter { it.enabled }
                .filter { force || capabilityCache.shouldRefresh(it.id, interval) }
        logger.debug("CapabilityRefresher refreshEnabledServers(force=$force) targets=${targets.size}")
        refreshRunner.refreshServers(targets)
    }

    suspend fun refreshServersById(
        targetIds: Set<String>,
        force: Boolean,
    ) {
        if (targetIds.isEmpty()) return
        val interval = refreshIntervalMillis()
        val targets =
            serversProvider()
                .filter { it.id in targetIds && it.enabled }
                .filter { force || capabilityCache.shouldRefresh(it.id, interval) }
        logger.debug(
            "CapabilityRefresher refreshServersById(ids=${targetIds.size}, force=$force) " +
                "targets=${targets.size}",
        )
        refreshRunner.refreshServers(targets)
    }

    fun applyProxyCapabilities(capabilitiesById: Map<String, ServerCapabilities>) {
        val servers = serversProvider()
        val configsById = servers.associateBy { it.id }
        val byId =
            capabilitiesById
                .mapNotNull { (serverId, capabilities) ->
                    val cfg = configsById[serverId] ?: return@mapNotNull null
                    serverId to capabilities.toSnapshot(cfg)
                }.toMap()

        byId.forEach { (serverId, snapshot) ->
            capabilityCache.put(serverId, snapshot)
            statusTracker.set(serverId, ServerConnectionStatus.Available)
        }
        servers.filterNot { it.enabled }.forEach { cfg ->
            statusTracker.set(cfg.id, ServerConnectionStatus.Disabled)
        }
        publishUpdate()
    }

    val hasCachedSnapshot: (String) -> Boolean =
        { serverId -> capabilityCache.snapshot(serverId) != null }

    fun applyProxyStatus(update: ServerConnectionUpdate) {
        when (update.status) {
            ServerConnectionStatus.Error -> statusTracker.setError(update.serverId, update.errorMessage)
            else -> statusTracker.set(update.serverId, update.status)
        }
        publishUpdate()
    }

    private inner class RefreshRunner {
        suspend fun refreshServers(targets: List<McpServerConfig>) {
            if (targets.isEmpty()) return
            val targetIds = targets.map { it.id }
            logger.debug("CapabilityRefresher refreshServers start targets=${targetIds.joinToString(",")}")
            statusTracker.setAll(targetIds, ServerConnectionStatus.Connecting)
            publishUpdate()

            supervisorScope {
                targets
                    .mapNotNull { cfg ->
                        val job =
                            launch(start = CoroutineStart.LAZY) {
                                performRefresh(cfg)
                            }
                        if (!refreshJobTracker.tryStartRefresh(cfg.id, job)) {
                            logger.debug("CapabilityRefresher refresh server '${cfg.id}' skipped (already active)")
                            job.cancel()
                            return@mapNotNull null
                        }
                        job
                    }.joinAll()
            }
        }

        private suspend fun performRefresh(cfg: McpServerConfig) {
            if (!isServerEnabled(cfg.id)) {
                statusTracker.set(cfg.id, ServerConnectionStatus.Disabled)
                publishUpdate()
                return
            }
            logger.debug("CapabilityRefresher refresh server '${cfg.id}' started")
            logger.debug("CapabilityRefresher fetching capabilities for '${cfg.id}'")
            val fetched = fetchAndCacheCapabilities(cfg)
            fetched.error?.let { error ->
                error.rethrowIfCancelled()
                logger.info(
                    "CapabilityRefresher refresh server '${cfg.id}' failed: ${error.message}",
                )
            }
            val capsSnapshot = resolveSnapshot(cfg.id, fetched.snapshot)
            val status = resolveRefreshStatus(cfg.id, fetched.error, capsSnapshot)
            updateStatus(cfg.id, status, fetched.error)
            logger.debug(
                "CapabilityRefresher refresh server '${cfg.id}' completed with status=$status",
            )
            publishUpdate()
        }

        private fun resolveSnapshot(
            serverId: String,
            fetchedSnapshot: ServerCapsSnapshot?,
        ): ServerCapsSnapshot? {
            if (fetchedSnapshot == null && !capabilityCache.has(serverId)) {
                capabilityCache.remove(serverId)
            }
            return fetchedSnapshot ?: capabilityCache.snapshot(serverId)
        }

        private fun resolveRefreshStatus(
            serverId: String,
            error: Throwable?,
            snapshot: ServerCapsSnapshot?,
        ): ServerConnectionStatus =
            when {
                !isServerEnabled(serverId) -> ServerConnectionStatus.Disabled
                error != null -> ServerConnectionStatus.Error
                snapshot != null -> ServerConnectionStatus.Available
                else -> ServerConnectionStatus.Error
            }

        private fun updateStatus(
            serverId: String,
            status: ServerConnectionStatus,
            error: Throwable?,
        ) {
            if (status == ServerConnectionStatus.Error) {
                statusTracker.setError(serverId, error?.message)
            } else {
                statusTracker.set(serverId, status)
            }
        }

        private fun isServerEnabled(serverId: String): Boolean {
            val currentServers = serversProvider().associateBy { it.id }
            return currentServers[serverId]?.enabled == true
        }

        suspend fun fetchAndCacheCapabilities(cfg: McpServerConfig): FetchResult {
            val timeoutSeconds = capabilitiesTimeoutProvider()
            val retryCount = connectionRetryCountProvider()
            logger.debug(
                "CapabilityRefresher fetchAndCacheCapabilities '${cfg.id}' " +
                    "timeoutSeconds=$timeoutSeconds retries=$retryCount",
            )
            val authorizationListener =
                object : AuthorizationStatusListener {
                    override fun onAuthorizationStart() {
                        statusTracker.set(cfg.id, ServerConnectionStatus.Authorization)
                        publishUpdate()
                    }

                    override fun onAuthorizationComplete() {
                        statusTracker.set(cfg.id, ServerConnectionStatus.Connecting)
                        publishUpdate()
                    }
                }
            val result = capabilityFetcher(cfg, timeoutSeconds, retryCount, authorizationListener)
            return if (result.isSuccess) {
                val snapshot = result.getOrThrow().toSnapshot(cfg)
                capabilityCache.put(cfg.id, snapshot)
                FetchResult(snapshot = snapshot, error = null)
            } else {
                FetchResult(snapshot = null, error = result.exceptionOrNull())
            }
        }
    }
}

private class RefreshJobTracker {
    private val refreshJobs = mutableMapOf<String, Job>()
    private val refreshLock = Any()

    fun tryStartRefresh(
        serverId: String,
        job: Job,
    ): Boolean {
        val accepted =
            synchronized(refreshLock) {
                val existing = refreshJobs[serverId]
                if (existing?.isActive == true) {
                    false
                } else {
                    refreshJobs[serverId] = job
                    true
                }
            }
        if (!accepted) return false
        job.invokeOnCompletion {
            synchronized(refreshLock) {
                val current = refreshJobs[serverId]
                if (current == job) {
                    refreshJobs.remove(serverId)
                }
            }
        }
        job.start()
        return true
    }

    fun cancelRefresh(serverId: String) {
        synchronized(refreshLock) {
            refreshJobs.remove(serverId)
        }?.cancel()
    }

    fun cancelRefreshesNotIn(validIds: Set<String>) {
        val toCancel =
            synchronized(refreshLock) {
                val ids = refreshJobs.keys.filterNot { it in validIds }
                ids.mapNotNull { id -> refreshJobs.remove(id) }
            }
        toCancel.forEach { it.cancel() }
    }
}

private fun Throwable.rethrowIfCancelled() {
    if (this is CancellationException) {
        throw this
    }
}

sealed class ServerStateUpdate {
    object Disabled : ServerStateUpdate()

    object Connecting : ServerStateUpdate()

    object Removed : ServerStateUpdate()
}
