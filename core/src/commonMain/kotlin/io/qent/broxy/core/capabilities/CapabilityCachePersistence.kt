package io.qent.broxy.core.capabilities

import kotlinx.serialization.Serializable

@Serializable
data class CapabilityCacheEntry(
    val serverId: String,
    val timestampMillis: Long,
    val snapshot: ServerCapsSnapshot,
)

interface CapabilityCachePersistence {
    fun loadAll(): List<CapabilityCacheEntry>

    fun save(entry: CapabilityCacheEntry)

    fun remove(serverId: String)

    fun retain(validIds: Set<String>)

    object Noop : CapabilityCachePersistence {
        override fun loadAll(): List<CapabilityCacheEntry> = emptyList()

        override fun save(entry: CapabilityCacheEntry) {
        }

        override fun remove(serverId: String) {
        }

        override fun retain(validIds: Set<String>) {
        }
    }
}
