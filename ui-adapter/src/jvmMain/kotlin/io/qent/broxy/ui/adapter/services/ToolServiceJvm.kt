package io.qent.broxy.ui.adapter.services

import io.qent.broxy.core.config.EnvironmentVariableResolver
import io.qent.broxy.core.mcp.DefaultMcpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.auth.AuthorizationStatusListener
import io.qent.broxy.core.mcp.auth.OAuthState
import io.qent.broxy.core.mcp.auth.OAuthStateStore
import io.qent.broxy.core.mcp.auth.restoreFromLocked
import io.qent.broxy.core.mcp.auth.toSnapshotLocked
import io.qent.broxy.core.utils.CommandLocator
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.toCore
import kotlinx.coroutines.runBlocking

actual suspend fun fetchServerCapabilities(
    config: UiMcpServerConfig,
    timeoutSeconds: Int,
    connectionRetryCount: Int,
    ignoreHttpsCertificateErrors: Boolean,
    logger: Logger?,
    authorizationStatusListener: AuthorizationStatusListener?,
): Result<ServerCapabilities> {
    // No outer timeout - let the internal timeouts handle it.
    // The timeoutSeconds parameter is used to configure internal timeouts.
    val connLogger = logger ?: ConsoleLogger
    connLogger.debug(
        "ToolService fetchServerCapabilities start id='${config.id}' timeoutSeconds=$timeoutSeconds retries=$connectionRetryCount",
    )
    val timeoutMillis = timeoutSeconds.coerceAtLeast(1).toLong() * 1_000L
    val authStore = OAuthStateStore(logger = connLogger)
    val resourceUrl =
        resolveAuthResourceUrl(config)
            ?.takeUnless { config.transport is UiStdioTransport && config.auth == null }
    val coreConfig = config.toCore()
    val authState =
        resourceUrl?.let {
            connLogger.debug("ToolService loading OAuth state for id='${config.id}' resource=$it")
            val state = OAuthState()
            authStore.load(config.id, it)?.let { snapshot ->
                state.restoreFromLocked(snapshot)
            }
            state
        }
    val conn =
        DefaultMcpServerConnection(
            config = coreConfig,
            logger = connLogger,
            authState = authState,
            authorizationStatusListener = authorizationStatusListener,
            authStateObserver = { state ->
                if (resourceUrl != null) {
                    connLogger.debug("ToolService saving OAuth state for id='${config.id}' resource=$resourceUrl")
                    val snapshot = runBlocking { state.toSnapshotLocked(resourceUrl) }
                    authStore.save(config.id, snapshot)
                }
            },
            maxRetries = connectionRetryCount.coerceAtLeast(1),
            ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors,
            initialCallTimeoutMillis = timeoutMillis,
            initialCapabilitiesTimeoutMillis = timeoutMillis,
            initialConnectTimeoutMillis = timeoutMillis,
        )
    return try {
        conn.getCapabilities(forceRefresh = true)
    } finally {
        runCatching { conn.disconnect() }
    }
}

actual suspend fun checkStdioCommandAvailability(
    command: String,
    env: Map<String, String>,
    logger: Logger?,
): Result<CommandAvailability> =
    runCatching {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            return@runCatching CommandAvailability(isAvailable = false, resolvedPath = null)
        }
        val resolver = EnvironmentVariableResolver(logger = logger)
        val pathOverride =
            env.entries
                .firstOrNull { it.key.equals("PATH", ignoreCase = true) }
                ?.value
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching { resolver.resolveString(raw) }.getOrNull()
                }
        val resolvedPath = CommandLocator.resolveCommand(trimmed, pathOverride = pathOverride, logger = logger)
        CommandAvailability(isAvailable = resolvedPath != null, resolvedPath = resolvedPath)
    }
