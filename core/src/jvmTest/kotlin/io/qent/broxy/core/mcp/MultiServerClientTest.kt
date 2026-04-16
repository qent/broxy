@file:Suppress("MaxLineLength")

package io.qent.broxy.core.mcp

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

private class MCServer(
    override val serverId: String,
    override val config: McpServerConfig,
    var caps: Result<ServerCapabilities>,
    private val toolHandler: (String, JsonObject) -> Result<JsonElement>,
) : McpServerConnection {
    override var status: ServerStatus = ServerStatus.Running
        private set

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun disconnect() = Unit

    override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> = caps

    override suspend fun callTool(
        toolName: String,
        arguments: JsonObject,
    ): Result<JsonElement> = toolHandler(toolName, arguments)

    override suspend fun getPrompt(
        name: String,
        arguments: Map<String, String>?,
    ): Result<JsonObject> = Result.failure(UnsupportedOperationException())

    override suspend fun readResource(uri: String): Result<JsonObject> = Result.failure(UnsupportedOperationException())
}

class MultiServerClientTest {
    private fun cfg(id: String) = McpServerConfig(id, "srv-$id", TransportConfig.HttpTransport("http://$id"))

    @Test
    fun fetch_all_caps_skips_failures() {
        runBlocking {
            val s1 =
                MCServer(
                    serverId = "s1",
                    config = cfg("s1"),
                    caps = Result.success(ServerCapabilities(tools = listOf(ToolDescriptor("t1")))),
                ) { _, _ ->
                    Result.success(
                        buildJsonObject {
                            put("content", buildJsonArray { })
                            put("structuredContent", buildJsonObject { put("ok", JsonPrimitive(true)) })
                            put("isError", JsonPrimitive(false))
                            put("_meta", JsonObject(emptyMap()))
                        },
                    )
                }

            val s2 =
                MCServer(
                    serverId = "s2",
                    config = cfg("s2"),
                    caps = Result.failure(IllegalStateException("boom")),
                ) { _, _ ->
                    Result.success(
                        buildJsonObject {
                            put("content", buildJsonArray { })
                            put("structuredContent", buildJsonObject { put("ok", JsonPrimitive(true)) })
                            put("isError", JsonPrimitive(false))
                            put("_meta", JsonObject(emptyMap()))
                        },
                    )
                }

            val multi = MultiServerClient(listOf(s1, s2))
            val all = multi.fetchAllCapabilities()
            assertEquals(setOf("s1"), all.keys)
        }
    }

    @Test
    fun fetch_all_caps_ignores_throwing_server() {
        runBlocking {
            val s1 =
                MCServer(
                    serverId = "s1",
                    config = cfg("s1"),
                    caps = Result.success(ServerCapabilities(tools = listOf(ToolDescriptor("t1")))),
                ) { _, _ ->
                    Result.success(
                        buildJsonObject {
                            put("content", buildJsonArray { })
                            put("structuredContent", buildJsonObject { put("ok", JsonPrimitive(true)) })
                            put("isError", JsonPrimitive(false))
                            put("_meta", JsonObject(emptyMap()))
                        },
                    )
                }
            val s2 = ThrowingServer(serverId = "s2", config = cfg("s2"))

            val multi = MultiServerClient(listOf(s1, s2))
            val all = multi.fetchAllCapabilities()

            assertEquals(setOf("s1"), all.keys)
            assertEquals(listOf("t1"), all["s1"]?.tools?.map { it.name })
        }
    }

    private class ThrowingServer(
        override val serverId: String,
        override val config: McpServerConfig,
    ) : McpServerConnection {
        override val status: ServerStatus = ServerStatus.Running

        override suspend fun connect(): Result<Unit> = Result.success(Unit)

        override suspend fun disconnect() = Unit

        override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> = error("boom")

        override suspend fun callTool(
            toolName: String,
            arguments: JsonObject,
        ): Result<JsonElement> = Result.failure(UnsupportedOperationException())

        override suspend fun getPrompt(
            name: String,
            arguments: Map<String, String>?,
        ): Result<JsonObject> = Result.failure(UnsupportedOperationException())

        override suspend fun readResource(uri: String): Result<JsonObject> = Result.failure(UnsupportedOperationException())
    }
}
