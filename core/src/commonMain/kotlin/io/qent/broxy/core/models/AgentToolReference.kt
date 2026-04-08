package io.qent.broxy.core.models

import kotlinx.serialization.Serializable

@Serializable
data class AgentToolReference(
    val agentId: String,
    val enabled: Boolean = true,
)
