package io.qent.broxy.ui.adapter.remote

import io.qent.broxy.ui.adapter.models.UiRemoteConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface RemoteConnector {
    val state: StateFlow<UiRemoteConnectionState>

    fun start()
    fun updateServerIdentifier(value: String)
    fun beginAuthorization()
    fun disconnect()
}

class NoOpRemoteConnector(
    initial: UiRemoteConnectionState
) : RemoteConnector {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<UiRemoteConnectionState> = _state

    override fun start() {}
    override fun updateServerIdentifier(value: String) {
        _state.value = _state.value.copy(serverIdentifier = value)
    }
    override fun beginAuthorization() {}
    override fun disconnect() {}
}
