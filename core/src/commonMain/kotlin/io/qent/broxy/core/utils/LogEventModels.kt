package io.qent.broxy.core.utils

internal enum class LogRequestType(
    val wireName: String,
    val nameKey: String,
    val eventSuffix: String,
) {
    TOOL("tool", "toolName", ""),
    PROMPT("prompt", "promptName", ".prompt"),
    RESOURCE("resource", "resourceUri", ".resource"),
}

internal data class LogRequestContext(
    val logger: Logger,
    val type: LogRequestType,
    val name: String,
)

internal data class DownstreamContext(
    val resolvedServerId: String,
    val downstreamName: String,
)

internal data class LogTargetContext(
    val targetServerId: String? = null,
    val downstreamName: String? = null,
)
