package io.qent.broxy.cli

import io.qent.broxy.cli.support.InboundScenario
import io.qent.broxy.cli.support.McpClientInteractions
import kotlin.test.Test

class BroxyCliHttpSseIntegrationTest : BaseBroxyCliIntegrationTest(InboundScenario.HTTP_SSE) {
    @Test
    fun toolsCapabilitiesMatchPreset() = runScenarioTest("tool capabilities") { client ->
        val caps = McpClientInteractions.awaitFilteredCapabilities(client)
        McpClientInteractions.assertExpectedToolCapabilities(caps)
    }

    @Test
    fun toolCallsSucceed() = runScenarioTest("tool invocation") { client ->
        McpClientInteractions.awaitFilteredCapabilities(client)
        McpClientInteractions.callExpectedTools(client)
    }

    @Test
    fun promptsCapabilitiesMatchPreset() = runScenarioTest("prompt capabilities") { client ->
        val caps = McpClientInteractions.awaitFilteredCapabilities(client)
        McpClientInteractions.assertExpectedPromptCapabilities(caps)
    }

    @Test
    fun promptFetchesSucceed() = runScenarioTest("prompt fetch") { client ->
        val caps = McpClientInteractions.awaitFilteredCapabilities(client)
        McpClientInteractions.fetchExpectedPrompts(client, caps)
    }

    @Test
    fun resourcesCapabilitiesMatchPreset() = runScenarioTest("resource capabilities") { client ->
        val caps = McpClientInteractions.awaitFilteredCapabilities(client)
        McpClientInteractions.assertExpectedResourceCapabilities(caps)
    }

    @Test
    fun resourceReadsSucceed() = runScenarioTest("resource read") { client ->
        McpClientInteractions.awaitFilteredCapabilities(client)
        McpClientInteractions.readExpectedResources(client)
    }
}
