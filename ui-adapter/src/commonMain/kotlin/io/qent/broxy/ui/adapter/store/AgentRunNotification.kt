package io.qent.broxy.ui.adapter.store

import io.qent.broxy.ui.adapter.models.UiAgentRunStatus

data class AgentRunNotification(
    val agentId: String,
    val agentName: String,
    val status: UiAgentRunStatus,
    val message: String,
)
