package io.qent.broxy.agents

import com.sun.net.httpserver.HttpServer
import io.qent.broxy.agents.runtime.models.HttpAgentModelCatalog
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpAgentModelCatalogTest {
    @Test
    fun listModels_lmStudio_usesHttp11WithoutH2cUpgradeHeader() =
        runTest {
            val upgradeHeader = AtomicReference<String?>(null)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/models") { exchange ->
                val currentUpgrade = exchange.requestHeaders.getFirst("Upgrade")
                upgradeHeader.set(currentUpgrade)
                if (currentUpgrade != null) {
                    val failure = """{"error":"upgrade not supported"}""".toByteArray()
                    exchange.sendResponseHeaders(400, failure.size.toLong())
                    exchange.responseBody.use { it.write(failure) }
                    return@createContext
                }

                val success =
                    """
                    {
                      "data": [
                        {
                          "id": "lmstudio-local-model",
                          "object": "model"
                        }
                      ],
                      "object": "list"
                    }
                    """.trimIndent().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, success.size.toLong())
                exchange.responseBody.use { it.write(success) }
            }
            server.start()
            try {
                val catalog = HttpAgentModelCatalog()
                val settings =
                    AgentProviderSettings(
                        lmStudio =
                            AgentProviderConfig(
                                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                            ),
                    )

                val result =
                    catalog.listModels(
                        provider = LlmProvider.LM_STUDIO,
                        providerSettings = settings,
                        apiKey = null,
                        requestTimeoutSeconds = 5,
                        ignoreHttpsCertificateErrors = false,
                    )

                assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
                assertEquals(listOf("lmstudio-local-model"), result.getOrThrow())
                assertNull(upgradeHeader.get())
            } finally {
                server.stop(0)
            }
        }
}
