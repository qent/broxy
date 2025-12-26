package io.qent.broxy.cli

import io.qent.broxy.cli.support.BroxyCliIntegrationConfig
import io.qent.broxy.cli.support.InboundScenario
import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal class BroxyCliFallbackToolsIntegrationTest :
    BaseBroxyCliIntegrationTest(
        inboundScenario = InboundScenario.STDIO,
        scenarioConfig = BroxyCliIntegrationConfig.FALLBACK_SCENARIO,
        skipWarmup = true,
    ) {
    @Test
    fun promptFallbackToolWithArguments() =
        runScenarioTest("fallback prompt tool with args") { client ->
            val caps = awaitFallbackCapabilities(client)
            val tool = findTool(caps, PROMPT_TOOL_WITH_ARGS)
            assertEquals(PROMPT_WITH_ARGS_DESCRIPTION, tool.description)
            assertToolSchema(tool, required = setOf(PROMPT_ARGUMENT_NAME))
            val payload =
                client.callTool(
                    PROMPT_TOOL_WITH_ARGS,
                    buildJsonObject { put(PROMPT_ARGUMENT_NAME, JsonPrimitive(PROMPT_ARGUMENT_VALUE)) },
                ).getOrFail("callTool $PROMPT_TOOL_WITH_ARGS").asJsonObject("callTool $PROMPT_TOOL_WITH_ARGS")
            assertToolSuccess(payload)
            assertEquals(EXPECTED_PROMPT_WITH_ARGS, extractPromptText(payload))
        }

    @Test
    fun promptFallbackToolWithoutArguments() =
        runScenarioTest("fallback prompt tool without args") { client ->
            val caps = awaitFallbackCapabilities(client)
            val tool = findTool(caps, PROMPT_TOOL_NO_ARGS)
            assertEquals(PROMPT_NO_ARGS_DESCRIPTION, tool.description)
            assertToolSchema(tool, required = emptySet())
            val payload =
                client.callTool(PROMPT_TOOL_NO_ARGS, JsonObject(emptyMap()))
                    .getOrFail("callTool $PROMPT_TOOL_NO_ARGS")
                    .asJsonObject("callTool $PROMPT_TOOL_NO_ARGS")
            assertToolSuccess(payload)
            assertEquals(EXPECTED_PROMPT_NO_ARGS, extractPromptText(payload))
        }

    @Test
    fun resourceFallbackToolWithArguments() =
        runScenarioTest("fallback resource tool with args") { client ->
            val caps = awaitFallbackCapabilities(client)
            val tool = findTool(caps, RESOURCE_TOOL_WITH_ARGS)
            assertEquals(RESOURCE_WITH_ARGS_DESCRIPTION, tool.description)
            assertToolSchema(tool, required = setOf(RESOURCE_ARGUMENT_NAME))
            val payload =
                client.callTool(
                    RESOURCE_TOOL_WITH_ARGS,
                    buildJsonObject { put(RESOURCE_ARGUMENT_NAME, JsonPrimitive("sample")) },
                ).getOrFail("callTool $RESOURCE_TOOL_WITH_ARGS").asJsonObject("callTool $RESOURCE_TOOL_WITH_ARGS")
            assertToolSuccess(payload)
            assertEquals(EXPECTED_RESOURCE_WITH_ARGS, extractResourceText(payload))
        }

    @Test
    fun resourceFallbackToolWithoutArguments() =
        runScenarioTest("fallback resource tool without args") { client ->
            val caps = awaitFallbackCapabilities(client)
            val tool = findTool(caps, RESOURCE_TOOL_NO_ARGS)
            assertEquals(RESOURCE_NO_ARGS_DESCRIPTION, tool.description)
            assertToolSchema(tool, required = emptySet())
            val payload =
                client.callTool(RESOURCE_TOOL_NO_ARGS, JsonObject(emptyMap()))
                    .getOrFail("callTool $RESOURCE_TOOL_NO_ARGS")
                    .asJsonObject("callTool $RESOURCE_TOOL_NO_ARGS")
            assertToolSuccess(payload)
            assertEquals(EXPECTED_RESOURCE_NO_ARGS, extractResourceText(payload))
        }

    private suspend fun awaitFallbackCapabilities(client: McpClient): ServerCapabilities {
        val expectedTools = setOf(PROMPT_TOOL_WITH_ARGS, PROMPT_TOOL_NO_ARGS, RESOURCE_TOOL_WITH_ARGS, RESOURCE_TOOL_NO_ARGS)
        var lastSnapshot: ServerCapabilities? = null
        val deadline = System.nanoTime() + BroxyCliIntegrationConfig.CAPABILITIES_TIMEOUT_MILLIS * 1_000_000
        var attempt = 0
        while (System.nanoTime() < deadline) {
            attempt += 1
            BroxyCliIntegrationConfig.log("Fetching fallback capabilities attempt $attempt")
            val result = fetchCapabilitiesWithTimeout(client) ?: continue
            if (result.isSuccess) {
                val caps = result.getOrThrow()
                lastSnapshot = caps
                val toolNames = caps.tools.map { it.name }.toSet()
                if (toolNames.containsAll(expectedTools)) {
                    return caps
                }
            }
            delay(BroxyCliIntegrationConfig.CAPABILITIES_DELAY_MILLIS)
        }
        val snapshotMsg =
            buildString {
                append("Timed out waiting for fallback tools.")
                lastSnapshot?.let {
                    append(" Last snapshot tools=${it.tools.map { tool -> tool.name }}")
                }
            }
        BroxyCliIntegrationConfig.log(snapshotMsg)
        fail(snapshotMsg)
    }

    private suspend fun fetchCapabilitiesWithTimeout(client: McpClient): Result<ServerCapabilities>? =
        try {
            withTimeout(BroxyCliIntegrationConfig.CAPABILITIES_REQUEST_TIMEOUT_MILLIS) {
                client.fetchCapabilities()
            }
        } catch (ex: TimeoutCancellationException) {
            null
        }

    private fun findTool(
        caps: ServerCapabilities,
        name: String,
    ): ToolDescriptor {
        return caps.tools.firstOrNull { it.name == name }
            ?: fail("Tool '$name' missing from ${caps.tools.map { it.name }}")
    }

    private fun assertToolSchema(
        tool: ToolDescriptor,
        required: Set<String>,
    ) {
        val schema = tool.inputSchema
        val actualRequired = schema?.required?.toSet().orEmpty()
        val actualProperties = schema?.properties?.keys?.toSet().orEmpty()
        assertEquals(required, actualRequired, "Required args mismatch for ${tool.name}")
        assertEquals(required, actualProperties, "Schema properties mismatch for ${tool.name}")
    }

    private fun assertToolSuccess(payload: JsonObject) {
        val isError = payload["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        assertTrue(!isError, "Tool call returned error: $payload")
    }

    private fun extractPromptText(payload: JsonObject): String {
        val structured = payload["structuredContent"]?.jsonObject ?: fail("Missing structuredContent: $payload")
        val messages = structured["messages"]?.jsonArray ?: fail("Prompt payload missing messages: $payload")
        val firstMessage = messages.firstOrNull()?.jsonObject ?: fail("Prompt payload has empty messages: $payload")
        val content = firstMessage["content"] ?: fail("Prompt message missing content: $payload")
        val text =
            when (content) {
                is JsonArray -> content.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                is JsonObject -> content["text"]?.jsonPrimitive?.content
                else -> null
            } ?: fail("Prompt content missing text: $payload")
        return text
    }

    private fun extractResourceText(payload: JsonObject): String {
        val structured = payload["structuredContent"]?.jsonObject ?: fail("Missing structuredContent: $payload")
        val contents = structured["contents"]?.jsonArray ?: fail("Resource payload missing contents: $payload")
        val firstEntry = contents.firstOrNull()?.jsonObject ?: fail("Resource contents missing text entry: $payload")
        return firstEntry["text"]?.jsonPrimitive?.content ?: fail("Resource text missing: $payload")
    }

    private fun <T> Result<T>.getOrFail(operation: String): T =
        getOrElse { error -> fail("$operation failed: ${error.message ?: error::class.simpleName}") }

    private fun JsonElement.asJsonObject(operation: String): JsonObject =
        this as? JsonObject ?: fail("$operation should return JsonObject but was ${this::class.simpleName}")
}

private const val PROMPT_PREFIX = "prompt"
private const val RESOURCE_PREFIX = "resource"
private const val PROMPT_ARGUMENT_NAME = "name"
private const val RESOURCE_ARGUMENT_NAME = "id"
private const val PROMPT_ARGUMENT_VALUE = BroxyCliIntegrationConfig.PROMPT_ARGUMENT_PLACEHOLDER

private const val PROMPT_NAME_WITH_ARGS = "hello_stdio"
private const val PROMPT_NAME_NO_ARGS = "hello_stdio_plain"
private const val RESOURCE_KEY_NO_ARGS = "test://resource/stdio"
private const val RESOURCE_KEY_WITH_ARGS = "test://resource/stdio/{id}"

private const val PROMPT_TOOL_WITH_ARGS = "$PROMPT_PREFIX:$PROMPT_NAME_WITH_ARGS"
private const val PROMPT_TOOL_NO_ARGS = "$PROMPT_PREFIX:$PROMPT_NAME_NO_ARGS"
private const val RESOURCE_TOOL_NO_ARGS = "$RESOURCE_PREFIX:$RESOURCE_KEY_NO_ARGS"
private const val RESOURCE_TOOL_WITH_ARGS = "$RESOURCE_PREFIX:$RESOURCE_KEY_WITH_ARGS"

private const val PROMPT_WITH_ARGS_DESCRIPTION = "Says hello via STDIO"
private const val PROMPT_NO_ARGS_DESCRIPTION = "Says hello without arguments via STDIO"
private const val RESOURCE_NO_ARGS_DESCRIPTION = "STDIO sample text"
private const val RESOURCE_WITH_ARGS_DESCRIPTION = "STDIO template text"

private const val EXPECTED_PROMPT_WITH_ARGS = "Hello stdio $PROMPT_ARGUMENT_VALUE!"
private const val EXPECTED_PROMPT_NO_ARGS = "Hello stdio!"
private const val EXPECTED_RESOURCE_NO_ARGS = "STDIO resource content"
private const val EXPECTED_RESOURCE_WITH_ARGS = "STDIO template resource content"
