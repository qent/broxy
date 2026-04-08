package io.qent.broxy.agents.runtime.mcp

import io.qent.broxy.agents.AgentDefinition
import io.qent.broxy.agents.AgentExecutionRequest
import io.qent.broxy.agents.AgentExecutionResult
import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.agents.AgentFileSystemSettings
import io.qent.broxy.agents.AgentLlmConfig
import io.qent.broxy.agents.AgentProviderSettings
import io.qent.broxy.agents.AgentRuntime
import io.qent.broxy.agents.LlmProvider
import io.qent.broxy.core.models.AgentToolReference
import io.qent.broxy.core.models.McpServersConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentToolsMcpConnectionTest {
    @Test
    fun buildAgentToolsConnection_executesNestedAgentWithStringInput() =
        runTest {
            val parent = agent(id = "agent-a", agentTools = listOf(AgentToolReference(agentId = "agent-b")))
            val target = agent(id = "agent-b")
            var nestedRequest: AgentExecutionRequest? = null
            val request =
                baseRequest(
                    agent = parent,
                    resolver = { id -> if (id == target.id) target else null },
                    nestedExecutor = { nested ->
                        nestedRequest = nested
                        Result.success(AgentExecutionResult(response = "nested-ok"))
                    },
                )

            val resolved = buildAgentToolsConnection(request)
            assertNotNull(resolved)
            assertEquals(1, resolved.toolRefs.size)
            val toolName = resolved.toolRefs.single().toolName
            val call =
                resolved.connection.callTool(
                    toolName = toolName,
                    arguments = JsonObject(mapOf("input" to JsonPrimitive("hello nested"))),
                )

            assertEquals("nested-ok", call.getOrThrow().toString().trim('"'))
            val nested = assertNotNull(nestedRequest)
            assertEquals("hello nested", nested.prompt)
            assertEquals("agent-b", nested.agent.id)
            assertEquals(listOf("agent-a"), nested.agentInvocationStack)
        }

    @Test
    fun buildAgentToolsConnection_skipsMissingReferencedAgents() {
        val parent = agent(id = "agent-a", agentTools = listOf(AgentToolReference(agentId = "missing")))
        val request =
            baseRequest(
                agent = parent,
                resolver = { null },
                nestedExecutor = { Result.success(AgentExecutionResult("unused")) },
            )

        val resolved = buildAgentToolsConnection(request)

        assertNull(resolved)
    }

    @Test
    fun buildAgentToolsConnection_blocksCycles() =
        runTest {
            val parent = agent(id = "agent-b", agentTools = listOf(AgentToolReference(agentId = "agent-a")))
            var nestedCalls = 0
            val request =
                baseRequest(
                    agent = parent,
                    stack = listOf("agent-a"),
                    resolver = { id -> if (id == "agent-a") agent("agent-a") else null },
                    nestedExecutor = {
                        nestedCalls += 1
                        Result.success(AgentExecutionResult("unexpected"))
                    },
                )
            val resolved = assertNotNull(buildAgentToolsConnection(request))
            val toolName = resolved.toolRefs.single().toolName

            val result =
                resolved.connection.callTool(
                    toolName = toolName,
                    arguments = JsonObject(mapOf("input" to JsonPrimitive("trigger cycle"))),
                )

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("cycle", ignoreCase = true),
            )
            assertEquals(0, nestedCalls)
        }

    @Test
    fun buildAgentToolsConnection_doesNotEnforceDepthLimitWithoutCycle() =
        runTest {
            val deepStack = List(200) { index -> "agent-$index" }
            val parent = agent(id = "agent-root", agentTools = listOf(AgentToolReference(agentId = "agent-leaf")))
            var nestedCalls = 0
            val request =
                baseRequest(
                    agent = parent,
                    stack = deepStack,
                    resolver = { id -> if (id == "agent-leaf") agent("agent-leaf") else null },
                    nestedExecutor = {
                        nestedCalls += 1
                        Result.success(AgentExecutionResult("leaf-ok"))
                    },
                )
            val resolved = assertNotNull(buildAgentToolsConnection(request))
            val toolName = resolved.toolRefs.single().toolName

            val result =
                resolved.connection.callTool(
                    toolName = toolName,
                    arguments = JsonObject(mapOf("input" to JsonPrimitive("deep"))),
                )

            assertEquals("leaf-ok", result.getOrThrow().toString().trim('"'))
            assertEquals(1, nestedCalls)
        }

    private fun baseRequest(
        agent: AgentDefinition,
        stack: List<String> = emptyList(),
        resolver: (String) -> AgentDefinition?,
        nestedExecutor: suspend (AgentExecutionRequest) -> Result<AgentExecutionResult>,
    ): AgentExecutionRequest =
        AgentExecutionRequest(
            agent = agent,
            runtime = AgentRuntime.LANGCHAIN,
            llm = AgentLlmConfig(provider = LlmProvider.OPENAI, model = "gpt-4o-mini", temperature = 0.0),
            codex = null,
            prompt = "prompt",
            fileSystem = AgentFileSystemSettings(path = "/tmp/broxy/agents", access = AgentFileSystemAccess.NONE),
            providerSettings = AgentProviderSettings(),
            mcpConfig = McpServersConfig(),
            apiKey = "test-openai-key",
            apiKeys = mapOf(LlmProvider.OPENAI to "test-openai-key"),
            agentInvocationStack = stack,
            resolveAgentById = resolver,
            executeNestedAgent = nestedExecutor,
        )

    private fun agent(
        id: String,
        agentTools: List<AgentToolReference> = emptyList(),
    ): AgentDefinition =
        AgentDefinition(
            id = id,
            name = id,
            systemPrompt = "System prompt for $id",
            agentTools = agentTools,
        )
}
