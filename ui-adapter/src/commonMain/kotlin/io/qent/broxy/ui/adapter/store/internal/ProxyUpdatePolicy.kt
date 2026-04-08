package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.models.UiProxyStatus

internal fun shouldApplyProxyUpdates(
    proxyStatus: UiProxyStatus,
    proxyRunning: Boolean,
): Boolean =
    proxyRunning ||
        proxyStatus is UiProxyStatus.Starting ||
        proxyStatus is UiProxyStatus.Running
