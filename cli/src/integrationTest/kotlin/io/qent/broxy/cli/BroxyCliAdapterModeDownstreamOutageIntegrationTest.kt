package io.qent.broxy.cli

import io.qent.broxy.cli.support.AdapterModeClientInteractions
import io.qent.broxy.cli.support.BroxyCliIntegrationConfig
import io.qent.broxy.cli.support.BroxyCliTestEnvironment
import io.qent.broxy.cli.support.DownstreamTarget
import io.qent.broxy.cli.support.InboundScenario
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

internal class BroxyCliAdapterModeDownstreamOutageIntegrationTest {
    private val config = BroxyCliIntegrationConfig
    private val clientInteractions = AdapterModeClientInteractions()

    @ParameterizedTest
    @EnumSource(DownstreamTarget::class)
    fun downstreamOutageIsIsolatedInAdapterMode(target: DownstreamTarget) =
        runBlocking {
            val scenarioHandle =
                BroxyCliTestEnvironment.startScenario(
                    inboundScenario = InboundScenario.STDIO,
                    scenarioConfig = BroxyCliIntegrationConfig.OUTAGE_ADAPTER_SCENARIO,
                )
            try {
                scenarioHandle.run("warmup adapter capabilities") { client ->
                    clientInteractions.awaitAdapterCapabilities(client)
                    clientInteractions.awaitAvailableActions(
                        client = client,
                        expectedTools = config.EXPECTED_TOOLS,
                        expectedPrompts = config.EXPECTED_PROMPTS,
                        expectedResources = config.EXPECTED_RESOURCES,
                        expectedResourceTemplates = emptySet(),
                    )
                }

                scenarioHandle.stopDownstream(target)
                delay(250)

                scenarioHandle.run("post-outage available actions") { client ->
                    val actions =
                        clientInteractions.awaitAvailableActions(
                            client = client,
                            expectedTools = config.EXPECTED_TOOLS,
                            expectedPrompts = config.EXPECTED_PROMPTS,
                            expectedResources = config.EXPECTED_RESOURCES,
                            expectedResourceTemplates = emptySet(),
                        )
                    clientInteractions.assertAvailableActions(
                        actions = actions,
                        expectedTools = config.EXPECTED_TOOLS,
                        expectedPrompts = config.EXPECTED_PROMPTS,
                        expectedResources = config.EXPECTED_RESOURCES,
                        expectedResourceTemplates = emptySet(),
                    )
                }

                scenarioHandle.run("post-outage tool calls") { client ->
                    val deadTool = deadToolFor(target)
                    val healthyTool = healthyToolFor(target)

                    clientInteractions.assertActionDenied(
                        client = client,
                        actionType = "tool",
                        name = deadTool,
                        arguments = clientInteractions.buildArithmeticArguments(),
                    )

                    val expectation = config.TOOL_EXPECTATIONS.getValue(healthyTool)
                    val payload =
                        clientInteractions.executeToolAction(
                            client = client,
                            toolName = healthyTool,
                            arguments = clientInteractions.buildArithmeticArguments(),
                        )
                    clientInteractions.assertStructuredResult(
                        payload = payload,
                        expectedOperation = expectation.operation,
                        expectedResult = expectation.expectedResult,
                    )
                }
            } finally {
                scenarioHandle.close()
            }
        }

    private fun deadToolFor(target: DownstreamTarget): String =
        when (target) {
            DownstreamTarget.STDIO -> "${config.STDIO_SERVER_ID}_${config.ADD_TOOL_NAME}"
            DownstreamTarget.HTTP_STREAMABLE -> "${config.HTTP_SERVER_ID}_${config.SUBTRACT_TOOL_NAME}"
            DownstreamTarget.HTTP_SSE -> "${config.SSE_SERVER_ID}_${config.MULTIPLY_TOOL_NAME}"
            DownstreamTarget.WS -> "${config.WS_SERVER_ID}_${config.DIVIDE_TOOL_NAME}"
        }

    private fun healthyToolFor(target: DownstreamTarget): String =
        when (target) {
            DownstreamTarget.STDIO -> "${config.HTTP_SERVER_ID}_${config.SUBTRACT_TOOL_NAME}"
            DownstreamTarget.HTTP_STREAMABLE -> "${config.STDIO_SERVER_ID}_${config.ADD_TOOL_NAME}"
            DownstreamTarget.HTTP_SSE -> "${config.STDIO_SERVER_ID}_${config.ADD_TOOL_NAME}"
            DownstreamTarget.WS -> "${config.STDIO_SERVER_ID}_${config.ADD_TOOL_NAME}"
        }
}
