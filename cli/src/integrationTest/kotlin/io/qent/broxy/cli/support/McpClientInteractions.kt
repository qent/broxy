package io.qent.broxy.cli.support

import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal class McpClientInteractions(
    private val config: BroxyCliIntegrationConfig = BroxyCliIntegrationConfig,
) {
    suspend fun awaitFilteredCapabilities(
        client: McpClient,
        timeoutMillis: Long = config.CAPABILITIES_TIMEOUT_MILLIS,
    ): ServerCapabilities {
        var lastSnapshot: ServerCapabilities? = null
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        var attempt = 0
        while (System.nanoTime() < deadline) {
            attempt += 1
            config.log("Fetching filtered capabilities attempt $attempt")
            val result = fetchCapabilitiesWithTimeout(client) ?: continue
            if (result.isSuccess) {
                val caps = result.getOrThrow()
                lastSnapshot = caps
                if (hasExpectedCapabilities(caps)) {
                    config.log("Received expected filtered capabilities")
                    return caps
                }
            }
            delay(config.CAPABILITIES_DELAY_MILLIS)
        }
        val snapshotMsg =
            buildString {
                append("Timed out waiting for filtered capabilities.")
                lastSnapshot?.let {
                    append(
                        " Last snapshot tools=${it.tools.map {
                                tool ->
                            tool.name
                        }} prompts=${it.prompts.map {
                                prompt ->
                            prompt.name
                        }} resources=${it.resources.map { res -> res.uri ?: res.name }}",
                    )
                }
            }
        config.log(snapshotMsg)
        fail(snapshotMsg)
    }

    fun assertExpectedToolCapabilities(caps: ServerCapabilities) {
        assertEquals(
            config.EXPECTED_TOOLS,
            caps.tools.map { it.name }.toSet(),
            "Tool list should match preset",
        )
    }

    fun assertExpectedPromptCapabilities(caps: ServerCapabilities) {
        assertEquals(
            config.EXPECTED_PROMPTS,
            caps.prompts.map { it.name }.toSet(),
            "Prompt list should match preset",
        )
    }

    fun assertExpectedResourceCapabilities(caps: ServerCapabilities) {
        assertEquals(
            config.EXPECTED_RESOURCES,
            caps.resources.map { it.uri ?: it.name }.toSet(),
            "Resource list should match preset",
        )
    }

    suspend fun callExpectedTools(client: McpClient) {
        config.EXPECTED_TOOLS.forEach { tool ->
            config.log("Invoking tool $tool")
            val args = buildArithmeticArguments()
            val payload = client.callTool(tool, args).getOrFail("callTool $tool").asJsonObject("callTool $tool")
            val isError = payload["isError"]?.jsonPrimitive?.booleanOrNull ?: false
            assertTrue(!isError, "Tool $tool returned error payload: $payload")
        }
    }

    suspend fun fetchExpectedPrompts(
        client: McpClient,
        caps: ServerCapabilities,
    ) {
        val descriptors = caps.prompts.associateBy { it.name }
        config.EXPECTED_PROMPTS.forEach { prompt ->
            config.log("Fetching prompt $prompt")
            val arguments = buildPromptArguments(descriptors[prompt])
            val response = client.getPrompt(prompt, arguments).getOrFail("getPrompt $prompt")
            assertTrue(
                response["messages"] != null,
                "Prompt $prompt should include 'messages' field: $response",
            )
        }
    }

    suspend fun readExpectedResources(client: McpClient) {
        config.EXPECTED_RESOURCES.forEach { uri ->
            config.log("Reading resource $uri")
            val response = client.readResource(uri).getOrFail("readResource $uri")
            val hasResourceObject = response["resource"] != null
            val hasContents = response["contents"] != null
            assertTrue(
                hasResourceObject || hasContents,
                "Resource $uri should include 'resource' or 'contents' field: $response",
            )
        }
    }

    suspend fun assertExpectedToolResults(client: McpClient) {
        config.TOOL_EXPECTATIONS.forEach { (tool, expectation) ->
            val payload =
                client.callTool(tool, buildArithmeticArguments())
                    .getOrFail("callTool $tool")
                    .asJsonObject("callTool $tool")
            assertStructuredResult(
                payload,
                expectedOperation = expectation.operation,
                expectedResult = expectation.expectedResult,
            )
        }
    }

    suspend fun assertPromptPersonalizedResponses(client: McpClient) {
        config.PROMPT_EXPECTATIONS.forEach { (prompt, expectedText) ->
            config.log("Validating prompt payload for $prompt")
            val payload =
                client.getPrompt(prompt, mapOf("name" to config.PROMPT_ARGUMENT_PLACEHOLDER))
                    .getOrFail("getPrompt $prompt")
            val actualText = extractPromptText(payload)
            assertEquals(expectedText, actualText, "Prompt $prompt should render expected text")
        }
    }

    suspend fun assertResourceContentsMatch(client: McpClient) {
        config.RESOURCE_EXPECTATIONS.forEach { (uri, expectedText) ->
            config.log("Validating resource payload for $uri")
            val payload = client.readResource(uri).getOrFail("readResource $uri")
            val text = extractResourceText(payload)
            assertEquals(expectedText, text, "Resource $uri should match expected text")
        }
    }

    private fun hasExpectedCapabilities(caps: ServerCapabilities): Boolean {
        val toolNames = caps.tools.map { it.name }.toSet()
        val promptNames = caps.prompts.map { it.name }.toSet()
        val resourceKeys = caps.resources.map { it.uri ?: it.name }.toSet()
        return toolNames == config.EXPECTED_TOOLS &&
            promptNames == config.EXPECTED_PROMPTS &&
            resourceKeys == config.EXPECTED_RESOURCES
    }

    private fun buildPromptArguments(descriptor: PromptDescriptor?): Map<String, String> {
        if (descriptor == null) return emptyMap()
        val requiredArgs = descriptor.arguments.orEmpty().filter { it.required == true }
        if (requiredArgs.isEmpty()) return emptyMap()
        return requiredArgs.associate { arg ->
            arg.name to config.PROMPT_ARGUMENT_PLACEHOLDER
        }
    }

    private fun buildArithmeticArguments(
        a: Int = config.TOOL_INPUT_A,
        b: Int = config.TOOL_INPUT_B,
    ): JsonObject =
        buildJsonObject {
            put("a", JsonPrimitive(a))
            put("b", JsonPrimitive(b))
        }

    private fun <T> Result<T>.getOrFail(operation: String): T =
        getOrElse { error -> fail("$operation failed: ${error.message ?: error::class.simpleName}") }

    private fun JsonElement.asJsonObject(operation: String): JsonObject =
        this as? JsonObject ?: fail("$operation should return JsonObject but was ${this::class.simpleName}")

    private fun assertStructuredResult(
        payload: JsonObject,
        expectedOperation: String,
        expectedResult: Double,
    ) {
        val structured =
            payload["structuredContent"]?.jsonObject
                ?: fail("structuredContent block is missing: $payload")
        val operation =
            structured["operation"]?.jsonPrimitive?.content
                ?: fail("Tool result missing operation field: $payload")
        assertEquals(expectedOperation, operation, "Tool result should report $expectedOperation operation")
        val actualResult =
            structured["result"]?.jsonPrimitive?.doubleOrNull
                ?: fail("Tool result missing numeric value: $payload")
        assertEquals(expectedResult, actualResult, 0.0001, "Tool result value mismatch for $expectedOperation")
        val isError = payload["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        assertTrue(!isError, "Tool call should not indicate error: $payload")
    }

    private fun extractPromptText(payload: JsonObject): String {
        val messages = payload["messages"]?.jsonArray ?: fail("Prompt payload missing messages: $payload")
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
        val contents = payload["contents"]?.jsonArray ?: fail("Resource payload missing contents: $payload")
        val firstEntry = contents.firstOrNull()?.jsonObject ?: fail("Resource contents missing text entry: $payload")
        return firstEntry["text"]?.jsonPrimitive?.content ?: fail("Resource text missing: $payload")
    }

    private suspend fun fetchCapabilitiesWithTimeout(client: McpClient): Result<ServerCapabilities>? =
        try {
            withTimeout(config.CAPABILITIES_REQUEST_TIMEOUT_MILLIS) {
                client.fetchCapabilities()
            }
        } catch (ex: TimeoutCancellationException) {
            null
        }
}
