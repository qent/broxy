package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyMcpServerTest {
    private val noopLogger = object : Logger {
        override fun debug(message: String) {}
        override fun info(message: String) {}
        override fun warn(message: String, throwable: Throwable?) {}
        override fun error(message: String, throwable: Throwable?) {}
    }

    @org.junit.Test
    fun startFiltersCapabilitiesUsingPreset() = runTest {
        val serverA = FakeServerConnection(
            serverId = "alpha",
            capabilities = ServerCapabilities(
                tools = listOf(ToolDescriptor(name = "search")),
                prompts = listOf(io.qent.broxy.core.mcp.PromptDescriptor(name = "p1")),
                resources = listOf(io.qent.broxy.core.mcp.ResourceDescriptor(name = "doc", uri = "uri://doc"))
            )
        ).apply {
            callResults["search"] = Result.success(JsonPrimitive("ok"))
            promptResults["p1"] = Result.success(JsonObject(emptyMap()))
        }
        val serverB = FakeServerConnection(
            serverId = "beta",
            capabilities = ServerCapabilities(
                tools = listOf(ToolDescriptor(name = "translate"))
            )
        ).apply {
            callResults["translate"] = Result.success(JsonPrimitive("done"))
        }
        val proxy = ProxyMcpServer(listOf(serverA, serverB), logger = noopLogger)
        val preset = Preset(
            id = "preset",
            name = "Preset",
            tools = listOf(
                ToolReference(serverId = "alpha", toolName = "search", enabled = true),
                ToolReference(serverId = "beta", toolName = "translate", enabled = false)
            )
        )

        proxy.start(preset, TransportConfig.StdioTransport(command = "noop"))

        val caps = proxy.getCapabilities()
        assertEquals(listOf("alpha:search"), caps.tools.map { it.name })
        assertEquals(listOf("p1"), caps.prompts.map { it.name })
        assertEquals(listOf("doc"), caps.resources.map { it.name })

        val callResult = proxy.callTool("alpha:search")
        assertTrue(callResult.isSuccess)
        assertEquals(JsonPrimitive("ok"), callResult.getOrThrow())
        assertEquals(listOf("search"), serverA.toolCalls)

        val denied = proxy.callTool("alpha:translate")
        assertTrue(denied.isFailure)
        assertIs<IllegalArgumentException>(denied.exceptionOrNull())

        val prompt = proxy.getPrompt("p1")
        assertTrue(prompt.isSuccess)
        assertEquals(listOf("p1"), serverA.promptRequests)
    }

    @org.junit.Test
    fun applyPresetRebuildsAllowedToolsAndRoutesToNewServer() = runTest {
        val serverA = FakeServerConnection(
            serverId = "alpha",
            capabilities = ServerCapabilities(
                tools = listOf(ToolDescriptor(name = "search"))
            )
        )
        val serverB = FakeServerConnection(
            serverId = "beta",
            capabilities = ServerCapabilities(
                tools = listOf(ToolDescriptor(name = "translate")),
                prompts = listOf(io.qent.broxy.core.mcp.PromptDescriptor(name = "p2"))
            )
        ).apply {
            callResults["translate"] = Result.success(JsonPrimitive("beta-ok"))
            promptResults["p2"] = Result.success(JsonObject(emptyMap()))
        }
        val proxy = ProxyMcpServer(listOf(serverA, serverB), logger = noopLogger)
        val initialPreset = Preset(
            id = "initial",
            name = "Initial",
            tools = listOf(ToolReference(serverId = "alpha", toolName = "search", enabled = true))
        )
        proxy.start(initialPreset, TransportConfig.StdioTransport(command = "noop"))

        val newPreset = Preset(
            id = "new",
            name = "New",
            tools = listOf(ToolReference(serverId = "beta", toolName = "translate", enabled = true))
        )
        proxy.applyPreset(newPreset)

        val caps = proxy.getCapabilities()
        assertEquals(listOf("beta:translate"), caps.tools.map { it.name })

        val result = proxy.callTool("beta:translate")
        assertTrue(result.isSuccess)
        assertEquals(JsonPrimitive("beta-ok"), result.getOrThrow())
        assertEquals(listOf("translate"), serverB.toolCalls)

        val prompt = proxy.getPrompt("p2")
        assertTrue(prompt.isSuccess)
        assertEquals(listOf("p2"), serverB.promptRequests)
    }

    @org.junit.Test
    fun updateDownstreamsRecomputesCapabilitiesWithoutRestart() = runTest {
        val serverA = FakeServerConnection(
            serverId = "alpha",
            capabilities = ServerCapabilities(
                tools = listOf(ToolDescriptor(name = "search"))
            )
        ).apply {
            callResults["search"] = Result.success(JsonPrimitive("alpha-ok"))
        }
        val serverB = FakeServerConnection(
            serverId = "beta",
            capabilities = ServerCapabilities(
                tools = listOf(ToolDescriptor(name = "translate"))
            )
        ).apply {
            callResults["translate"] = Result.success(JsonPrimitive("beta-ok"))
        }

        val proxy = ProxyMcpServer(listOf(serverA), logger = noopLogger)
        val preset = Preset(
            id = "preset",
            name = "Preset",
            tools = listOf(
                ToolReference(serverId = "alpha", toolName = "search", enabled = true),
                ToolReference(serverId = "beta", toolName = "translate", enabled = true)
            )
        )
        proxy.start(preset, TransportConfig.StdioTransport(command = "noop"))

        assertEquals(listOf("alpha:search"), proxy.getCapabilities().tools.map { it.name })

        proxy.updateDownstreams(listOf(serverA, serverB))
        proxy.refreshFilteredCapabilities()

        assertEquals(listOf("alpha:search", "beta:translate"), proxy.getCapabilities().tools.map { it.name })
        assertEquals(JsonPrimitive("beta-ok"), proxy.callTool("beta:translate").getOrThrow())

        proxy.updateDownstreams(listOf(serverA))
        proxy.refreshFilteredCapabilities()

        assertEquals(listOf("alpha:search"), proxy.getCapabilities().tools.map { it.name })
        assertTrue(proxy.callTool("beta:translate").isFailure)
    }

    private class FakeServerConnection(
        override val serverId: String,
        private var capabilities: ServerCapabilities
    ) : McpServerConnection {
        override val config: McpServerConfig = McpServerConfig(
            id = serverId,
            name = "Server $serverId",
            transport = TransportConfig.StdioTransport(command = "noop")
        )

        private var currentStatus: ServerStatus = ServerStatus.Stopped
        val toolCalls = mutableListOf<String>()
        val promptRequests = mutableListOf<String>()
        val resourceRequests = mutableListOf<String>()

        var callResults: MutableMap<String, Result<JsonElement>> = mutableMapOf()
        var promptResults: MutableMap<String, Result<JsonObject>> = mutableMapOf()
        var resourceResults: MutableMap<String, Result<JsonObject>> = mutableMapOf()

        override val status: ServerStatus
            get() = currentStatus

        override suspend fun connect(): Result<Unit> {
            currentStatus = ServerStatus.Running
            return Result.success(Unit)
        }

        override suspend fun disconnect() {
            currentStatus = ServerStatus.Stopped
        }

        override suspend fun getCapabilities(forceRefresh: Boolean): Result<ServerCapabilities> =
            Result.success(capabilities)

        override suspend fun callTool(toolName: String, arguments: JsonObject): Result<JsonElement> {
            toolCalls += toolName
            return callResults[toolName] ?: Result.success(JsonNull)
        }

        override suspend fun getPrompt(name: String, arguments: Map<String, String>?): Result<JsonObject> {
            promptRequests += name
            return promptResults[name] ?: Result.failure(IllegalArgumentException("prompt $name missing"))
        }

        override suspend fun readResource(uri: String): Result<JsonObject> {
            resourceRequests += uri
            return resourceResults[uri] ?: Result.failure(IllegalArgumentException("resource $uri missing"))
        }
    }
}
