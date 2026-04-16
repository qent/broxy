package io.qent.broxy.cli

import io.qent.broxy.cli.support.AdapterModeClientInteractions
import io.qent.broxy.cli.support.BroxyCliIntegrationConfig
import io.qent.broxy.cli.support.BroxyCliTestEnvironment
import io.qent.broxy.cli.support.InboundScenario
import io.qent.broxy.cli.support.ScenarioHandle
import io.qent.broxy.core.mcp.McpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle

@TestInstance(Lifecycle.PER_CLASS)
internal class BroxyCliAdapterModeNegativeRoutingIntegrationTest {
    private val config = BroxyCliIntegrationConfig
    private val clientInteractions = AdapterModeClientInteractions()
    private lateinit var scenarioHandle: ScenarioHandle

    @BeforeAll
    fun setUp() =
        runBlocking {
            scenarioHandle =
                BroxyCliTestEnvironment.startScenario(
                    inboundScenario = InboundScenario.STDIO,
                    scenarioConfig = BroxyCliIntegrationConfig.NEGATIVE_ADAPTER_SCENARIO,
                )
            scenarioHandle.run("warmup adapter capabilities") { client ->
                clientInteractions.awaitAdapterCapabilities(
                    client,
                    BroxyCliIntegrationConfig.CAPABILITIES_WARMUP_TIMEOUT_MILLIS,
                )
            }
        }

    @AfterAll
    fun tearDown() {
        if (this::scenarioHandle.isInitialized) {
            scenarioHandle.close()
        }
    }

    @Test
    fun disallowedAndDisabledActionsAreRejected() =
        runScenarioTest("adapter negative routing") { client ->
            val allowedTool = "${config.STDIO_SERVER_ID}_${config.ADD_TOOL_NAME}"
            val disallowedTool = "${config.HTTP_SERVER_ID}_${config.SUBTRACT_TOOL_NAME}"
            val disabledTool = "${config.WS_SERVER_ID}_${config.DIVIDE_TOOL_NAME}"

            val actions =
                clientInteractions.awaitAvailableActions(
                    client = client,
                    expectedTools = setOf(allowedTool),
                    expectedPrompts = emptySet(),
                    expectedResources = emptySet(),
                    expectedResourceTemplates = emptySet(),
                )
            clientInteractions.assertAvailableActions(
                actions = actions,
                expectedTools = setOf(allowedTool),
                expectedPrompts = emptySet(),
                expectedResources = emptySet(),
                expectedResourceTemplates = emptySet(),
            )

            val expectation = config.TOOL_EXPECTATIONS.getValue(allowedTool)
            val payload =
                clientInteractions.executeToolAction(
                    client = client,
                    toolName = allowedTool,
                    arguments = clientInteractions.buildArithmeticArguments(),
                )
            clientInteractions.assertStructuredResult(
                payload = payload,
                expectedOperation = expectation.operation,
                expectedResult = expectation.expectedResult,
            )

            clientInteractions.assertActionDenied(
                client = client,
                actionType = "tool",
                name = disallowedTool,
                arguments = clientInteractions.buildArithmeticArguments(),
            )
            clientInteractions.assertActionDenied(
                client = client,
                actionType = "tool",
                name = disabledTool,
                arguments = clientInteractions.buildArithmeticArguments(),
            )
        }

    private fun runScenarioTest(
        description: String,
        block: suspend (McpClient) -> Unit,
    ) = runBlocking {
        withTimeout(BroxyCliIntegrationConfig.TEST_TIMEOUT_MILLIS) {
            scenarioHandle.run(description, block)
        }
    }
}
