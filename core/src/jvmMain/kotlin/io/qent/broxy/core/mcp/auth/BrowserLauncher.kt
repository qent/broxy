package io.qent.broxy.core.mcp.auth

import io.qent.broxy.core.utils.Logger
import java.awt.Desktop
import java.net.URI

fun interface BrowserLauncher {
    fun open(url: String): Result<Unit>
}

class DesktopBrowserLauncher(
    private val logger: Logger,
) : BrowserLauncher {
    override fun open(url: String): Result<Unit> =
        runCatching {
            logger.debug("Launching browser for OAuth URL.")
            if (!Desktop.isDesktopSupported()) {
                error("Desktop browsing is not supported on this platform.")
            }
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                error("Desktop browsing is not supported on this platform.")
            }
            desktop.browse(URI(url))
        }.onFailure { ex ->
            logger.warn("Failed to open browser for OAuth authorization URL.", ex)
        }
}
