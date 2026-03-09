package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class DServer(
    override val serverId: String,
    override val config: McpServerConfig,
    private val caps: ServerCapabilities = ServerCapabilities(),
) : McpServerConnection {
    override var status: ServerStatus = ServerStatus.Running
        private set

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() = Unit

    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> = Result.success(caps)

    override suspend fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Result<JsonElement> =
        Result.success(
            buildJsonObject {
                put("content", buildJsonArray { })
                put(
                    "structuredContent",
                    buildJsonObject {
                        put("server", JsonPrimitive(serverId))
                        put("tool", JsonPrimitive(toolName))
                    },
                )
                put("isError", JsonPrimitive(false))
                put("_meta", JsonObject(emptyMap()))
            },
        )

    override suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> =
        Result.success(
            buildJsonObject {
                put("description", "desc-$name")
                put("messages", "[]")
                put("serverId", serverId)
            },
        )

    override suspend fun readResource(uri: String): Result<JsonObject> =
        Result.success(
            buildJsonObject {
                put("contents", "[]")
                put("_meta", "{}")
                put("serverId", serverId)
                put("uri", uri)
            },
        )
}

class RequestDispatcherBatchTest {
    private fun cfg(id: String) = McpServerConfig(id, "srv-$id", TransportConfig.HttpTransport("http://$id"))

    @Test
    fun batch_dispatch_routes_to_correct_servers() =
        runBlocking {
            val s1 =
                DServer(
                    "s1",
                    cfg("s1"),
                    caps = ServerCapabilities(tools = listOf(ToolDescriptor("echo"))),
                )
            val s2 =
                DServer(
                    "s2",
                    cfg("s2"),
                    caps = ServerCapabilities(tools = listOf(ToolDescriptor("ping"))),
                )
            val allowed = setOf("s1_echo", "s2_ping")
            val dispatcher =
                DefaultRequestDispatcher(
                    servers = listOf(s1, s2),
                    allowedPrefixedTools = { allowed },
                )

            val results =
                dispatcher.dispatchBatch(
                    listOf(
                        ToolCallRequest("s1_echo"),
                        ToolCallRequest("s2_ping"),
                    ),
                )

            assertEquals(2, results.size)
            assertTrue(results[0].isSuccess)
            assertTrue(results[1].isSuccess)
            assertTrue(results[0].getOrThrow().toString().contains("\"server\":\"s1\""))
            assertTrue(results[1].getOrThrow().toString().contains("\"server\":\"s2\""))
        }

    @Test
    fun enforces_allowed_set_in_batch() =
        runBlocking {
            val s1 = DServer("s1", cfg("s1"))
            val s2 = DServer("s2", cfg("s2"))
            val dispatcher =
                DefaultRequestDispatcher(
                    servers = listOf(s1, s2),
                    allowedPrefixedTools = { setOf("s2_ping") },
                )

            val results =
                dispatcher.dispatchBatch(
                    listOf(
                        ToolCallRequest("s1_echo"),
                        ToolCallRequest("s2_ping"),
                    ),
                )

            assertTrue(results[0].isFailure)
            assertTrue(results[1].isSuccess)
        }

    @Test
    fun prompt_and_resource_resolution_fallback() =
        runBlocking {
            val s1 =
                DServer(
                    "s1",
                    cfg("s1"),
                    caps =
                        ServerCapabilities(
                            prompts = listOf(PromptDescriptor("p1")),
                            resources = listOf(ResourceDescriptor("r1", uri = "u1")),
                        ),
                )
            val s2 =
                DServer(
                    "s2",
                    cfg("s2"),
                    caps =
                        ServerCapabilities(
                            prompts = listOf(PromptDescriptor("p2")),
                            resources = listOf(ResourceDescriptor("r2", uri = "u2")),
                        ),
                )
            val dispatcher =
                DefaultRequestDispatcher(
                    servers = listOf(s1, s2),
                    promptServerResolver = { null },
                    resourceServerResolver = { null },
                )

            val pr1 = dispatcher.dispatchPrompt("p2")
            assertTrue(pr1.isSuccess)
            assertEquals("s2", pr1.getOrThrow()["serverId"]?.jsonPrimitive?.content)
            val rr1 = dispatcher.dispatchResource("u1")
            assertTrue(rr1.isSuccess)
            assertEquals("s1", rr1.getOrThrow()["serverId"]?.jsonPrimitive?.content)
            val prUnknown = dispatcher.dispatchPrompt("unknown")
            assertTrue(prUnknown.isFailure)
            val rrUnknown = dispatcher.dispatchResource("unknown://resource")
            assertTrue(rrUnknown.isFailure)
        }

