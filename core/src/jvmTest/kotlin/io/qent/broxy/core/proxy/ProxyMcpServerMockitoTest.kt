package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.McpServerConnection
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertTrue

class ProxyMcpServerMockitoTest {
    private fun cfg(id: String) = McpServerConfig(id, "srv-$id", TransportConfig.HttpTransport("http://$id"))

    @Test
    fun routes_prompt_and_resource_to_correct_server() {
        val s1: McpServerConnection = mock()
        whenever(s1.serverId).thenReturn("s1")
        whenever(s1.config).thenReturn(cfg("s1"))
        runBlocking { whenever(s1.connect()).thenReturn(Result.success(Unit)) }
        runBlocking { whenever(s1.getCapabilities(any())).thenReturn(
            Result.success(
                ServerCapabilities(
                    prompts = listOf(io.qent.broxy.core.mcp.PromptDescriptor("p1")),
                    resources = listOf(io.qent.broxy.core.mcp.ResourceDescriptor("r1", uri = "u1"))
                )
            )
        ) }
        runBlocking { whenever(s1.getPrompt("p1")).thenReturn(Result.success(buildJsonObject { put("description", "d"); put("messages", "[]") })) }
        runBlocking { whenever(s1.readResource("u1")).thenReturn(Result.success(buildJsonObject { put("contents", "[]"); put("_meta", "{}") })) }

        val proxy = ProxyMcpServer(listOf(s1))
        val preset = Preset("p", "name", "d", tools = listOf(ToolReference("s1", "any", true)))
        proxy.start(preset, TransportConfig.HttpTransport("http://0.0.0.0:0/mcp"))

        val pr = kotlinx.coroutines.runBlocking { proxy.getPrompt("p1") }
        assertTrue(pr.isSuccess)
        runBlocking { verify(s1).getPrompt("p1") }

        val rr = kotlinx.coroutines.runBlocking { proxy.readResource("u1") }
        assertTrue(rr.isSuccess)
        runBlocking { verify(s1).readResource("u1") }
    }

    @Test
    fun unknown_prompt_and_resource_return_failure() {
        val s1: McpServerConnection = mock()
        whenever(s1.serverId).thenReturn("s1")
        whenever(s1.config).thenReturn(cfg("s1"))
        runBlocking { whenever(s1.connect()).thenReturn(Result.success(Unit)) }
        runBlocking { whenever(s1.getCapabilities(any())).thenReturn(
            Result.success(
                ServerCapabilities(
                    prompts = listOf(io.qent.broxy.core.mcp.PromptDescriptor("p1")),
                    resources = listOf(io.qent.broxy.core.mcp.ResourceDescriptor("r1", uri = "u1"))
                )
            )
        ) }

        val proxy = ProxyMcpServer(listOf(s1))
        val preset = Preset("p", "name", "d", tools = listOf(ToolReference("s1", "any", true)))
        proxy.start(preset, TransportConfig.HttpTransport("http://0.0.0.0:0/mcp"))

        val pr = kotlinx.coroutines.runBlocking { proxy.getPrompt("unknown") }
        assertTrue(pr.isFailure)
        val rr = kotlinx.coroutines.runBlocking { proxy.readResource("unknown") }
        assertTrue(rr.isFailure)
    }
}
