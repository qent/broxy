package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.models.UiServerCapsSnapshot

/**
 * Thread-safe cache for server capabilities that remembers the last refresh timestamp.
 */
internal class CapabilityCache(
    private val now: () -> Long
) {
    private data class Entry(val snapshot: UiServerCapsSnapshot, val timestampMillis: Long)

    private val entries = mutableMapOf<String, Entry>()
    private val lock = Any()

    fun snapshot(serverId: String): UiServerCapsSnapshot? = synchronized(lock) {
        entries[serverId]?.snapshot
    }

    fun put(serverId: String, snapshot: UiServerCapsSnapshot) {
        val entry = Entry(snapshot = snapshot, timestampMillis = now())
        synchronized(lock) { entries[serverId] = entry }
    }

    fun has(serverId: String): Boolean = synchronized(lock) { serverId in entries }

    fun updateName(serverId: String, name: String) {
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

    fun shouldRefresh(serverId: String, intervalMillis: Long): Boolean = synchronized(lock) {
        val entry = entries[serverId] ?: return@synchronized true
        now() - entry.timestampMillis >= intervalMillis
    }

    fun list(serverIds: Collection<String>): List<UiServerCapsSnapshot> =
        serverIds.mapNotNull { snapshot(it) }
}