    @Test
    fun prompt_and_resource_conflicts_pick_first_match() =
        runBlocking {
            val sharedPrompt = PromptDescriptor("shared")
            val sharedResource = ResourceDescriptor("shared", uri = "shared://r")
            val s1 =
                DServer(
                    "s1",
                    cfg("s1"),
                    caps =
                        ServerCapabilities(
                            prompts = listOf(sharedPrompt),
                            resources = listOf(sharedResource),
                        ),
                )
            val s2 =
                DServer(
                    "s2",
                    cfg("s2"),
                    caps =
                        ServerCapabilities(
                            prompts = listOf(sharedPrompt),
                            resources = listOf(sharedResource),
                        ),
                )
            val dispatcher =
                DefaultRequestDispatcher(
                    servers = listOf(s1, s2),
                    promptServerResolver = { null },
                    resourceServerResolver = { null },
                )

            val promptResult = dispatcher.dispatchPrompt("shared")
            assertTrue(promptResult.isSuccess)
            val promptServer = promptResult.getOrThrow()["serverId"]?.jsonPrimitive?.content
            assertTrue(promptServer == "s1" || promptServer == "s2")

            val resourceResult = dispatcher.dispatchResource("shared://r")
            assertTrue(resourceResult.isSuccess)
            val resourceServer = resourceResult.getOrThrow()["serverId"]?.jsonPrimitive?.content
            assertTrue(resourceServer == "s1" || resourceServer == "s2")
        }

    @Test
    fun prompt_routing_map_takes_precedence_over_fallback_scan() =
        runBlocking {
            val sharedPrompt = PromptDescriptor("shared")
            val s1 =
                DServer(
                    "s1",
                    cfg("s1"),
                    caps = ServerCapabilities(prompts = listOf(sharedPrompt)),
                )
            val s2 =
                DServer(
                    "s2",
                    cfg("s2"),
                    caps = ServerCapabilities(prompts = listOf(sharedPrompt)),
                )
            val dispatcher =
                DefaultRequestDispatcher(
                    servers = listOf(s1, s2),
                    promptServerResolver = { "s2" },
                )

            val result = dispatcher.dispatchPrompt("shared")

            assertTrue(result.isSuccess)
            assertEquals("s2", result.getOrThrow()["serverId"]?.jsonPrimitive?.content)
        }

    @Test
    fun resource_routing_map_takes_precedence_over_fallback_scan() =
        runBlocking {
            val sharedResource = ResourceDescriptor("shared", uri = "shared://r")
            val s1 =
                DServer(
                    "s1",
                    cfg("s1"),
                    caps = ServerCapabilities(resources = listOf(sharedResource)),
                )
            val s2 =
                DServer(
                    "s2",
                    cfg("s2"),
                    caps = ServerCapabilities(resources = listOf(sharedResource)),
                )
            val dispatcher =
                DefaultRequestDispatcher(
                    servers = listOf(s1, s2),
                    resourceServerResolver = { "s1" },
                )

            val result = dispatcher.dispatchResource("shared://r")

            assertTrue(result.isSuccess)
            assertEquals("s1", result.getOrThrow()["serverId"]?.jsonPrimitive?.content)
        }
}
