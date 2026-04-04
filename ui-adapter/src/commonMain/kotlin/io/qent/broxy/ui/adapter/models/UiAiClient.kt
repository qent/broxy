package io.qent.broxy.ui.adapter.models

data class UiAiClient(
    val id: String,
    val name: String,
    val description: String,
    val iconId: String,
    val infoUrl: String,
    val isConnected: Boolean,
    val canConnect: Boolean,
    val notice: UiAiClientNotice? = null,
)

sealed interface UiAiClientNotice {
    val severity: UiAiClientNoticeSeverity
}

data class UiAiClientMissingConfigNotice(
    val clientName: String,
    override val severity: UiAiClientNoticeSeverity = UiAiClientNoticeSeverity.Error,
) : UiAiClientNotice

data class UiAiClientBroxyConfigMismatchNotice(
    val configuredUrl: String?,
    override val severity: UiAiClientNoticeSeverity = UiAiClientNoticeSeverity.Warning,
) : UiAiClientNotice

data class UiAiClientStatusLoadFailedNotice(
    val details: String?,
    override val severity: UiAiClientNoticeSeverity = UiAiClientNoticeSeverity.Error,
) : UiAiClientNotice

enum class UiAiClientNoticeSeverity {
    Warning,
    Error,
}
