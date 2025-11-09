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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseBroxyCliIntegrationTest(
    private val inboundScenario: InboundScenario
) {
    private lateinit var scenarioHandle: ScenarioHandle

    @BeforeAll
    fun setUpScenario() = runBlocking {
        scenarioHandle = BroxyCliTestEnvironment.startScenario(inboundScenario)
        warmUpClient()
    }

    @AfterAll
    fun tearDownScenario() {
        scenarioHandle.close()
    }

    protected fun runScenarioTest(description: String, block: suspend (McpClient) -> Unit) =
        runBlocking {
            withTimeout(BroxyCliIntegrationConfig.TEST_TIMEOUT_MILLIS) {
                scenarioHandle.run(description, block)
        }
    }

    private suspend fun warmUpClient() {
        scenarioHandle.run("warmup capabilities") { client ->
            McpClientInteractions.awaitFilteredCapabilities(
                client,
                BroxyCliIntegrationConfig.CAPABILITIES_WARMUP_TIMEOUT_MILLIS
            )
        }
    }
}
