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
internal class BroxyCliAdapterModeFallbackIntegrationTest {
    private val config = BroxyCliIntegrationConfig
    private val clientInteractions = AdapterModeClientInteractions()
    private lateinit var scenarioHandle: ScenarioHandle

    @BeforeAll
    fun setUp() =
        runBlocking {
            scenarioHandle =
                BroxyCliTestEnvironment.startScenario(
                    inboundScenario = InboundScenario.STDIO,
                    scenarioConfig = BroxyCliIntegrationConfig.FALLBACK_ADAPTER_SCENARIO,
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
    fun adapterActionsCoverPromptsAndResources() =
        runScenarioTest("adapter fallback actions") { client ->
            val expectedPrompts = setOf(config.HELLO_STDIO_PROMPT, HELLO_STDIO_PLAIN_PROMPT)
            val expectedResources = setOf(config.RESOURCE_STDIO)
            val expectedTemplates = setOf(config.RESOURCE_TEMPLATE_STDIO)

            val actions =
                clientInteractions.awaitAvailableActions(
                    client = client,
                    expectedTools = emptySet(),
                    expectedPrompts = expectedPrompts,
                    expectedResources = expectedResources,
                    expectedResourceTemplates = expectedTemplates,
                )
            clientInteractions.assertAvailableActions(
                actions = actions,
                expectedTools = emptySet(),
                expectedPrompts = expectedPrompts,
                expectedResources = expectedResources,
                expectedResourceTemplates = expectedTemplates,
            )
            clientInteractions.assertTemplateArguments(actions, expectedTemplates)

            val promptPayload =
                clientInteractions.executePromptAction(
                    client = client,
                    promptName = config.HELLO_STDIO_PROMPT,
                    arguments = clientInteractions.buildPromptArguments(),
                )
            val promptText = clientInteractions.extractPromptText(promptPayload)
            assertEquals(EXPECTED_PROMPT_WITH_ARGS, promptText)

            val promptNoArgsPayload =
                clientInteractions.executePromptAction(
                    client = client,
                    promptName = HELLO_STDIO_PLAIN_PROMPT,
                    arguments = null,
                )
            val promptNoArgsText = clientInteractions.extractPromptText(promptNoArgsPayload)
            assertEquals(EXPECTED_PROMPT_NO_ARGS, promptNoArgsText)

            val resourcePayload =
                clientInteractions.executeResourceAction(
                    client = client,
                    resourceUri = config.RESOURCE_STDIO,
                )
            val resourceText = clientInteractions.extractResourceText(resourcePayload)
            assertEquals(config.RESOURCE_EXPECTATIONS.getValue(config.RESOURCE_STDIO), resourceText)

            val templatePayload =
                clientInteractions.executeResourceTemplateAction(
                    client = client,
                    templateUri = config.RESOURCE_TEMPLATE_STDIO,
                    arguments = clientInteractions.buildTemplateArguments(),
                )
            val templateText = clientInteractions.extractResourceText(templatePayload)
            assertEquals(config.RESOURCE_TEMPLATE_EXPECTATIONS.getValue(config.RESOURCE_TEMPLATE_STDIO), templateText)
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

private const val HELLO_STDIO_PLAIN_PROMPT = "hello_stdio_plain"
private const val EXPECTED_PROMPT_WITH_ARGS = "Hello stdio ${BroxyCliIntegrationConfig.PROMPT_ARGUMENT_PLACEHOLDER}!"
private const val EXPECTED_PROMPT_NO_ARGS = "Hello stdio!"
