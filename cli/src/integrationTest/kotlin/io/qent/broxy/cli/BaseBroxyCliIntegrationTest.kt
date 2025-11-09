package io.qent.broxy.cli

import io.qent.broxy.cli.support.BroxyCliIntegrationConfig
import io.qent.broxy.cli.support.BroxyCliTestEnvironment
import io.qent.broxy.cli.support.InboundScenario
import io.qent.broxy.cli.support.McpClientInteractions
import io.qent.broxy.cli.support.ScenarioHandle
import io.qent.broxy.core.mcp.McpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal abstract class BaseBroxyCliIntegrationTest(
    private val inboundScenario: InboundScenario
) {
    protected val clientInteractions = McpClientInteractions()

    protected fun runScenarioTest(description: String, block: suspend (McpClient) -> Unit) =
        runBlocking {
            val scenarioHandle = BroxyCliTestEnvironment.startScenario(inboundScenario)
            try {
                warmUpClient(scenarioHandle)
                withTimeout(BroxyCliIntegrationConfig.TEST_TIMEOUT_MILLIS) {
                    scenarioHandle.run(description, block)
                }
            } finally {
                scenarioHandle.close()
            }
        }

    private suspend fun warmUpClient(scenarioHandle: ScenarioHandle) {
        scenarioHandle.run("warmup capabilities") { client ->
            clientInteractions.awaitFilteredCapabilities(
                client,
                BroxyCliIntegrationConfig.CAPABILITIES_WARMUP_TIMEOUT_MILLIS
            )
        }
    }
}
