package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.models.UiAgenticInstallPermissionPopup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AgenticInstallPermissionCoordinator(
    private val state: StoreStateAccess,
    private val publishReady: () -> Unit,
    private val logger: Logger,
) {
    private val lock = Mutex()
    private var nextRequestId: Long = 1L
    private val pendingDecisions: MutableMap<Long, CompletableDeferred<Boolean>> = mutableMapOf()

    suspend fun requestPermission(
        serverId: String,
        serverName: String,
        serverDescription: String,
        iconUrl: String?,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val popup =
            lock.withLock {
                val popup =
                    UiAgenticInstallPermissionPopup(
                        requestId = nextRequestId++,
                        serverId = serverId,
                        serverName = serverName,
                        serverDescription = serverDescription,
                        iconUrl = iconUrl,
                    )
                pendingDecisions[popup.requestId] = deferred
                state.updateSnapshot {
                    if (agenticInstallPermissionPopup == null) {
                        copy(
                            agenticInstallPermissionPopup = popup,
                            agenticInstallPermissionQueue = agenticInstallPermissionQueue.filterNot { it.requestId == popup.requestId },
                        )
                    } else {
                        val deduplicatedQueue = agenticInstallPermissionQueue.filterNot { it.requestId == popup.requestId }
                        copy(agenticInstallPermissionQueue = deduplicatedQueue + popup)
                    }
                }
                popup
            }
        publishReady()
        val decision =
            runCatching { deferred.await() }
                .onFailure { error ->
                    logger.warn("Agentic install permission request '${popup.requestId}' failed: ${error.message}", error)
                }.getOrDefault(false)
        lock.withLock {
            pendingDecisions.remove(popup.requestId)
        }
        return decision
    }

    suspend fun allow(requestId: Long) {
        resolve(requestId, true)
    }

    suspend fun deny(requestId: Long) {
        resolve(requestId, false)
    }

    suspend fun cancelAll() {
        val pending =
            lock.withLock {
                val all = pendingDecisions.values.toList()
                pendingDecisions.clear()
                state.updateSnapshot {
                    copy(
                        agenticInstallPermissionPopup = null,
                        agenticInstallPermissionQueue = emptyList(),
                    )
                }
                all
            }
        pending.forEach { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(false)
            }
        }
        publishReady()
    }

    private suspend fun resolve(
        requestId: Long,
        decision: Boolean,
    ) {
        val deferred =
            lock.withLock {
                val snapshot = state.snapshot
                val activePopup = snapshot.agenticInstallPermissionPopup
                val queuedPopups = snapshot.agenticInstallPermissionQueue
                val (nextActive, nextQueue) =
                    when {
                        activePopup?.requestId == requestId -> {
                            val next = queuedPopups.firstOrNull()
                            next to if (next == null) emptyList() else queuedPopups.drop(1)
                        }

                        queuedPopups.any { it.requestId == requestId } -> {
                            activePopup to queuedPopups.filterNot { it.requestId == requestId }
                        }

                        else -> {
                            activePopup to queuedPopups
                        }
                    }
                state.updateSnapshot {
                    copy(
                        agenticInstallPermissionPopup = nextActive,
                        agenticInstallPermissionQueue = nextQueue,
                    )
                }
                pendingDecisions.remove(requestId)
            }
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(decision)
        }
        publishReady()
    }
}
