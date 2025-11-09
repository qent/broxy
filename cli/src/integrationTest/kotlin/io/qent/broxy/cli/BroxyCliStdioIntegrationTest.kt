package io.qent.broxy.cli

import io.qent.broxy.cli.support.InboundScenario
import kotlin.test.Test

internal class BroxyCliStdioIntegrationTest : BaseBroxyCliIntegrationTest(InboundScenario.STDIO) {
    @Test
    fun toolsCapabilitiesMatchPreset() = runScenarioTest("tool capabilities") { client ->
        val caps = clientInteractions.awaitFilteredCapabilities(client)
        clientInteractions.assertExpectedToolCapabilities(caps)
    }

    @Test
    fun toolCallsSucceed() = runScenarioTest("tool invocation") { client ->
        clientInteractions.awaitFilteredCapabilities(client)
        clientInteractions.callExpectedTools(client)
    }

    @Test
    fun toolResultsMatchExpectedValues() = runScenarioTest("tool result values") { client ->
        clientInteractions.awaitFilteredCapabilities(client)
        clientInteractions.assertExpectedToolResults(client)
    }

    @Test
    fun promptsCapabilitiesMatchPreset() = runScenarioTest("prompt capabilities") { client ->
        val caps = clientInteractions.awaitFilteredCapabilities(client)
        clientInteractions.assertExpectedPromptCapabilities(caps)
    }

    @Test
    fun promptFetchesSucceed() = runScenarioTest("prompt fetch") { client ->
        val caps = clientInteractions.awaitFilteredCapabilities(client)
        clientInteractions.fetchExpectedPrompts(client, caps)
    }

    @Test
    fun promptResponsesIncludeProvidedName() = runScenarioTest("prompt personalization") { client ->
        clientInteractions.awaitFilteredCapabilities(client)
        clientInteractions.assertPromptPersonalizedResponses(client)
    }

    @Test
    fun resourcesCapabilitiesMatchPreset() = runScenarioTest("resource capabilities") { client ->
        val caps = clientInteractions.awaitFilteredCapabilities(client)
        clientInteractions.assertExpectedResourceCapabilities(caps)
    }

    @Test
    fun resourceReadsSucceed() = runScenarioTest("resource read") { client ->
        clientInteractions.awaitFilteredCapabilities(client)
        clientInteractions.readExpectedResources(client)
    }

    @Test
    fun resourceContentsMatchExpectedValues() = runScenarioTest("resource content values") { client ->
        clientInteractions.awaitFilteredCapabilities(client)
        clientInteractions.assertResourceContentsMatch(client)
    }
}
