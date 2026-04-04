package io.qent.broxy.ui.adapter.clients

import io.qent.broxy.ui.adapter.clients.common.McpServerListEntry
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiTransportConfig
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport

internal fun McpServerListEntry.toImportServerOrNull(): AiClientImportServer? {
    val normalizedId = sourceServerId.trim()
    if (normalizedId.isEmpty()) return null
    val transport = toTransportOrNull() ?: return null
    val normalizedName = name?.trim()?.takeIf { it.isNotEmpty() } ?: normalizedId
    val normalizedEnv =
        env
            .mapNotNull { (key, value) ->
                val normalizedKey = key.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                normalizedKey to value.trim()
            }.toMap()
    return AiClientImportServer(
        sourceServerId = normalizedId,
        name = normalizedName,
        enabled = enabled ?: true,
        transport = transport,
        env = normalizedEnv,
    )
}

private fun McpServerListEntry.toTransportOrNull(): UiTransportConfig? {
    val normalizedType = type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    return when (normalizedType) {
        "stdio" -> {
            val normalizedCommand = command?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            UiStdioTransport(command = normalizedCommand, args = args.mapNotNull { it.trim().takeIf { arg -> arg.isNotEmpty() } })
        }
        "http",
        "streamable-http",
        "streamable_http",
        "streamablehttp",
        -> {
            val normalizedUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            UiStreamableHttpTransport(url = normalizedUrl, headers = normalizedHeaders())
        }
        "sse" -> {
            val normalizedUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            UiHttpTransport(url = normalizedUrl, headers = normalizedHeaders())
        }
        "ws", "websocket" -> {
            val normalizedUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            UiWebSocketTransport(url = normalizedUrl, headers = normalizedHeaders())
        }
        null -> inferTransportWithoutExplicitType()
        else -> null
    }
}

private fun McpServerListEntry.inferTransportWithoutExplicitType(): UiTransportConfig? {
    val normalizedCommand = command?.trim()?.takeIf { it.isNotEmpty() }
    if (normalizedCommand != null) {
        return UiStdioTransport(command = normalizedCommand, args = args.mapNotNull { it.trim().takeIf { arg -> arg.isNotEmpty() } })
    }
    val normalizedUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return UiStreamableHttpTransport(url = normalizedUrl, headers = normalizedHeaders())
}

private fun McpServerListEntry.normalizedHeaders(): Map<String, String> =
    headers
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            normalizedKey to value.trim()
        }.toMap()
