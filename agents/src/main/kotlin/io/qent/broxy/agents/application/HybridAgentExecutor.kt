package io.qent.broxy.agents.application

import io.qent.broxy.agents.AgentExecutionRequest
import io.qent.broxy.agents.AgentExecutionResult
import io.qent.broxy.agents.AgentExecutor
import io.qent.broxy.agents.AgentRuntime

class HybridAgentExecutor(
    private val langChainExecutor: AgentExecutor,
    private val codexExecutor: AgentExecutor,
) : AgentExecutor {
    override suspend fun execute(request: AgentExecutionRequest): Result<AgentExecutionResult> =
        when (request.runtime) {
            AgentRuntime.LANGCHAIN -> langChainExecutor.execute(request)
            AgentRuntime.CODEX_CLI -> codexExecutor.execute(request)
        }
}
