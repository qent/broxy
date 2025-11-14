package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.models.UiLogEntry

/**
 * Bounded buffer for log entries with thread-safe access.
 */
internal class LogsBuffer(
    private val maxEntries: Int
) {
    private val entries = ArrayDeque<UiLogEntry>()
    private val lock = Any()

    fun append(entry: UiLogEntry) {
        synchronized(lock) {
            entries += entry
            if (entries.size > maxEntries) {
                entries.removeFirst()
            }
        }
    }

    fun snapshot(): List<UiLogEntry> = synchronized(lock) {
        entries.asReversed().toList()
    }
}
