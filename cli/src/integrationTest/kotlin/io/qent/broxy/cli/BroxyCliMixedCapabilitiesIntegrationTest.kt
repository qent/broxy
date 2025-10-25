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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle

@TestInstance(Lifecycle.PER_CLASS)
internal class BroxyCliMixedCapabilitiesIntegrationTest {
    private val clientInteractions = McpClientInteractions()
    private lateinit var scenarioHandle: ScenarioHandle

    @BeforeAll
    fun setUp() =
        runBlocking {
            scenarioHandle =
                BroxyCliTestEnvironment.startScenario(
                    inboundScenario = InboundScenario.STDIO,
                    scenarioConfig = BroxyCliIntegrationConfig.MIXED_CAPABILITIES_SCENARIO,
                )
        }

    @AfterAll
    fun tearDown() {
        if (this::scenarioHandle.isInitialized) {
            scenarioHandle.close()
        }
    }

    @Test
    fun mixedCapabilitiesMergeAndRouting() =
        runScenarioTest("mixed capabilities merge") { client ->
            val toolName = "${BroxyCliIntegrationConfig.STDIO_SERVER_ID}_${BroxyCliIntegrationConfig.ADD_TOOL_NAME}"
            val promptName = BroxyCliIntegrationConfig.HELLO_HTTP_PROMPT
            val resourceUri = BroxyCliIntegrationConfig.RESOURCE_SSE
            val expectedTools = setOf(toolName)
            val expectedPrompts = setOf(promptName)
            val expectedResources = setOf(resourceUri)

            val caps =
                clientInteractions.awaitCapabilities(
                    client = client,
                    expectedTools = expectedTools,
                    expectedPrompts = expectedPrompts,
                    expectedResources = expectedResources,
                )
            clientInteractions.assertToolCapabilities(caps, expectedTools)
            clientInteractions.assertPromptCapabilities(caps, expectedPrompts)
            clientInteractions.assertResourceCapabilities(caps, expectedResources)

            val toolExpectation = BroxyCliIntegrationConfig.TOOL_EXPECTATIONS.getValue(toolName)
            val promptExpectation = BroxyCliIntegrationConfig.PROMPT_EXPECTATIONS.getValue(promptName)
            val resourceExpectation = BroxyCliIntegrationConfig.RESOURCE_EXPECTATIONS.getValue(resourceUri)

            clientInteractions.assertToolResults(client, mapOf(toolName to toolExpectation))
            clientInteractions.assertPromptResponses(client, mapOf(promptName to promptExpectation))
            clientInteractions.assertResourceContents(client, mapOf(resourceUri to resourceExpectation))
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
