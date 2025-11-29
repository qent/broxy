package io.qent.broxy.cli

import io.qent.broxy.cli.support.BroxyCliIntegrationConfig
import io.qent.broxy.cli.support.BroxyCliTestEnvironment
import io.qent.broxy.cli.support.InboundScenario
import io.qent.broxy.cli.support.McpClientInteractions
import io.qent.broxy.cli.support.ScenarioHandle
import io.qent.broxy.core.mcp.McpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle

@TestInstance(Lifecycle.PER_CLASS)
internal abstract class BaseBroxyCliIntegrationTest(
    private val inboundScenario: InboundScenario
) {
    private lateinit var scenarioHandle: ScenarioHandle
    protected val clientInteractions = McpClientInteractions()

    @BeforeAll
    fun setUp() = runBlocking {
        scenarioHandle = BroxyCliTestEnvironment.startScenario(inboundScenario)
        try {
            warmUpClient()
        } catch (error: Throwable) {
            scenarioHandle.close()
            throw error
        }
    }

    @AfterAll
    fun tearDown() {
        if (this::scenarioHandle.isInitialized) {
            scenarioHandle.close()
        }
    }

    protected fun runScenarioTest(description: String, block: suspend (McpClient) -> Unit) =
        runBlocking {
            withTimeout(BroxyCliIntegrationConfig.TEST_TIMEOUT_MILLIS) {
                scenarioHandle.run(description, block)
            }
        }

    private suspend fun warmUpClient() {
        scenarioHandle.run("warmup capabilities") { client ->
            clientInteractions.awaitFilteredCapabilities(
                client,
                BroxyCliIntegrationConfig.CAPABILITIES_WARMUP_TIMEOUT_MILLIS
            )
        }
    }
}
