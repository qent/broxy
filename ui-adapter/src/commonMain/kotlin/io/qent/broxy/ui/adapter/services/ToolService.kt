package io.qent.broxy.ui.adapter.services

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.auth.AuthorizationStatusListener
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig

data class CommandAvailability(
    val isAvailable: Boolean,
    val resolvedPath: String?,
)

/**
 * Provides access to server tools/capabilities for UI components.
 * Implementations live per-platform.
 */
expect suspend fun fetchServerCapabilities(
    config: UiMcpServerConfig,
    timeoutSeconds: Int,
    connectionRetryCount: Int,
    ignoreHttpsCertificateErrors: Boolean = false,
    logger: Logger? = null,
    authorizationStatusListener: AuthorizationStatusListener? = null,
): Result<ServerCapabilities>

expect suspend fun checkStdioCommandAvailability(
    command: String,
    env: Map<String, String> = emptyMap(),
    logger: Logger? = null,
): Result<CommandAvailability>
