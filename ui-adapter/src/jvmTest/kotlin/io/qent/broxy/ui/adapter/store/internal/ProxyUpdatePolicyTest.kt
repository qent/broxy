package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.models.UiProxyStatus
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyUpdatePolicyTest {
    @org.junit.Test
    fun shouldApplyProxyUpdatesWhenStartingOrRunning() {
        assertTrue(shouldApplyProxyUpdates(UiProxyStatus.Starting, proxyRunning = false))
        assertTrue(shouldApplyProxyUpdates(UiProxyStatus.Running, proxyRunning = false))
        assertTrue(shouldApplyProxyUpdates(UiProxyStatus.Stopped, proxyRunning = true))
    }

    @org.junit.Test
    fun shouldNotApplyProxyUpdatesWhenNotRunning() {
        assertFalse(shouldApplyProxyUpdates(UiProxyStatus.Stopped, proxyRunning = false))
        assertFalse(shouldApplyProxyUpdates(UiProxyStatus.Stopping, proxyRunning = false))
        assertFalse(shouldApplyProxyUpdates(UiProxyStatus.Error("boom"), proxyRunning = false))
    }
}
