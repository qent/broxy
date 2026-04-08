package io.qent.broxy.ui.adapter.clients.common

import kotlinx.serialization.json.JsonObject

internal const val DEFAULT_BROXY_SERVER_NAME = "broxy"

internal sealed interface BroxyServerEntry {
    data class JsonEntry(
        val value: JsonObject,
    ) : BroxyServerEntry

    data class UrlEntry(
        val url: String,
    ) : BroxyServerEntry
}

internal data class BroxyServerStatus(
    val isConfigured: Boolean,
    val configuredUrl: String? = null,
)

internal data class McpServerListEntry(
    val sourceServerId: String,
    val name: String? = null,
    val enabled: Boolean? = null,
    val type: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
)

/**
 * Internal contract for MCP server list formats used by AI client connectors.
 * This keeps connect/disconnect/status behavior stable and provides primitives
 * needed for future server import flows.
 */
internal interface McpServerListFormat {
    fun listServerEntries(content: String): List<McpServerListEntry>

    fun listServers(content: String): List<String>

    fun readBroxyStatus(
        content: String,
        serverName: String = DEFAULT_BROXY_SERVER_NAME,
    ): BroxyServerStatus

    fun upsertBroxy(
        content: String,
        serverName: String = DEFAULT_BROXY_SERVER_NAME,
        entry: BroxyServerEntry,
    ): String

    fun removeBroxy(
        content: String,
        serverName: String = DEFAULT_BROXY_SERVER_NAME,
    ): String
}
