package io.qent.broxy.core.contracts

import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.DefaultNamespaceManager
import io.qent.broxy.core.proxy.DefaultRequestDispatcher
import io.qent.broxy.core.proxy.ToolCallRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class ContractServer(
    override val serverId: String,
    override val config: McpServerConfig,
    private val capabilities: ServerCapabilities,
) : McpServerConnection {
    override var status: ServerStatus = ServerStatus.Running
        private set
    var promptCalls: Int = 0
    var resourceCalls: Int = 0

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() = Unit

    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> {
        val snapshot = capabilities
        return Result.success(snapshot)
    }

    override suspend fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Result<JsonElement> = Result.success(JsonObject(emptyMap()))

    override suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> {
        promptCalls += 1
        return Result.success(
            buildJsonObject {
                put("serverId", JsonPrimitive(serverId))
                put("name", JsonPrimitive(name))
            },
        )
    }

    override suspend fun readResource(uri: String): Result<JsonObject> {
        resourceCalls += 1
        return Result.success(
            buildJsonObject {
                put("serverId", JsonPrimitive(serverId))
                put("uri", JsonPrimitive(uri))
            },
        )
    }
}

class CoreContractsTest {
    @Test
    fun namespace_requires_prefixed_tool_name() {
        val namespace = DefaultNamespaceManager()
        assertFailsWith<IllegalArgumentException> {
            namespace.parsePrefixedToolName("invalid")
        }
    }

    @Test
    fun empty_allow_list_denies_tool_calls() =
        runBlocking {
            val server =
                ContractServer(
                    serverId = "s1",
                    config = McpServerConfig("s1", "s1", TransportConfig.HttpTransport("http://s1")),
                    capabilities = ServerCapabilities(),
                )
            val dispatcher =
                DefaultRequestDispatcher(
                    servers = listOf(server),
                    allowedPrefixedTools = { emptySet() },
                    allowAllWhenNoAllowedTools = false,
                )

            val result = dispatcher.dispatchToolCall(ToolCallRequest("s1_echo"))

            assertTrue(result.isFailure)
        }

    @Test
    fun prompt_and_resource_fallback_scans_downstream_capabilities() =
        runBlocking {
            val server =
                ContractServer(
                    serverId = "s1",
                    config = McpServerConfig("s1", "s1", TransportConfig.HttpTransport("http://s1")),
                    capabilities =
                        ServerCapabilities(
                            prompts = listOf(PromptDescriptor("p1")),
                            resources = listOf(ResourceDescriptor("r1", uri = "u1")),
                        ),
                )
            val dispatcher =
                DefaultRequestDispatcher(
                    servers = listOf(server),
                    promptServerResolver = { null },
                    resourceServerResolver = { null },
                )

            val prompt = dispatcher.dispatchPrompt("p1")
            val resource = dispatcher.dispatchResource("u1")

            assertTrue(prompt.isSuccess)
            assertTrue(resource.isSuccess)
            assertEquals("s1", prompt.getOrThrow()["serverId"]?.jsonPrimitive?.content)
            assertEquals("s1", resource.getOrThrow()["serverId"]?.jsonPrimitive?.content)
            assertEquals(1, server.promptCalls)
            assertEquals(1, server.resourceCalls)
        }
}
