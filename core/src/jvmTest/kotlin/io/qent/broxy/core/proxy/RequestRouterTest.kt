@file:Suppress("MaxLineLength")

package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class Srv(
    override val serverId: String,
    override val config: McpServerConfig,
    private val handler: (String) -> JsonObject,
) : McpServerConnection {
    override var status: ServerStatus = ServerStatus.Running
        private set
    var callToolCalls: Int = 0

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() = Unit

    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> = Result.success(ServerCapabilities())

    override suspend fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Result<JsonElement> {
        callToolCalls += 1
        return Result.success(
            buildJsonObject {
                put("content", buildJsonArray { })
                put("structuredContent", handler(toolName))
                put("isError", JsonPrimitive(false))
                put("_meta", JsonObject(emptyMap()))
            },
        )
    }

    override suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> = Result.failure(UnsupportedOperationException())

    override suspend fun readResource(uri: String): Result<JsonObject> = Result.failure(UnsupportedOperationException())
}

class RequestRouterTest {
    private fun cfg(id: String) = McpServerConfig(id, "srv-$id", TransportConfig.HttpTransport("http://$id"))

    @Test
    fun enforces_allowed_and_routes_by_prefix() =
        runBlocking {
            val s1 =
                Srv("s1", cfg("s1")) { tool ->
                    buildJsonObject {
                        put("server", "s1")
                        put("tool", tool)
                    }
                }
            val s2 =
                Srv("s2", cfg("s2")) { tool ->
                    buildJsonObject {
                        put("server", "s2")
                        put("tool", tool)
                    }
                }
            val router =
                DefaultRequestDispatcher(
                    servers = listOf(s1, s2),
                    allowedPrefixedTools = { setOf("s2_echo") },
                )

            val denied = router.dispatchToolCall(ToolCallRequest("s1_echo"))
            assertTrue(denied.isFailure)

            val ok = router.dispatchToolCall(ToolCallRequest("s2_echo"))
            assertTrue(ok.isSuccess)
            assertTrue(ok.getOrThrow().toString().contains("\"server\":\"s2\""))
        }

    @Test
    fun rejects_invalid_prefixed_tool_name() =
        runBlocking {
            val s1 =
                Srv("s1", cfg("s1")) { tool ->
                    buildJsonObject {
                        put("server", "s1")
                        put("tool", tool)
                    }
                }
            val router = DefaultRequestDispatcher(servers = listOf(s1))

            val result = router.dispatchToolCall(ToolCallRequest("invalid"))

            assertTrue(result.isFailure)
            assertIs<IllegalArgumentException>(result.exceptionOrNull())
            assertEquals(0, s1.callToolCalls)
        }

    @Test
    fun routes_normalized_registry_server_prefix_to_original_server_id() =
        runBlocking {
            val registryServerId = "io.qent.broxy/time"
            val server =
                Srv(registryServerId, cfg(registryServerId)) { tool ->
                    buildJsonObject {
                        put("server", registryServerId)
                        put("tool", tool)
                    }
                }
            val router =
                DefaultRequestDispatcher(
                    servers = listOf(server),
                    allowedPrefixedTools = { setOf("time_echo") },
                )

            val ok = router.dispatchToolCall(ToolCallRequest("time_echo"))

            assertTrue(ok.isSuccess)
            assertTrue(ok.getOrThrow().toString().contains("\"server\":\"$registryServerId\""))
        }
}
