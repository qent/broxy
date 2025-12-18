package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.MultiServerClient
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Indexes and provides search over tools exposed by all downstream servers.
 * - Lazy loads tool index on demand
 * - Relies on per-server capabilities cache
 * - Supports simple case-insensitive search by name/description
 */
class ToolRegistry(
    private val servers: List<McpServerConnection>,
    private val namespace: NamespaceManager = DefaultNamespaceManager(),
    private val logger: Logger = ConsoleLogger,
    private val ttlMillis: Long = DEFAULT_TTL_MS
) {
    data class Entry(
        val serverId: String,
        val name: String,             // original tool name without prefix
        val prefixedName: String,     // serverId:name
        val description: String?
    )

    private val multi = MultiServerClient(servers)
    private val mutex = Mutex()
    private var cached: List<Entry>? = null
    private var timestamp: TimeSource.Monotonic.ValueTimeMark? = null
    private val ttl: Duration = ttlMillis.milliseconds

    suspend fun refresh(force: Boolean = false) = mutex.withLock {
        val ts = timestamp
        if (!force && cached != null && ts != null && ts.elapsedNow() <= ttl) return
        val all: Map<String, ServerCapabilities> = multi.fetchAllCapabilities()
        cached = buildIndex(all)
        timestamp = TimeSource.Monotonic.markNow()
        logger.debug("ToolRegistry indexed ${cached?.size ?: 0} tools from ${all.size} servers")
    }

    suspend fun invalidate() = mutex.withLock {
        cached = null
        timestamp = null
    }

    suspend fun getTools(): List<Entry> {
        refresh(force = false)
        return mutex.withLock { cached ?: emptyList() }
    }

    suspend fun findByPrefixedName(name: String): Entry? = getTools().firstOrNull { it.prefixedName == name }

    suspend fun search(query: String): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return getTools()
        return getTools().filter { e ->
            e.prefixedName.lowercase().contains(q) ||
                    e.name.lowercase().contains(q) ||
                    (e.description?.lowercase()?.contains(q) == true)
        }
    }

    private fun buildIndex(all: Map<String, ServerCapabilities>): List<Entry> {
        return all.flatMap { (serverId, caps) ->
            caps.tools.map { t ->
                Entry(
                    serverId = serverId,
                    name = t.name,
                    prefixedName = namespace.prefixToolName(serverId, t.name),
                    description = t.description
                )
            }
        }
    }

    companion object {
        private const val DEFAULT_TTL_MS: Long = 60_000 // 1 minute
    }
}

