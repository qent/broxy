package io.qent.broxy.ui.adapter.headless

import io.qent.broxy.core.config.JsonConfigurationRepository
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.proxy.runtime.createStdioProxyController
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.StdErrLogger
import java.nio.file.Paths

/**
 * Starts the proxy in STDIO inbound mode and blocks until the STDIO session ends.
 * This is intended to be called from a packaged Desktop app binary with
 * no CLI args so that Claude Desktop can spawn it as an MCP STDIO server
 * without a separate CLI jar. The preset is resolved from:
 *
 * 1) [presetIdOverride] (if provided),
 * 2) `defaultPresetId` in `mcp.json`,
 * 3) the only available preset (if exactly one exists).
 *
 * @param presetIdOverride Optional preset ID override
 * @param configDir Optional configuration directory (defaults to ~/.config/broxy)
 */
fun runStdioProxy(presetIdOverride: String? = null, configDir: String? = null): Result<Unit> = runCatching {
    val repo = if (configDir.isNullOrBlank())
        JsonConfigurationRepository(logger = StdErrLogger)
    else
        JsonConfigurationRepository(baseDir = Paths.get(configDir), logger = StdErrLogger)
    val cfg = repo.loadMcpConfig()
    val effectivePresetId = presetIdOverride?.takeIf { it.isNotBlank() }
        ?: cfg.defaultPresetId?.takeIf { it.isNotBlank() }
        ?: repo.listPresets().singleOrNull()?.id
        ?: error("No default preset configured. Open broxy UI and select a preset (Proxy tab) to set it as default.")
    val preset = repo.loadPreset(effectivePresetId)

    val logger = CollectingLogger(delegate = StdErrLogger)
    val controller = createStdioProxyController(logger)
    val lifecycle = ProxyLifecycle(controller, logger)
    val inbound = TransportConfig.StdioTransport(command = "", args = emptyList())
    val r = lifecycle.start(cfg, preset, inbound)
    if (r.isFailure) throw r.exceptionOrNull() ?: IllegalStateException("Failed to start proxy")
    // For STDIO inbound, controller.start() blocks inside InboundServers until the session ends.
    // When it returns successfully, we treat it as a graceful exit.
    Unit
}

fun logStdioInfo(message: String) {
    StdErrLogger.info(message)
}
