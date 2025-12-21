package io.qent.broxy.core.mcp

import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IsolatedMcpServerConnectionTest {
    @Test
    fun runs_each_server_on_its_own_thread() =
        runBlocking {
            val s1 = RecordingConnection("s1")
            val s2 = RecordingConnection("s2")

            val iso1 = IsolatedMcpServerConnection(s1, threadName = "test-mcp-s1")
            val iso2 = IsolatedMcpServerConnection(s2, threadName = "test-mcp-s2")

            try {
                iso1.getCapabilities()
                iso1.callTool("noop", JsonObject(emptyMap()))
                iso2.getCapabilities()

                val s1Threads = s1.threadNames.toSet()
                val s2Threads = s2.threadNames.toSet()

                assertEquals(1, s1Threads.size)
                assertEquals(1, s2Threads.size)
                assertNotEquals(s1Threads.first(), s2Threads.first())
                assertTrue(s1Threads.first().startsWith("test-mcp-s1"))
                assertTrue(s2Threads.first().startsWith("test-mcp-s2"))
            } finally {
                iso1.close()
                iso2.close()
            }
        }

    private class RecordingConnection(
        override val serverId: String,
    ) : McpServerConnection {
        override val config: McpServerConfig =
            McpServerConfig(
                id = serverId,
                name = "Server $serverId",
                transport = TransportConfig.StdioTransport(command = "noop"),
            )

        override val status: ServerStatus = ServerStatus.Stopped

        val threadNames = mutableListOf<String>()

        override suspend fun connect(): Result<Unit> {
            record()
            return Result.success(Unit)
        }

        override suspend fun disconnect() {
            record()
        }

        override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> {
            record()
            return Result.success(ServerCapabilities())
        }

        override suspend fun callTool(
            toolName: String,
            arguments: JsonObject,
        ): Result<JsonElement> {
            record()
            return Result.success(JsonNull)
        }

        override suspend fun getPrompt(
            name: String,
            arguments: Map<String, String>?,
        ): Result<JsonObject> {
            record()
            return Result.success(JsonObject(emptyMap()))
        }

        override suspend fun readResource(uri: String): Result<JsonObject> {
            record()
            return Result.success(JsonObject(emptyMap()))
        }

        private fun record() {
            threadNames += Thread.currentThread().name
        }
    }
}
