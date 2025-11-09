package io.qent.broxy.cli.support

import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

    private suspend fun fetchCapabilitiesWithTimeout(client: McpClient): Result<ServerCapabilities>? =
        try {
            withTimeout(config.CAPABILITIES_REQUEST_TIMEOUT_MILLIS) {
                client.fetchCapabilities()
            }
        } catch (ex: TimeoutCancellationException) {
            null
        }
}
