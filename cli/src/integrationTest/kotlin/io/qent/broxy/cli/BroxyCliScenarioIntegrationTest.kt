package io.qent.broxy.cli

import io.qent.broxy.cli.support.BroxyCliIntegrationConfig
import io.qent.broxy.cli.support.BroxyCliTestEnvironment
import io.qent.broxy.cli.support.InboundScenario
import io.qent.broxy.cli.support.McpClientInteractions
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

@TestInstance(Lifecycle.PER_CLASS)
internal class BroxyCliScenarioIntegrationTest {
    private val clientInteractions = McpClientInteractions()
    private val scenarioRunner = ScenarioRunner(clientInteractions)

    @AfterAll
    fun tearDown() {
        scenarioRunner.close()
    }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun toolsCapabilitiesMatchPreset(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "tool capabilities") { client ->
            val caps = clientInteractions.awaitFilteredCapabilities(client)
            clientInteractions.assertExpectedToolCapabilities(caps)
        }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun toolCallsSucceed(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "tool invocation") { client ->
            clientInteractions.awaitFilteredCapabilities(client)
            clientInteractions.callExpectedTools(client)
        }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun toolResultsMatchExpectedValues(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "tool result values") { client ->
            clientInteractions.awaitFilteredCapabilities(client)
            clientInteractions.assertExpectedToolResults(client)
        }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun promptsCapabilitiesMatchPreset(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "prompt capabilities") { client ->
            val caps = clientInteractions.awaitFilteredCapabilities(client)
            clientInteractions.assertExpectedPromptCapabilities(caps)
        }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun promptFetchesSucceed(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "prompt fetch") { client ->
            val caps = clientInteractions.awaitFilteredCapabilities(client)
            clientInteractions.fetchExpectedPrompts(client, caps)
        }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun promptResponsesIncludeProvidedName(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "prompt personalization") { client ->
            clientInteractions.awaitFilteredCapabilities(client)
            clientInteractions.assertPromptPersonalizedResponses(client)
        }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun resourcesCapabilitiesMatchPreset(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "resource capabilities") { client ->
            val caps = clientInteractions.awaitFilteredCapabilities(client)
            clientInteractions.assertExpectedResourceCapabilities(caps)
        }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun resourceReadsSucceed(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "resource read") { client ->
            clientInteractions.awaitFilteredCapabilities(client)
            clientInteractions.readExpectedResources(client)
        }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun resourceContentsMatchExpectedValues(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "resource content values") { client ->
            clientInteractions.awaitFilteredCapabilities(client)
            clientInteractions.assertResourceContentsMatch(client)
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

private class ScenarioRunner(
    private val clientInteractions: McpClientInteractions,
    private val scenarioConfig: BroxyCliIntegrationConfig.ScenarioConfig = BroxyCliIntegrationConfig.DEFAULT_SCENARIO,
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
        handle.run("warmup capabilities") { client ->
            clientInteractions.awaitFilteredCapabilities(
                client,
                BroxyCliIntegrationConfig.CAPABILITIES_WARMUP_TIMEOUT_MILLIS,
            )
        }
    }
}
