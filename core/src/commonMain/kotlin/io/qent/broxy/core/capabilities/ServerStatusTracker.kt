package io.qent.broxy.core.capabilities

/**
 * Tracks transient server connection statuses. Backed by a synchronized map.
 */
class ServerStatusTracker(
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val statuses = mutableMapOf<String, ServerConnectionStatus>()
    private val connectingSince = mutableMapOf<String, Long>()
    private val errors = mutableMapOf<String, String>()
    private val lock = Any()

    fun statusFor(serverId: String): ServerConnectionStatus? = synchronized(lock) { statuses[serverId] }

    fun connectingSince(serverId: String): Long? = synchronized(lock) { connectingSince[serverId] }

    fun errorMessageFor(serverId: String): String? = synchronized(lock) { errors[serverId] }

    fun setError(
        serverId: String,
        message: String?,
    ) {
        synchronized(lock) {
            statuses[serverId] = ServerConnectionStatus.Error
            if (!message.isNullOrBlank()) {
                errors[serverId] = message
            } else {
                errors.remove(serverId)
            }
            connectingSince.remove(serverId)
        }
    }

    fun set(
        serverId: String,
        status: ServerConnectionStatus,
    ) {
        synchronized(lock) {
            val previous = statuses[serverId]
            statuses[serverId] = status
            if (status.isTimedStatus()) {
                if (connectingSince[serverId] == null || previous != status) {
                    connectingSince[serverId] = now()
                }
            } else {
                connectingSince.remove(serverId)
            }
            if (status != ServerConnectionStatus.Error) {
                errors.remove(serverId)
            }
        }
    }

    fun setAll(
        serverIds: Collection<String>,
        status: ServerConnectionStatus,
    ) {
        if (serverIds.isEmpty()) return
        val timestamp = if (status.isTimedStatus()) now() else null
        synchronized(lock) {
            serverIds.forEach { serverId ->
                val previous = statuses[serverId]
                statuses[serverId] = status
                if (status.isTimedStatus()) {
                    if (connectingSince[serverId] == null || previous != status) {
                        connectingSince[serverId] = timestamp ?: now()
                    }
                } else {
                    connectingSince.remove(serverId)
                }
                if (status != ServerConnectionStatus.Error) {
                    errors.remove(serverId)
                }
            }
        }
    }

    fun remove(serverId: String) {
        synchronized(lock) {
            statuses.remove(serverId)
            connectingSince.remove(serverId)
            errors.remove(serverId)
        }
    }

    fun retain(validIds: Set<String>) {
        synchronized(lock) {
            statuses.keys.retainAll(validIds)
            connectingSince.keys.retainAll(validIds)
            errors.keys.retainAll(validIds)
        }
    }

    private fun ServerConnectionStatus.isTimedStatus(): Boolean =
        this == ServerConnectionStatus.Authorization || this == ServerConnectionStatus.Connecting
}
