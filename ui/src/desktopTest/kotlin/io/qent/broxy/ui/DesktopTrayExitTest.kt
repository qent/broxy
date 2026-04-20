package io.qent.broxy.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopTrayExitTest {
    @Test
    fun `requestExit calls exit before cleanup runs`() {
        var hideWindowCalls = 0
        var stopStoreCalls = 0
        var disposeTrayCalls = 0
        var exitApplicationCalls = 0
        var scheduledCleanup: (() -> Unit)? = null
        val coordinator =
            DesktopShutdownCoordinator(
                hideWindow = { hideWindowCalls += 1 },
                stopStore = { stopStoreCalls += 1 },
                disposeTray = { disposeTrayCalls += 1 },
                exitApplication = { exitApplicationCalls += 1 },
                cleanupDispatcher = { task -> scheduledCleanup = task },
            )

        coordinator.requestExit()

        assertEquals(1, hideWindowCalls)
        assertEquals(1, exitApplicationCalls)
        assertEquals(0, stopStoreCalls)
        assertEquals(0, disposeTrayCalls)

        scheduledCleanup?.invoke()
        assertEquals(1, stopStoreCalls)
        assertEquals(1, disposeTrayCalls)
    }

    @Test
    fun `requestExit and dispose are idempotent`() {
        var hideWindowCalls = 0
        var stopStoreCalls = 0
        var disposeTrayCalls = 0
        var exitApplicationCalls = 0
        var scheduledCleanupCount = 0
        val coordinator =
            DesktopShutdownCoordinator(
                hideWindow = { hideWindowCalls += 1 },
                stopStore = { stopStoreCalls += 1 },
                disposeTray = { disposeTrayCalls += 1 },
                exitApplication = { exitApplicationCalls += 1 },
                cleanupDispatcher = { scheduledCleanupCount += 1 },
            )

        coordinator.requestExit()
        coordinator.requestExit()
        coordinator.dispose()
        coordinator.dispose()

        assertEquals(1, hideWindowCalls)
        assertEquals(1, exitApplicationCalls)
        assertEquals(1, scheduledCleanupCount)
        assertEquals(0, stopStoreCalls)
        assertEquals(0, disposeTrayCalls)
    }

    @Test
    fun `cleanup failure does not prevent exit and tray dispose`() {
        var hideWindowCalls = 0
        var stopStoreCalls = 0
        var disposeTrayCalls = 0
        var exitApplicationCalls = 0
        val coordinator =
            DesktopShutdownCoordinator(
                hideWindow = { hideWindowCalls += 1 },
                stopStore = {
                    stopStoreCalls += 1
                    error("stop failed")
                },
                disposeTray = { disposeTrayCalls += 1 },
                exitApplication = { exitApplicationCalls += 1 },
                cleanupDispatcher = { task -> task() },
            )

        coordinator.requestExit()

        assertEquals(1, hideWindowCalls)
        assertEquals(1, exitApplicationCalls)
        assertEquals(1, stopStoreCalls)
        assertEquals(1, disposeTrayCalls)
    }
}
