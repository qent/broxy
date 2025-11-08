package io.qent.broxy.core.models

import kotlinx.serialization.Serializable

@Serializable
data class PromptReference(
    val serverId: String,
    val promptName: String,
    val enabled: Boolean = true
)
