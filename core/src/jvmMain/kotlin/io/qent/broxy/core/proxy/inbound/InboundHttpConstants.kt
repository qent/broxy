package io.qent.broxy.core.proxy.inbound

internal const val MCP_SESSION_ID_HEADER = "mcp-session-id"
internal const val MIN_REQUEST_TIMEOUT_MILLIS = 1L
internal const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 60_000L
internal const val SSE_ENDPOINT_PATH = "/sse"
internal const val SSE_SESSION_ID_PARAM = "sessionId"
internal const val SESSION_TTL_MILLIS = 20 * 60 * 1_000L
internal const val SESSION_CLEANUP_INTERVAL_MILLIS = 5 * 60 * 1_000L
