package io.qent.broxy.cli.support

import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal class McpClientInteractions(
    private val config: BroxyCliIntegrationConfig = BroxyCliIntegrationConfig
) {
    suspend fun awaitFilteredCapabilities(
        client: McpClient,
        timeoutMillis: Long = config.CAPABILITIES_TIMEOUT_MILLIS
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
        val snapshotMsg = buildString {
            append("Timed out waiting for filtered capabilities.")
            lastSnapshot?.let {
                append(" Last snapshot tools=${it.tools.map { tool -> tool.name }} prompts=${it.prompts.map { prompt -> prompt.name }} resources=${it.resources.map { res -> res.uri ?: res.name }}")
            }
        }
        config.log(snapshotMsg)
        fail(snapshotMsg)
    }

    fun assertExpectedToolCapabilities(caps: ServerCapabilities) {
        assertEquals(
            config.EXPECTED_TOOLS,
            caps.tools.map { it.name }.toSet(),
            "Tool list should match preset"
        )
    }

    fun assertExpectedPromptCapabilities(caps: ServerCapabilities) {
        assertEquals(
            config.EXPECTED_PROMPTS,
            caps.prompts.map { it.name }.toSet(),
            "Prompt list should match preset"
        )
    }

    fun assertExpectedResourceCapabilities(caps: ServerCapabilities) {
        assertEquals(
            config.EXPECTED_RESOURCES,
            caps.resources.map { it.uri ?: it.name }.toSet(),
            "Resource list should match preset"
        )
    }

    suspend fun callExpectedTools(client: McpClient) {
        config.EXPECTED_TOOLS.forEach { tool ->
            config.log("Invoking tool $tool")
            val args = when (tool) {
                "${config.STDIO_SERVER_ID}:${config.ADD_TOOL_NAME}" -> buildArithmeticArguments()
                "${config.HTTP_SERVER_ID}:${config.SUBTRACT_TOOL_NAME}" -> buildArithmeticArguments(a = 5, b = 2)
                else -> JsonObject(emptyMap())
            }
            val payload = client.callTool(tool, args).getOrFail("callTool $tool").asJsonObject("callTool $tool")
            val isError = payload["isError"]?.jsonPrimitive?.booleanOrNull ?: false
            assertTrue(!isError, "Tool $tool returned error payload: $payload")
        }
    }

    suspend fun fetchExpectedPrompts(client: McpClient, caps: ServerCapabilities) {
        val descriptors = caps.prompts.associateBy { it.name }
        config.EXPECTED_PROMPTS.forEach { prompt ->
            config.log("Fetching prompt $prompt")
            val arguments = buildPromptArguments(descriptors[prompt])
            val response = client.getPrompt(prompt, arguments).getOrFail("getPrompt $prompt")
            assertTrue(
                response["messages"] != null,
                "Prompt $prompt should include 'messages' field: $response"
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
                "Resource $uri should include 'resource' or 'contents' field: $response"
            )
        }
    }

    suspend fun assertExpectedToolResults(client: McpClient) {
        val addTool = "${config.STDIO_SERVER_ID}:${config.ADD_TOOL_NAME}"
        val subtractTool = "${config.HTTP_SERVER_ID}:${config.SUBTRACT_TOOL_NAME}"
        val addPayload = client.callTool(addTool, buildArithmeticArguments(a = 2, b = 3))
            .getOrFail("callTool $addTool")
            .asJsonObject("callTool $addTool")
        val subtractPayload = client.callTool(subtractTool, buildArithmeticArguments(a = 5, b = 2))
            .getOrFail("callTool $subtractTool")
            .asJsonObject("callTool $subtractTool")

        assertStructuredResult(addPayload, expectedOperation = "addition", expectedResult = 5.0)
        assertStructuredResult(subtractPayload, expectedOperation = "subtraction", expectedResult = 3.0)
    }

    suspend fun assertPromptPersonalizedResponses(client: McpClient) {
        val name = config.PROMPT_ARGUMENT_PLACEHOLDER
        val expectations = mapOf(
            config.HELLO_PROMPT to "Hello $name!",
            config.BYE_PROMPT to "Bye $name!"
        )

        expectations.forEach { (prompt, expectedText) ->
            config.log("Validating prompt payload for $prompt")
            val payload = client.getPrompt(prompt, mapOf("name" to name)).getOrFail("getPrompt $prompt")
            val actualText = extractPromptText(payload)
            assertEquals(expectedText, actualText, "Prompt $prompt should render expected text")
        }
    }

    suspend fun assertResourceContentsMatch(client: McpClient) {
        val expectations = mapOf(
            config.RESOURCE_ALPHA to "Alpha resource content",
            config.RESOURCE_BETA to "Beta resource content"
        )

        expectations.forEach { (uri, expectedText) ->
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

    private fun buildArithmeticArguments(a: Int = 2, b: Int = 3): JsonObject = buildJsonObject {
        put("a", JsonPrimitive(a))
        put("b", JsonPrimitive(b))
    }

    private fun <T> Result<T>.getOrFail(operation: String): T =
        getOrElse { error -> fail("$operation failed: ${error.message ?: error::class.simpleName}") }

    private fun JsonElement.asJsonObject(operation: String): JsonObject =
        this as? JsonObject ?: fail("$operation should return JsonObject but was ${this::class.simpleName}")

    private fun assertStructuredResult(payload: JsonObject, expectedOperation: String, expectedResult: Double) {
        val structured = payload["structuredContent"]?.jsonObject
            ?: fail("structuredContent block is missing: $payload")
        val operation = structured["operation"]?.jsonPrimitive?.content
            ?: fail("Tool result missing operation field: $payload")
        assertEquals(expectedOperation, operation, "Tool result should report $expectedOperation operation")
        val actualResult = structured["result"]?.jsonPrimitive?.doubleOrNull
            ?: fail("Tool result missing numeric value: $payload")
        assertEquals(expectedResult, actualResult, 0.0001, "Tool result value mismatch for $expectedOperation")
        val isError = payload["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        assertTrue(!isError, "Tool call should not indicate error: $payload")
    }

    private fun extractPromptText(payload: JsonObject): String {
        val messages = payload["messages"]?.jsonArray ?: fail("Prompt payload missing messages: $payload")
        val firstMessage = messages.firstOrNull()?.jsonObject ?: fail("Prompt payload has empty messages: $payload")
        val content = firstMessage["content"] ?: fail("Prompt message missing content: $payload")
        val text = when (content) {
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
