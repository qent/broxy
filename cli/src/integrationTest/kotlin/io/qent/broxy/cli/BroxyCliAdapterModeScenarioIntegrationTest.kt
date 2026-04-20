package io.qent.broxy.cli

import io.qent.broxy.cli.support.AdapterModeClientInteractions
import io.qent.broxy.cli.support.BroxyCliIntegrationConfig
import io.qent.broxy.cli.support.BroxyCliTestEnvironment
import io.qent.broxy.cli.support.InboundScenario
import io.qent.broxy.cli.support.ScenarioHandle
import io.qent.broxy.core.mcp.McpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(Lifecycle.PER_CLASS)
internal class BroxyCliAdapterModeScenarioIntegrationTest {
    private val config = BroxyCliIntegrationConfig
    private val clientInteractions = AdapterModeClientInteractions()
    private val scenarioRunner = AdapterScenarioRunner(clientInteractions)

    @AfterAll
    fun tearDown() {
        scenarioRunner.close()
    }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun adapterCapabilitiesExposeFixedToolset(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "adapter toolset") { client ->
            val caps = clientInteractions.awaitAdapterCapabilities(client)
            val toolNames = caps.tools.map { it.name }.toSet()
            assertEquals(config.ADAPTER_TOOL_NAMES, toolNames)
            assertTrue(caps.prompts.isEmpty(), "Adapter mode should not expose prompts in capabilities")
            assertTrue(caps.resources.isEmpty(), "Adapter mode should not expose resources in capabilities")
        }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun availableActionsMatchPreset(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "adapter available actions") { client ->
            clientInteractions.awaitAdapterCapabilities(client)
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

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun executeActionToolResultsMatchExpectedValues(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "adapter tool results") { client ->
            clientInteractions.awaitAdapterCapabilities(client)
            clientInteractions.awaitAvailableActions(
                client = client,
                expectedTools = config.EXPECTED_TOOLS,
                expectedPrompts = config.EXPECTED_PROMPTS,
                expectedResources = config.EXPECTED_RESOURCES,
                expectedResourceTemplates = emptySet(),
            )
            config.TOOL_EXPECTATIONS.forEach { (toolName, expectation) ->
                val payload =
                    clientInteractions.executeToolAction(
                        client = client,
                        toolName = toolName,
                        arguments = clientInteractions.buildArithmeticArguments(),
                    )
                clientInteractions.assertStructuredResult(
                    payload = payload,
                    expectedOperation = expectation.operation,
                    expectedResult = expectation.expectedResult,
                )
            }
        }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun executeActionPromptsReturnExpectedContent(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "adapter prompt results") { client ->
            clientInteractions.awaitAdapterCapabilities(client)
            clientInteractions.awaitAvailableActions(
                client = client,
                expectedTools = config.EXPECTED_TOOLS,
                expectedPrompts = config.EXPECTED_PROMPTS,
                expectedResources = config.EXPECTED_RESOURCES,
                expectedResourceTemplates = emptySet(),
            )
            config.PROMPT_EXPECTATIONS.forEach { (promptName, expectedText) ->
                val payload =
                    clientInteractions.executePromptAction(
                        client = client,
                        promptName = promptName,
                        arguments = clientInteractions.buildPromptArguments(),
                    )
                val actual = clientInteractions.extractPromptText(payload)
                assertEquals(expectedText, actual, "Prompt $promptName should render expected text")
            }
        }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun executeActionResourcesReturnExpectedContent(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "adapter resource results") { client ->
            clientInteractions.awaitAdapterCapabilities(client)
            clientInteractions.awaitAvailableActions(
                client = client,
                expectedTools = config.EXPECTED_TOOLS,
                expectedPrompts = config.EXPECTED_PROMPTS,
                expectedResources = config.EXPECTED_RESOURCES,
                expectedResourceTemplates = emptySet(),
            )
            config.RESOURCE_EXPECTATIONS.forEach { (resourceUri, expectedText) ->
                val payload =
                    clientInteractions.executeResourceAction(
                        client = client,
                        resourceUri = resourceUri,
                    )
                val actual = clientInteractions.extractResourceText(payload)
                assertEquals(expectedText, actual, "Resource $resourceUri should match expected text")
            }
        }

    private fun runScenarioTest(
        inboundScenario: InboundScenario,
        description: String,
        block: suspend (McpClient) -> Unit,
    ) = runBlocking {
        val handle = scenarioRunner.handleFor(inboundScenario)
        withTimeout(BroxyCliIntegrationConfig.TEST_TIMEOUT_MILLIS) {
            handle.run(description, block)
        }
    }
}

private class AdapterScenarioRunner(
    private val clientInteractions: AdapterModeClientInteractions,
    private val scenarioConfig: BroxyCliIntegrationConfig.ScenarioConfig = BroxyCliIntegrationConfig.DEFAULT_ADAPTER_SCENARIO,
    private val skipWarmup: Boolean = false,
) : AutoCloseable {
    private val mutex = Mutex()
    private val handles = mutableMapOf<InboundScenario, ScenarioHandle>()

    suspend fun handleFor(inboundScenario: InboundScenario): ScenarioHandle =
        mutex.withLock {
            handles[inboundScenario]?.let { return it }
            val handle = BroxyCliTestEnvironment.startScenario(inboundScenario, scenarioConfig)
            if (!skipWarmup) {
                warmUpClient(handle)
            }
            handles[inboundScenario] = handle
            handle
        }

    override fun close() {
        handles.values.forEach { it.close() }
        handles.clear()
    }

    private suspend fun warmUpClient(handle: ScenarioHandle) {
        handle.run("warmup adapter capabilities") { client ->
            clientInteractions.awaitAdapterCapabilities(
                client,
                BroxyCliIntegrationConfig.CAPABILITIES_WARMUP_TIMEOUT_MILLIS,
            )
        }
    }
}
