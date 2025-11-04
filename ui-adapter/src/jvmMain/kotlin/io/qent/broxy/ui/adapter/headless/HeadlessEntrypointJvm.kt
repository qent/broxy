package io.qent.broxy.ui.adapter.headless

import io.qent.broxy.core.config.JsonConfigurationRepository
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.StdErrLogger
import io.qent.broxy.ui.adapter.proxy.createStdioProxyController
import java.nio.file.Paths

/**
 * Starts the proxy in STDIO inbound mode and blocks until the STDIO session ends.
 * This is intended to be called from a packaged Desktop app binary with
 * flags like `--stdio-proxy --preset-id <id>` so that Claude Desktop can
 * spawn it as an MCP STDIO server without a separate CLI jar.
 *
 * @param presetId Preset to apply for filtering and routing
 * @param configDir Optional configuration directory (defaults to ~/.config/broxy)
 */
fun runStdioProxy(presetId: String, configDir: String? = null): Result<Unit> = runCatching {
    val repo = if (configDir.isNullOrBlank())
        JsonConfigurationRepository(logger = StdErrLogger)
    else
        JsonConfigurationRepository(baseDir = Paths.get(configDir), logger = StdErrLogger)
    val cfg = repo.loadMcpConfig()
    val preset = repo.loadPreset(presetId)

    val controller = createStdioProxyController()
    val inbound = TransportConfig.StdioTransport(command = "", args = emptyList())
    val r = controller.start(
        servers = cfg.servers,
        preset = preset,
        inbound = inbound,
        callTimeoutSeconds = cfg.requestTimeoutSeconds,
        capabilitiesTimeoutSeconds = cfg.capabilitiesTimeoutSeconds
    )
    if (r.isFailure) throw r.exceptionOrNull() ?: IllegalStateException("Failed to start proxy")
    // For STDIO inbound, controller.start() blocks inside InboundServers until the session ends.
    // When it returns successfully, we treat it as a graceful exit.
    Unit
}

fun logStdioInfo(message: String) {
    StdErrLogger.info(message)
}
