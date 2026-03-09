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
import kotlin.test.assertEquals

@TestInstance(Lifecycle.PER_CLASS)
internal class BroxyCliAdapterModeMixedCapabilitiesIntegrationTest {
    private val config = BroxyCliIntegrationConfig
    private val clientInteractions = AdapterModeClientInteractions()
    private lateinit var scenarioHandle: ScenarioHandle

    @BeforeAll
    fun setUp() =
        runBlocking {
            scenarioHandle =
                BroxyCliTestEnvironment.startScenario(
                    inboundScenario = InboundScenario.STDIO,
                    scenarioConfig = BroxyCliIntegrationConfig.MIXED_CAPABILITIES_ADAPTER_SCENARIO,
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
    fun mixedCapabilitiesExposeExpectedActions() =
        runScenarioTest("adapter mixed capabilities") { client ->
            val toolName = "${config.STDIO_SERVER_ID}_${config.ADD_TOOL_NAME}"
            val promptName = config.HELLO_HTTP_PROMPT
            val resourceUri = config.RESOURCE_SSE
            val expectedTools = setOf(toolName)
            val expectedPrompts = setOf(promptName)
            val expectedResources = setOf(resourceUri)
            val expectedTemplates = emptySet<String>()

            val actions =
                clientInteractions.awaitAvailableActions(
                    client = client,
                    expectedTools = expectedTools,
                    expectedPrompts = expectedPrompts,
                    expectedResources = expectedResources,
                    expectedResourceTemplates = expectedTemplates,
                )
            clientInteractions.assertAvailableActions(
                actions = actions,
                expectedTools = expectedTools,
                expectedPrompts = expectedPrompts,
                expectedResources = expectedResources,
                expectedResourceTemplates = expectedTemplates,
            )

            val toolExpectation = config.TOOL_EXPECTATIONS.getValue(toolName)
            val toolPayload =
                clientInteractions.executeToolAction(
                    client = client,
                    toolName = toolName,
                    arguments = clientInteractions.buildArithmeticArguments(),
                )
            clientInteractions.assertStructuredResult(
                payload = toolPayload,
                expectedOperation = toolExpectation.operation,
                expectedResult = toolExpectation.expectedResult,
            )

            val promptPayload =
                clientInteractions.executePromptAction(
                    client = client,
                    promptName = promptName,
                    arguments = clientInteractions.buildPromptArguments(),
                )
            val promptText = clientInteractions.extractPromptText(promptPayload)
            assertEquals(config.PROMPT_EXPECTATIONS.getValue(promptName), promptText)

            val resourcePayload =
                clientInteractions.executeResourceAction(
                    client = client,
                    resourceUri = resourceUri,
                )
            val resourceText = clientInteractions.extractResourceText(resourcePayload)
            assertEquals(config.RESOURCE_EXPECTATIONS.getValue(resourceUri), resourceText)
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
