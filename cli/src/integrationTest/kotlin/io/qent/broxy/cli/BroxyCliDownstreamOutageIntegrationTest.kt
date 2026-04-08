package io.qent.broxy.cli

import io.qent.broxy.cli.support.BroxyCliIntegrationConfig
import io.qent.broxy.cli.support.BroxyCliTestEnvironment
import io.qent.broxy.cli.support.DownstreamTarget
import io.qent.broxy.cli.support.InboundScenario
import io.qent.broxy.cli.support.McpClientInteractions
import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.ServerCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertTrue
import kotlin.test.fail

internal class BroxyCliDownstreamOutageIntegrationTest {
    private val clientInteractions = McpClientInteractions()

    @ParameterizedTest
    @EnumSource(DownstreamTarget::class)
    fun downstreamOutageIsIsolated(target: DownstreamTarget) =
        runBlocking {
            val scenarioHandle =
                BroxyCliTestEnvironment.startScenario(
                    inboundScenario = InboundScenario.STDIO,
                    scenarioConfig = BroxyCliIntegrationConfig.OUTAGE_SCENARIO,
                )
            try {
                scenarioHandle.run("warmup capabilities") { client ->
                    clientInteractions.awaitFilteredCapabilities(client)
                }

                scenarioHandle.stopDownstream(target)
                delay(250)

                scenarioHandle.run("post-outage capabilities") { client ->
                    val caps = fetchCapabilitiesOrFail(client, "post-outage")
                    clientInteractions.assertExpectedToolCapabilities(caps)
                    clientInteractions.assertExpectedPromptCapabilities(caps)
                    clientInteractions.assertExpectedResourceCapabilities(caps)
                }

                scenarioHandle.run("post-outage tool calls") { client ->
                    val deadTool = deadToolFor(target)
                    val healthyTool = healthyToolFor(target)
                    assertToolFailure(client, deadTool)
                    val expectation = BroxyCliIntegrationConfig.TOOL_EXPECTATIONS.getValue(healthyTool)
                    clientInteractions.assertToolResults(client, mapOf(healthyTool to expectation))
                }
            } finally {
                scenarioHandle.close()
            }
        }

    private fun deadToolFor(target: DownstreamTarget): String =
        when (target) {
            DownstreamTarget.STDIO ->
                "${BroxyCliIntegrationConfig.STDIO_SERVER_ID}_${BroxyCliIntegrationConfig.ADD_TOOL_NAME}"
            DownstreamTarget.HTTP_STREAMABLE ->
                "${BroxyCliIntegrationConfig.HTTP_SERVER_ID}_${BroxyCliIntegrationConfig.SUBTRACT_TOOL_NAME}"
            DownstreamTarget.HTTP_SSE ->
                "${BroxyCliIntegrationConfig.SSE_SERVER_ID}_${BroxyCliIntegrationConfig.MULTIPLY_TOOL_NAME}"
            DownstreamTarget.WS ->
                "${BroxyCliIntegrationConfig.WS_SERVER_ID}_${BroxyCliIntegrationConfig.DIVIDE_TOOL_NAME}"
        }

    private fun healthyToolFor(target: DownstreamTarget): String =
        when (target) {
            DownstreamTarget.STDIO ->
                "${BroxyCliIntegrationConfig.HTTP_SERVER_ID}_${BroxyCliIntegrationConfig.SUBTRACT_TOOL_NAME}"
            DownstreamTarget.HTTP_STREAMABLE ->
                "${BroxyCliIntegrationConfig.STDIO_SERVER_ID}_${BroxyCliIntegrationConfig.ADD_TOOL_NAME}"
            DownstreamTarget.HTTP_SSE ->
                "${BroxyCliIntegrationConfig.STDIO_SERVER_ID}_${BroxyCliIntegrationConfig.ADD_TOOL_NAME}"
            DownstreamTarget.WS ->
                "${BroxyCliIntegrationConfig.STDIO_SERVER_ID}_${BroxyCliIntegrationConfig.ADD_TOOL_NAME}"
        }

    private suspend fun assertToolFailure(
        client: McpClient,
        toolName: String,
    ) {
        val result =
            client.callTool(toolName, buildArithmeticArguments()).getOrElse { error ->
                fail("Tool '$toolName' should fail after downstream outage: ${error.message}")
            }
        val payload = result.asJsonObject("callTool $toolName")
        val isError = payload["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        assertTrue(isError, "Tool '$toolName' should fail after downstream outage")
        val message =
            payload["structuredContent"]
                ?.jsonObject
                ?.get("error")
                ?.jsonPrimitive
                ?.content
                ?: ""
        assertTrue(message.isNotBlank(), "Tool '$toolName' should return a clear error")
    }

    private suspend fun fetchCapabilitiesOrFail(
        client: McpClient,
        label: String,
    ): ServerCapabilities =
        client
            .fetchCapabilities()
            .getOrElse { error ->
                fail("$label fetchCapabilities failed: ${error.message ?: error::class.simpleName}")
            }

    private fun buildArithmeticArguments(): JsonObject =
        buildJsonObject {
            put("a", JsonPrimitive(BroxyCliIntegrationConfig.TOOL_INPUT_A))
            put("b", JsonPrimitive(BroxyCliIntegrationConfig.TOOL_INPUT_B))
        }

    private fun JsonElement.asJsonObject(operation: String): JsonObject =
        this as? JsonObject ?: fail("$operation should return JsonObject but was ${this::class.simpleName}")
}
