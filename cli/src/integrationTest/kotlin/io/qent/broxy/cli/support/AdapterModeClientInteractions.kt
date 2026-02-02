package io.qent.broxy.cli.support

import io.qent.broxy.core.mcp.McpClient
import io.qent.broxy.core.mcp.ServerCapabilities
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

internal data class AdapterActionArgument(
    val name: String,
    val description: String,
    val required: Boolean,
)

internal data class AdapterActionDescriptor(
    val name: String,
    val description: String,
    val arguments: List<AdapterActionArgument>,
)

internal data class AdapterAvailableActions(
    val tools: List<AdapterActionDescriptor>,
    val prompts: List<AdapterActionDescriptor>,
    val resources: List<AdapterActionDescriptor>,
    val resourceTemplates: List<AdapterActionDescriptor>,
) {
    val toolNames: Set<String> = tools.map { it.name }.toSet()
    val promptNames: Set<String> = prompts.map { it.name }.toSet()
    val resourceNames: Set<String> = resources.map { it.name }.toSet()
    val resourceTemplateNames: Set<String> = resourceTemplates.map { it.name }.toSet()

    fun templateDescriptor(name: String): AdapterActionDescriptor? = resourceTemplates.firstOrNull { it.name == name }
}

internal class AdapterModeClientInteractions(
    private val config: BroxyCliIntegrationConfig = BroxyCliIntegrationConfig,
) {
    private val base = McpClientInteractions(config)

    suspend fun awaitAdapterCapabilities(
        client: McpClient,
        timeoutMillis: Long = config.CAPABILITIES_TIMEOUT_MILLIS,
    ): ServerCapabilities =
        base.awaitCapabilities(
            client = client,
            expectedTools = config.ADAPTER_TOOL_NAMES,
            expectedPrompts = emptySet(),
            expectedResources = emptySet(),
            timeoutMillis = timeoutMillis,
        )

    suspend fun fetchAvailableActions(client: McpClient): AdapterAvailableActions {
        val payload =
            callTool(
                client = client,
                toolName = config.ADAPTER_GET_ACTIONS_TOOL,
                arguments = JsonObject(emptyMap()),
            )
        assertNotError(payload, config.ADAPTER_GET_ACTIONS_TOOL)
        val structured = payload.structuredContentOrFail(config.ADAPTER_GET_ACTIONS_TOOL)
        return parseAvailableActions(structured)
    }

    suspend fun awaitAvailableActions(
        client: McpClient,
        expectedTools: Set<String>,
        expectedPrompts: Set<String>,
        expectedResources: Set<String>,
        expectedResourceTemplates: Set<String>,
        timeoutMillis: Long = config.CAPABILITIES_TIMEOUT_MILLIS,
    ): AdapterAvailableActions {
        var lastSnapshot: AdapterAvailableActions? = null
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        var attempt = 0
        while (System.nanoTime() < deadline) {
            attempt += 1
            config.log("Fetching available actions attempt $attempt")
            val actions = fetchAvailableActions(client)
            lastSnapshot = actions
            if (actions.toolNames == expectedTools &&
                actions.promptNames == expectedPrompts &&
                actions.resourceNames == expectedResources &&
                actions.resourceTemplateNames == expectedResourceTemplates
            ) {
                return actions
            }
            kotlinx.coroutines.delay(config.CAPABILITIES_DELAY_MILLIS)
        }
        val snapshotMsg =
            buildString {
                append("Timed out waiting for available actions.")
                lastSnapshot?.let {
                    append(
                        " Last snapshot tools=${it.toolNames} prompts=${it.promptNames} resources=${it.resourceNames} " +
                            "templates=${it.resourceTemplateNames}",
                    )
                }
            }
        config.log(snapshotMsg)
        fail(snapshotMsg)
    }

    fun assertAvailableActions(
        actions: AdapterAvailableActions,
        expectedTools: Set<String>,
        expectedPrompts: Set<String>,
        expectedResources: Set<String>,
        expectedResourceTemplates: Set<String>,
    ) {
        assertEquals(expectedTools, actions.toolNames, "Adapter tools list mismatch")
        assertEquals(expectedPrompts, actions.promptNames, "Adapter prompts list mismatch")
        assertEquals(expectedResources, actions.resourceNames, "Adapter resources list mismatch")
        assertEquals(expectedResourceTemplates, actions.resourceTemplateNames, "Adapter resource templates list mismatch")
    }

    fun assertTemplateArguments(
        actions: AdapterAvailableActions,
        expectedTemplates: Set<String>,
        requiredArgument: String = config.RESOURCE_TEMPLATE_ARGUMENT_NAME,
    ) {
        expectedTemplates.forEach { template ->
            val descriptor =
                actions.templateDescriptor(template)
                    ?: fail("Missing resource template descriptor for $template")
            val arg =
                descriptor.arguments.firstOrNull { it.name == requiredArgument }
                    ?: fail("Resource template $template missing argument '$requiredArgument'")
            assertTrue(arg.required, "Resource template $template argument '$requiredArgument' should be required")
        }
    }

    suspend fun executeToolAction(
        client: McpClient,
        toolName: String,
        arguments: JsonObject,
    ): JsonObject {
        val payload = executeAction(client, "tool", toolName, arguments)
        return payload.structuredContentOrFail("execute_action tool $toolName")
    }

    suspend fun executePromptAction(
        client: McpClient,
        promptName: String,
        arguments: JsonObject?,
    ): JsonObject {
        val payload = executeAction(client, "prompt", promptName, arguments)
        return payload.structuredContentOrFail("execute_action prompt $promptName")
    }

    suspend fun executeResourceAction(
        client: McpClient,
        resourceUri: String,
    ): JsonObject {
        val payload = executeAction(client, "resource", resourceUri, null)
        return payload.structuredContentOrFail("execute_action resource $resourceUri")
    }

    suspend fun executeResourceTemplateAction(
        client: McpClient,
        templateUri: String,
        arguments: JsonObject,
    ): JsonObject {
        val payload = executeAction(client, "resource_template", templateUri, arguments)
        return payload.structuredContentOrFail("execute_action resource_template $templateUri")
    }

    suspend fun assertActionDenied(
        client: McpClient,
        actionType: String,
        name: String,
        arguments: JsonObject,
    ) {
        val payload = executeActionRaw(client, actionType, name, arguments)
        val isError = payload["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        assertTrue(isError, "Expected $actionType $name to be rejected")
    }

    fun assertStructuredResult(
        payload: JsonObject,
        expectedOperation: String,
        expectedResult: Double,
    ) {
        val operation =
            payload["operation"]?.jsonPrimitive?.content
                ?: fail("Tool result missing operation field: $payload")
        assertEquals(expectedOperation, operation, "Tool result should report $expectedOperation operation")
        val actualResult =
            payload["result"]?.jsonPrimitive?.doubleOrNull
                ?: fail("Tool result missing numeric value: $payload")
        assertEquals(expectedResult, actualResult, 0.0001, "Tool result value mismatch for $expectedOperation")
    }

    fun extractPromptText(payload: JsonObject): String {
        val messages = payload["messages"]?.jsonArray ?: fail("Prompt payload missing messages: $payload")
        val firstMessage = messages.firstOrNull()?.jsonObject ?: fail("Prompt payload has empty messages: $payload")
        val content = firstMessage["content"] ?: fail("Prompt message missing content: $payload")
        val text =
            when (content) {
                is JsonArray ->
                    content
                        .firstOrNull()
                        ?.jsonObject
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.content
                is JsonObject -> content["text"]?.jsonPrimitive?.content
                else -> null
            } ?: fail("Prompt content missing text: $payload")
        return text
    }

    fun extractResourceText(payload: JsonObject): String {
        val contents = payload["contents"]?.jsonArray ?: fail("Resource payload missing contents: $payload")
        val firstEntry = contents.firstOrNull()?.jsonObject ?: fail("Resource contents missing text entry: $payload")
        return firstEntry["text"]?.jsonPrimitive?.content ?: fail("Resource text missing: $payload")
    }

    fun buildArithmeticArguments(
        a: Int = config.TOOL_INPUT_A,
        b: Int = config.TOOL_INPUT_B,
    ): JsonObject =
        buildJsonObject {
            put("a", JsonPrimitive(a))
            put("b", JsonPrimitive(b))
        }

    fun buildPromptArguments(name: String = config.PROMPT_ARGUMENT_PLACEHOLDER): JsonObject =
        buildJsonObject {
            put(BroxyCliIntegrationConfig.PROMPT_ARGUMENT_NAME, JsonPrimitive(name))
        }

    fun buildTemplateArguments(value: String = config.RESOURCE_TEMPLATE_ARGUMENT_VALUE): JsonObject =
        buildJsonObject {
            put(config.RESOURCE_TEMPLATE_ARGUMENT_NAME, JsonPrimitive(value))
        }

    private suspend fun executeAction(
        client: McpClient,
        actionType: String,
        name: String,
        arguments: JsonObject?,
    ): JsonObject {
        val payload = executeActionRaw(client, actionType, name, arguments)
        assertNotError(payload, "execute_action $actionType $name")
        return payload
    }

    private suspend fun executeActionRaw(
        client: McpClient,
        actionType: String,
        name: String,
        arguments: JsonObject?,
    ): JsonObject {
        val args =
            buildJsonObject {
                put("action_type", JsonPrimitive(actionType))
                put("name", JsonPrimitive(name))
                if (arguments != null) {
                    put("arguments", arguments)
                }
            }
        return callTool(client, config.ADAPTER_EXECUTE_ACTION_TOOL, args)
    }

    private suspend fun callTool(
        client: McpClient,
        toolName: String,
        arguments: JsonObject,
    ): JsonObject {
        val result =
            client
                .callTool(toolName, arguments)
                .getOrElse { error -> fail("callTool $toolName failed: ${error.message ?: error::class.simpleName}") }
        return result.asJsonObject("callTool $toolName")
    }

    private fun assertNotError(
        payload: JsonObject,
        operation: String,
    ) {
        val isError = payload["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        assertTrue(!isError, "$operation should not return error: $payload")
    }

    private fun parseAvailableActions(payload: JsonObject): AdapterAvailableActions =
        AdapterAvailableActions(
            tools = parseActionList(payload, "tools"),
            prompts = parseActionList(payload, "prompts"),
            resources = parseActionList(payload, "resources"),
            resourceTemplates = parseActionList(payload, "resource_templates"),
        )

    private fun parseActionList(
        payload: JsonObject,
        key: String,
    ): List<AdapterActionDescriptor> {
        val array = payload[key]?.jsonArray ?: fail("Missing '$key' in available actions payload")
        return array.map { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: fail("Action $key missing name: $obj")
            val description = obj["description"]?.jsonPrimitive?.content ?: name
            val args = obj["arguments"]?.jsonArray?.map { parseArgument(it) } ?: emptyList()
            AdapterActionDescriptor(name = name, description = description, arguments = args)
        }
    }

    private fun parseArgument(element: JsonElement): AdapterActionArgument {
        val obj = element.jsonObject
        val name = obj["name"]?.jsonPrimitive?.content ?: fail("Action argument missing name: $obj")
        val description = obj["description"]?.jsonPrimitive?.content ?: name
        val required = obj["required"]?.jsonPrimitive?.booleanOrNull ?: false
        return AdapterActionArgument(name = name, description = description, required = required)
    }

    private fun JsonElement.asJsonObject(operation: String): JsonObject =
        this as? JsonObject ?: fail("$operation should return JsonObject but was ${this::class.simpleName}")

    private fun JsonObject.structuredContentOrFail(operation: String): JsonObject =
        this["structuredContent"]?.jsonObject
            ?: fail("$operation missing structuredContent: $this")
}
