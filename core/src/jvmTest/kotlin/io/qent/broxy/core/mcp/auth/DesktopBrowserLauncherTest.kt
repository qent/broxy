package io.qent.broxy.core.mcp.auth

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopBrowserLauncherTest {
    @Test
    fun open_returns_failure_when_url_is_invalid() {
        val logger = CapturingLogger()
        val launcher = DesktopBrowserLauncher(logger)

        val result = launcher.open("http://example.com/invalid url")

        assertTrue(result.isFailure)
        assertTrue(logger.messages.any { it.contains("Failed to open browser") })
    }
}
