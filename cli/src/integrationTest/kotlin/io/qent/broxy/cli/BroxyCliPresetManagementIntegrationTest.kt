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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(Lifecycle.PER_CLASS)
internal class BroxyCliPresetManagementIntegrationTest {
    private val clientInteractions = McpClientInteractions()
    private val scenarioRunner =
        PresetManagementScenarioRunner(
            clientInteractions = clientInteractions,
            scenarioConfig = BroxyCliIntegrationConfig.PRESET_MANAGEMENT_SCENARIO,
            skipWarmup = true,
        )

    @AfterAll
    fun tearDown() {
        scenarioRunner.close()
    }

    @ParameterizedTest
    @EnumSource(InboundScenario::class)
    fun startup_with_management_preset_exposes_six_management_tools_only(inboundScenario: InboundScenario) =
        runScenarioTest(inboundScenario, "management preset capabilities") { client ->
            val capabilities =
                clientInteractions.awaitCapabilities(
                    client = client,
                    expectedTools = BroxyCliIntegrationConfig.PRESET_MANAGEMENT_TOOL_NAMES,
                    expectedPrompts = emptySet(),
                    expectedResources = emptySet(),
                )
            assertEquals(BroxyCliIntegrationConfig.PRESET_MANAGEMENT_TOOL_NAMES, capabilities.tools.map { it.name }.toSet())
            assertTrue(capabilities.prompts.isEmpty())
            assertTrue(capabilities.resources.isEmpty())
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

private class PresetManagementScenarioRunner(
    private val clientInteractions: McpClientInteractions,
    private val scenarioConfig: BroxyCliIntegrationConfig.ScenarioConfig,
    private val skipWarmup: Boolean,
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
