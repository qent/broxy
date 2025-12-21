package io.qent.broxy.core.capabilities

/**
 * Tracks transient server connection statuses. Backed by a synchronized map.
 */
class ServerStatusTracker(
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val statuses = mutableMapOf<String, ServerConnectionStatus>()
    private val connectingSince = mutableMapOf<String, Long>()
    private val lock = Any()

    fun statusFor(serverId: String): ServerConnectionStatus? = synchronized(lock) { statuses[serverId] }

    fun connectingSince(serverId: String): Long? = synchronized(lock) { connectingSince[serverId] }

    fun set(
        serverId: String,
        status: ServerConnectionStatus,
    ) {
        synchronized(lock) {
            statuses[serverId] = status
            if (status == ServerConnectionStatus.Connecting) {
                if (connectingSince[serverId] == null) {
                    connectingSince[serverId] = now()
                }
            } else {
                connectingSince.remove(serverId)
            }
        }
    }

    fun setAll(
        serverIds: Collection<String>,
        status: ServerConnectionStatus,
    ) {
        if (serverIds.isEmpty()) return
        val timestamp = if (status == ServerConnectionStatus.Connecting) now() else null
        synchronized(lock) {
            serverIds.forEach { serverId ->
                statuses[serverId] = status
                if (status == ServerConnectionStatus.Connecting) {
                    if (connectingSince[serverId] == null) {
                        connectingSince[serverId] = timestamp ?: now()
                    }
                } else {
                    connectingSince.remove(serverId)
                }
            }
        }
    }

    fun remove(serverId: String) {
        synchronized(lock) {
            statuses.remove(serverId)
            connectingSince.remove(serverId)
        }
    }

    fun retain(validIds: Set<String>) {
        synchronized(lock) {
            statuses.keys.retainAll(validIds)
            connectingSince.keys.retainAll(validIds)
        }
    }
}
