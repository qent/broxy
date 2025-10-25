package io.qent.broxy.cli

import io.qent.broxy.cli.support.BroxyCliIntegrationConfig
import io.qent.broxy.cli.support.BroxyCliTestEnvironment
import io.qent.broxy.cli.support.InboundScenario
import io.qent.broxy.cli.support.McpClientInteractions
import io.qent.broxy.cli.support.ScenarioHandle
import io.qent.broxy.core.mcp.McpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import kotlin.test.assertTrue
import kotlin.test.fail

@TestInstance(Lifecycle.PER_CLASS)
internal class BroxyCliNegativeRoutingIntegrationTest {
    private val clientInteractions = McpClientInteractions()
    private lateinit var scenarioHandle: ScenarioHandle

    @BeforeAll
    fun setUp() =
        runBlocking {
            scenarioHandle =
                BroxyCliTestEnvironment.startScenario(
                    inboundScenario = InboundScenario.STDIO,
                    scenarioConfig = BroxyCliIntegrationConfig.NEGATIVE_SCENARIO,
                )
        }

    @AfterAll
    fun tearDown() {
        if (this::scenarioHandle.isInitialized) {
            scenarioHandle.close()
        }
    }

    @Test
    fun disallowedAndDisabledToolsAreRejected() =
        runScenarioTest("negative routing") { client ->
            val allowedTool = "${BroxyCliIntegrationConfig.STDIO_SERVER_ID}_${BroxyCliIntegrationConfig.ADD_TOOL_NAME}"
            val disallowedTool =
                "${BroxyCliIntegrationConfig.HTTP_SERVER_ID}_${BroxyCliIntegrationConfig.SUBTRACT_TOOL_NAME}"
            val disabledTool = "${BroxyCliIntegrationConfig.WS_SERVER_ID}_${BroxyCliIntegrationConfig.DIVIDE_TOOL_NAME}"
            val expectedTools = setOf(allowedTool)

            val caps =
                clientInteractions.awaitCapabilities(
                    client = client,
                    expectedTools = expectedTools,
                    expectedPrompts = emptySet(),
                    expectedResources = emptySet(),
                )
            clientInteractions.assertToolCapabilities(caps, expectedTools)
            clientInteractions.assertPromptCapabilities(caps, emptySet())
            clientInteractions.assertResourceCapabilities(caps, emptySet())

            val allowedExpectation = BroxyCliIntegrationConfig.TOOL_EXPECTATIONS.getValue(allowedTool)
            clientInteractions.assertToolResults(client, mapOf(allowedTool to allowedExpectation))

            assertToolDenied(client, disallowedTool)
            assertToolDenied(client, disabledTool)
        }

    private fun runScenarioTest(
        description: String,
        block: suspend (McpClient) -> Unit,
    ) = runBlocking {
        withTimeout(BroxyCliIntegrationConfig.TEST_TIMEOUT_MILLIS) {
            scenarioHandle.run(description, block)
        }
    }

    private suspend fun assertToolDenied(
        client: McpClient,
        toolName: String,
    ) {
        val result =
            client.callTool(toolName, buildArithmeticArguments()).getOrElse { error ->
                fail("Tool '$toolName' should be rejected but failed unexpectedly: ${error.message}")
            }
        val payload = result.asJsonObject("callTool $toolName")
        val isError = payload["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        assertTrue(isError, "Tool '$toolName' should be rejected")
        val message =
            payload["structuredContent"]
                ?.jsonObject
                ?.get("error")
                ?.jsonPrimitive
                ?.content
                ?: ""
        if (message.isNotBlank()) {
            assertTrue(
                message.contains("not allowed by current preset"),
                "Tool '$toolName' should be denied with a clear error but got '$message'",
            )
        }
    }

    private fun buildArithmeticArguments(): JsonObject =
        buildJsonObject {
            put("a", JsonPrimitive(BroxyCliIntegrationConfig.TOOL_INPUT_A))
            put("b", JsonPrimitive(BroxyCliIntegrationConfig.TOOL_INPUT_B))
        }

    private fun JsonElement.asJsonObject(operation: String): JsonObject =
        this as? JsonObject ?: fail("$operation should return JsonObject but was ${this::class.simpleName}")
}
