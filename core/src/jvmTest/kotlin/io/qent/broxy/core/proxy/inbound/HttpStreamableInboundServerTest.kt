package io.qent.broxy.core.proxy.inbound

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ServerStatus
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.mcp.clients.KtorMcpClient
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.presetmanagement.PresetManagementToolNames
import io.qent.broxy.core.proxy.ProxyMcpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HttpStreamableInboundServerTest {
    @Test
    fun `http streamable inbound accepts mcp client connection`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val url = "http://127.0.0.1:$port/mcp"

            val proxy = ProxyMcpServer(downstreams = emptyList())
            val inbound =
                InboundServerFactory.create(
                    transport = TransportConfig.StreamableHttpTransport(url = url),
                    proxy = proxy,
                )

            assertEquals(ServerStatus.Running, inbound.start())

            val client = KtorMcpClient(mode = KtorMcpClient.Mode.StreamableHttp, url = url)
            try {
                withTimeout(10.seconds) {
                    while (true) {
                        val connected = client.connect()
                        if (connected.isSuccess) break
                        delay(100)
                    }
                }

                val caps = withTimeout(5.seconds) { client.fetchCapabilities() }
                assertTrue(
                    caps.isSuccess,
                    "Client should fetch capabilities after connect (${caps.exceptionOrNull()?.message})",
                )
            } finally {
                runCatching { client.disconnect() }
                runCatching { inbound.stop() }
            }
        }

    @Test
    fun `mcp preset management route exposes only management tools and stays pinned`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val baseUrl = "http://127.0.0.1:$port/mcp"
            val managementUrl = "$baseUrl/${Preset.PRESET_MANAGEMENT_ID}"

            val proxy = ProxyMcpServer(downstreams = emptyList())
            proxy.start(Preset.allEnabled(), TransportConfig.StreamableHttpTransport(url = baseUrl))
            proxy.setCapabilitiesSnapshot(
                mapOf(
                    "alpha" to ServerCapabilities(tools = listOf(ToolDescriptor(name = "search"))),
                ),
            )
            val inbound =
                InboundServerFactory.create(
                    transport = TransportConfig.StreamableHttpTransport(url = baseUrl),
                    proxy = proxy,
                )

            assertEquals(ServerStatus.Running, inbound.start())

            val defaultClient = KtorMcpClient(mode = KtorMcpClient.Mode.StreamableHttp, url = baseUrl)
            val managementClient = KtorMcpClient(mode = KtorMcpClient.Mode.StreamableHttp, url = managementUrl)
            try {
                connectWithRetries(defaultClient)
                connectWithRetries(managementClient)

                val defaultBefore = defaultClient.fetchCapabilities().getOrThrow()
                val managementBefore = managementClient.fetchCapabilities().getOrThrow()
                assertTrue(defaultBefore.tools.any { it.name == "alpha_search" })
                assertEquals(PresetManagementToolNames.all.toSet(), managementBefore.tools.map { it.name }.toSet())
                assertTrue(managementBefore.prompts.isEmpty())
                assertTrue(managementBefore.resources.isEmpty())

                proxy.applyPreset(Preset.empty())
                inbound.refreshCapabilities().getOrThrow()

                val defaultAfter = defaultClient.fetchCapabilities().getOrThrow()
                val managementAfter = managementClient.fetchCapabilities().getOrThrow()
                assertTrue(defaultAfter.tools.isEmpty())
                assertEquals(PresetManagementToolNames.all.toSet(), managementAfter.tools.map { it.name }.toSet())
            } finally {
                runCatching { defaultClient.disconnect() }
                runCatching { managementClient.disconnect() }
                runCatching { inbound.stop() }
            }
        }

    @Test
    fun `sse preset management route exposes only management tools`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val baseUrl = "http://127.0.0.1:$port/mcp"
            val managementSseUrl = "http://127.0.0.1:$port/sse/${Preset.PRESET_MANAGEMENT_ID}"

            val proxy = ProxyMcpServer(downstreams = emptyList())
            proxy.start(Preset.allEnabled(), TransportConfig.StreamableHttpTransport(url = baseUrl))
            proxy.setCapabilitiesSnapshot(
                mapOf(
                    "alpha" to ServerCapabilities(tools = listOf(ToolDescriptor(name = "search"))),
                ),
            )
            val inbound =
                InboundServerFactory.create(
                    transport = TransportConfig.StreamableHttpTransport(url = baseUrl),
                    proxy = proxy,
                )

            assertEquals(ServerStatus.Running, inbound.start())

            val sseClient = KtorMcpClient(mode = KtorMcpClient.Mode.Sse, url = managementSseUrl)
            try {
                connectWithRetries(sseClient)
                val capabilities = sseClient.fetchCapabilities().getOrThrow()
                assertEquals(PresetManagementToolNames.all.toSet(), capabilities.tools.map { it.name }.toSet())
                assertTrue(capabilities.prompts.isEmpty())
                assertTrue(capabilities.resources.isEmpty())
            } finally {
                runCatching { sseClient.disconnect() }
                runCatching { inbound.stop() }
            }
        }

    private suspend fun connectWithRetries(client: KtorMcpClient) {
        withTimeout(10.seconds) {
            while (true) {
                if (client.connect().isSuccess) {
                    break
                }
                delay(100)
            }
        }
    }
}
