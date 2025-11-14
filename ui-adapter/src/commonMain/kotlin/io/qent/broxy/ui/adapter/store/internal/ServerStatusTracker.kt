package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.models.UiServerConnStatus

/**
 * Tracks transient server connection statuses. Backed by a synchronized map.
 */
internal class ServerStatusTracker {
    private val statuses = mutableMapOf<String, UiServerConnStatus>()
    private val lock = Any()

    fun statusFor(serverId: String): UiServerConnStatus? = synchronized(lock) { statuses[serverId] }

    fun set(serverId: String, status: UiServerConnStatus) {
        synchronized(lock) { statuses[serverId] = status }
    }

    fun setAll(serverIds: Collection<String>, status: UiServerConnStatus) {
        if (serverIds.isEmpty()) return
        synchronized(lock) { serverIds.forEach { statuses[it] = status } }
    }

    fun remove(serverId: String) {
        synchronized(lock) { statuses.remove(serverId) }
    }

    fun retain(validIds: Set<String>) {
        synchronized(lock) { statuses.keys.retainAll(validIds) }
    }
}
