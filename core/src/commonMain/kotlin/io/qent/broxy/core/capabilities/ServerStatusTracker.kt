package io.qent.broxy.core.capabilities

/**
 * Tracks transient server connection statuses. Backed by a synchronized map.
 */
class ServerStatusTracker {
    private val statuses = mutableMapOf<String, ServerConnectionStatus>()
    private val lock = Any()

    fun statusFor(serverId: String): ServerConnectionStatus? = synchronized(lock) { statuses[serverId] }

    fun set(serverId: String, status: ServerConnectionStatus) {
        synchronized(lock) { statuses[serverId] = status }
    }

    fun setAll(serverIds: Collection<String>, status: ServerConnectionStatus) {
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
