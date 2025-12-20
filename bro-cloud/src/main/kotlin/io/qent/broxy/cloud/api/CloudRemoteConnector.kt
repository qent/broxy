package io.qent.broxy.cloud.api

import kotlinx.coroutines.flow.StateFlow

interface CloudRemoteConnector {
    val state: StateFlow<CloudRemoteState>

    fun start()

    fun beginAuthorization()

    fun connect()

    fun disconnect()

    fun logout()

    fun onProxyRunningChanged(running: Boolean)
}
