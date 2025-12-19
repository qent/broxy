package io.qent.broxy.core.capabilities

/**
 * Thread-safe cache for server capabilities that remembers the last refresh timestamp.
 */
class CapabilityCache(
    private val now: () -> Long,
) {
    private data class Entry(val snapshot: ServerCapsSnapshot, val timestampMillis: Long)

    private val entries = mutableMapOf<String, Entry>()
    private val lock = Any()

    fun snapshot(serverId: String): ServerCapsSnapshot? =
        synchronized(lock) {
            entries[serverId]?.snapshot
        }

    fun put(
        serverId: String,
        snapshot: ServerCapsSnapshot,
    ) {
        val entry = Entry(snapshot = snapshot, timestampMillis = now())
        synchronized(lock) { entries[serverId] = entry }
    }

    fun has(serverId: String): Boolean = synchronized(lock) { serverId in entries }

    fun updateName(
        serverId: String,
        name: String,
    ) {
        synchronized(lock) {
            val existing = entries[serverId] ?: return
            entries[serverId] = existing.copy(snapshot = existing.snapshot.copy(name = name))
        }
    }

    fun remove(serverId: String) {
        synchronized(lock) { entries.remove(serverId) }
    }

    fun retain(validIds: Set<String>) {
        synchronized(lock) { entries.keys.retainAll(validIds) }
    }

    fun shouldRefresh(
        serverId: String,
        intervalMillis: Long,
    ): Boolean =
        synchronized(lock) {
            val entry = entries[serverId] ?: return@synchronized true
            now() - entry.timestampMillis >= intervalMillis
        }

    fun list(serverIds: Collection<String>): List<ServerCapsSnapshot> = serverIds.mapNotNull { snapshot(it) }
}
