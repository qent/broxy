package io.qent.broxy.ui.adapter.models

sealed interface UiTransportDraft
data class UiStdioDraft(val command: String, val args: List<String> = emptyList()) : UiTransportDraft
data class UiHttpDraft(val url: String, val headers: Map<String, String> = emptyMap()) : UiTransportDraft
data class UiStreamableHttpDraft(val url: String, val headers: Map<String, String> = emptyMap()) : UiTransportDraft
data class UiWebSocketDraft(val url: String) : UiTransportDraft
