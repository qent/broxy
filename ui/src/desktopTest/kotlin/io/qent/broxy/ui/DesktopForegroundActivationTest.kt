package io.qent.broxy.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopForegroundActivationTest {
    @Test
    fun `requests app foreground when action is supported`() {
        val bridge = FakeMacOsForegroundBridge(isSupported = true)

        requestMacOsForegroundIfSupported(bridge)

        assertEquals(1, bridge.supportChecks)
        assertEquals(1, bridge.requestCalls)
        assertEquals(listOf(true), bridge.requestArguments)
    }

    @Test
    fun `does not request app foreground when action is not supported`() {
        val bridge = FakeMacOsForegroundBridge(isSupported = false)

        requestMacOsForegroundIfSupported(bridge)

        assertEquals(1, bridge.supportChecks)
        assertEquals(0, bridge.requestCalls)
    }

    @Test
    fun `swallows exceptions from foreground bridge`() {
        val supportFailureBridge =
            FakeMacOsForegroundBridge(
                isSupported = true,
                failOnSupportCheck = true,
            )
        val requestFailureBridge =
            FakeMacOsForegroundBridge(
                isSupported = true,
                failOnRequest = true,
            )

        requestMacOsForegroundIfSupported(supportFailureBridge)
        requestMacOsForegroundIfSupported(requestFailureBridge)

        assertEquals(1, supportFailureBridge.supportChecks)
        assertEquals(0, supportFailureBridge.requestCalls)
        assertEquals(1, requestFailureBridge.supportChecks)
        assertEquals(1, requestFailureBridge.requestCalls)
    }

    private class FakeMacOsForegroundBridge(
        private val isSupported: Boolean,
        private val failOnSupportCheck: Boolean = false,
        private val failOnRequest: Boolean = false,
    ) : MacOsForegroundBridge {
        var supportChecks: Int = 0
        var requestCalls: Int = 0
        val requestArguments: MutableList<Boolean> = mutableListOf()

        override fun isRequestForegroundSupported(): Boolean {
            supportChecks += 1
            if (failOnSupportCheck) {
                error("Support check failure")
            }
            return isSupported
        }

        override fun requestForeground(allWindows: Boolean) {
            requestCalls += 1
            requestArguments += allWindows
            if (failOnRequest) {
                error("Foreground request failure")
            }
        }
    }
}
