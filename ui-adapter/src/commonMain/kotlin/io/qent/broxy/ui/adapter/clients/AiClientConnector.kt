package io.qent.broxy.ui.adapter.clients

import io.qent.broxy.ui.adapter.models.UiAiClientNotice
import io.qent.broxy.ui.adapter.models.UiTransportConfig

data class AiClientDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val iconId: String,
    val infoUrl: String,
)

data class AiClientConnectionRequest(
    val httpEndpoint: String,
)

data class AiClientStatus(
    val isConnected: Boolean,
    val canConnect: Boolean,
    val notice: UiAiClientNotice? = null,
)

data class AiClientImportServer(
    val sourceServerId: String,
    val name: String,
    val enabled: Boolean,
    val transport: UiTransportConfig,
    val env: Map<String, String> = emptyMap(),
)

interface AiClientConnector {
    val descriptor: AiClientDescriptor

    suspend fun loadStatus(request: AiClientConnectionRequest): Result<AiClientStatus>

    suspend fun loadImportableServers(): Result<List<AiClientImportServer>>

    suspend fun connect(request: AiClientConnectionRequest): Result<Unit>

    suspend fun disconnect(request: AiClientConnectionRequest): Result<Unit>
}

expect fun provideAiClientConnectors(): List<AiClientConnector>
