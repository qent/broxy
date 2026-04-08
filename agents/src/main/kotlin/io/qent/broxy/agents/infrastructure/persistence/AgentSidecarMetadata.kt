package io.qent.broxy.agents.infrastructure.persistence

import io.qent.broxy.agents.AgentManualLaunchDefaults
import io.qent.broxy.agents.AgentSchedule
import io.qent.broxy.core.models.AgentToolReference
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import kotlinx.serialization.Serializable

@Serializable
data class AgentSidecarMetadata(
    val tools: List<ToolReference> = emptyList(),
    val agentTools: List<AgentToolReference> = emptyList(),
    val prompts: List<PromptReference>? = null,
    val resources: List<ResourceReference>? = null,
    val orderIndex: Int = 0,
    val schedule: AgentSchedule? = null,
    val manualLaunchDefaults: AgentManualLaunchDefaults? = null,
)
