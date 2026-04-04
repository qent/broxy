package io.qent.broxy.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class ServersScreenLogicTest {
    @Test
    fun `resolveCatalogInstalledServerScrollDecision returns scroll target when pending server is visible`() {
        val decision =
            resolveCatalogInstalledServerScrollDecision(
                pendingCatalogInstalledServerId = "time",
                pendingCatalogInstalledServerRequestId = 1L,
                lastHandledPendingCatalogInstalledServerRequestId = null,
                serverIds = listOf("time", "existing"),
            )

        assertEquals(true, decision.shouldScroll)
        assertEquals(0, decision.targetIndex)
    }

    @Test
    fun `resolveCatalogInstalledServerScrollDecision skips when pending server is not visible yet`() {
        val decision =
            resolveCatalogInstalledServerScrollDecision(
                pendingCatalogInstalledServerId = "time",
                pendingCatalogInstalledServerRequestId = 1L,
                lastHandledPendingCatalogInstalledServerRequestId = null,
                serverIds = listOf("existing"),
            )

        assertEquals(false, decision.shouldScroll)
    }

    @Test
    fun `resolveCatalogInstalledServerScrollDecision skips repeated request after consume`() {
        val decision =
            resolveCatalogInstalledServerScrollDecision(
                pendingCatalogInstalledServerId = "time",
                pendingCatalogInstalledServerRequestId = 4L,
                lastHandledPendingCatalogInstalledServerRequestId = 4L,
                serverIds = listOf("time", "existing"),
            )

        assertEquals(false, decision.shouldScroll)
    }
}
