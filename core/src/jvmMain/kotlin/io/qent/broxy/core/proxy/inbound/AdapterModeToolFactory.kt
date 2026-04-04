package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.utils.LogEventBuilder
import io.qent.broxy.core.utils.LogRequestContext
import io.qent.broxy.core.utils.LogRequestType
import io.qent.broxy.core.utils.LogTargetContext
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import io.qent.broxy.core.mcp.ServerCapabilities as ProxyServerCapabilities

@Suppress("TooManyFunctions")
internal object AdapterModeToolFactory {
    private const val GET_AVAILABLE_ACTIONS = "get_available_actions"
    private const val EXECUTE_ACTION = "execute_action"
    private val actionTypes = listOf("tool", "prompt", "resource", "resource_template")

    fun buildAdapterTools(
        capabilitiesProvider: () -> ProxyServerCapabilities,
        backend: ProxyBackend,
        logger: Logger,
        json: Json,
    ): List<RegisteredTool> =
        listOf(
            buildAvailableActionsTool(capabilitiesProvider, logger),
            buildExecuteActionTool(backend, logger, json),
        )

    private fun buildAvailableActionsTool(
        capabilitiesProvider: () -> ProxyServerCapabilities,
        logger: Logger,
    ): RegisteredTool =
        RegisteredTool(
            tool =
                Tool(
                    name = GET_AVAILABLE_ACTIONS,
                    title = null,
                    description = "Returns the tools, prompts, and resources you can currently use.",
                    inputSchema = ToolSchema(),
                    outputSchema = null,
                    annotations = null,
                ),
            handler = { req ->
                val requestContext = LogRequestContext(logger, LogRequestType.TOOL, GET_AVAILABLE_ACTIONS)
                LogEventBuilder.llmToFacadeRequest(
                    request = requestContext,
                    arguments = req.arguments,
                    meta = req.meta?.json,
                )
                val payload = buildAvailableActionsPayload(capabilitiesProvider())
                val result = successResult(payload)
                val responseJson = Json.encodeToJsonElement(CallToolResult.serializer(), result)
                LogEventBuilder.facadeToLlmResponse(
                    request = requestContext,
                    response = responseJson,
                )
                result
            },
        )

    private fun buildExecuteActionTool(
        backend: ProxyBackend,
        logger: Logger,
        json: Json,
    ): RegisteredTool =
        RegisteredTool(
            tool =
                Tool(
                    name = EXECUTE_ACTION,
                    title = null,
                    description = "Executes the named tool, prompt, or resource with the provided arguments.",
                    inputSchema = buildExecuteActionSchema(),
                    outputSchema = null,
                    annotations = null,
                ),
            handler = { req ->
                val requestContext = LogRequestContext(logger, LogRequestType.TOOL, EXECUTE_ACTION)
                LogEventBuilder.llmToFacadeRequest(
                    request = requestContext,
                    arguments = req.arguments,
                    meta = req.meta?.json,
                )
                handleExecuteAction(req, backend, json, requestContext)
            },
        )

    private suspend fun handleExecuteAction(
        req: CallToolRequest,
        backend: ProxyBackend,
        json: Json,
        requestContext: LogRequestContext,
    ): CallToolResult {
        val arguments = req.arguments ?: JsonObject(emptyMap())
        val actionType = arguments.stringValue("action_type")
        val name = arguments.stringValue("name")
        val actionArguments = arguments["arguments"]?.asJsonObjectOrNull() ?: JsonObject(emptyMap())
        val result =
            when {
                actionType == null -> errorResult("Missing action_type", requestContext)
                actionType !in actionTypes -> errorResult("Unsupported action_type: $actionType", requestContext)
                name == null -> errorResult("Missing name", requestContext)
                else ->
                    when (actionType) {
                        "tool" -> executeTool(name, actionArguments, backend, json, requestContext)
                        "prompt" -> executePrompt(name, actionArguments, backend, requestContext)
                        "resource" -> executeResource(name, backend, requestContext)
                        "resource_template" ->
                            executeResourceTemplate(
                                template = name,
                                arguments = actionArguments,
                                backend = backend,
                                requestContext = requestContext,
                            )
                        else -> errorResult("Unsupported action_type: $actionType", requestContext)
                    }
            }
        return result
    }

    private suspend fun executeTool(
        name: String,
        arguments: JsonObject,
        backend: ProxyBackend,
        json: Json,
        requestContext: LogRequestContext,
    ): CallToolResult {
        val result = backend.callTool(name, arguments)
        return if (result.isSuccess) {
            val raw = result.getOrNull() ?: JsonNull
            val target = parseToolTarget(name)
            val decoded = decodeActionToolResult(json, raw, requestContext, target)
            val responseJson = Json.encodeToJsonElement(CallToolResult.serializer(), decoded)
            LogEventBuilder.facadeToLlmResponse(
                request = requestContext,
                response = responseJson,
                target = target,
            )
            decoded
        } else {
            val errMsg = result.exceptionOrNull()?.message ?: "Tool error"
            val target = parseToolTarget(name)
            errorResult(errMsg, requestContext, target, result.exceptionOrNull())
        }
    }

    private suspend fun executePrompt(
        name: String,
        arguments: JsonObject,
        backend: ProxyBackend,
        requestContext: LogRequestContext,
    ): CallToolResult {
        val promptArgs = arguments.toPromptArguments()
        val result = backend.getPrompt(name, promptArgs)
        return if (result.isSuccess) {
            val payload = result.getOrNull() ?: JsonObject(emptyMap())
            val callResult = successResult(payload)
            val responseJson = Json.encodeToJsonElement(CallToolResult.serializer(), callResult)
            LogEventBuilder.facadeToLlmResponse(requestContext, responseJson)
            callResult
        } else {
            val errMsg = result.exceptionOrNull()?.message ?: "Prompt error"
            errorResult(errMsg, requestContext, failure = result.exceptionOrNull())
        }
    }

    private suspend fun executeResource(
        name: String,
        backend: ProxyBackend,
        requestContext: LogRequestContext,
    ): CallToolResult {
        val result = backend.readResource(name)
        return if (result.isSuccess) {
            val payload = result.getOrNull() ?: JsonObject(emptyMap())
            val callResult = successResult(payload)
            val responseJson = Json.encodeToJsonElement(CallToolResult.serializer(), callResult)
            LogEventBuilder.facadeToLlmResponse(requestContext, responseJson)
            callResult
        } else {
            val errMsg = result.exceptionOrNull()?.message ?: "Resource error"
            errorResult(errMsg, requestContext, failure = result.exceptionOrNull())
        }
    }

    private suspend fun executeResourceTemplate(
        template: String,
        arguments: JsonObject,
        backend: ProxyBackend,
        requestContext: LogRequestContext,
    ): CallToolResult {
        val resolved =
            resolveTemplateUri(template, arguments)
                .getOrElse { failure ->
                    val errMsg = failure.message ?: "Invalid resource template arguments"
                    return errorResult(errMsg, requestContext, failure = failure)
                }
        return executeResource(resolved, backend, requestContext)
    }

    private fun buildAvailableActionsPayload(capabilities: ProxyServerCapabilities): JsonObject {
        val tools = capabilities.tools.map { tool -> buildToolDescriptor(tool) }
        val prompts = capabilities.prompts.map { prompt -> buildPromptDescriptor(prompt) }
        val resources = mutableListOf<JsonObject>()
        val templates = mutableListOf<JsonObject>()
        capabilities.resources.forEach { resource ->
            val descriptor = buildResourceDescriptor(resource)
            val key = resource.uri ?: resource.name
            if (isResourceTemplate(key)) {
                templates += descriptor
            } else {
                resources += descriptor
            }
        }
        return buildJsonObject {
            put("tools", JsonArray(tools))
            put("prompts", JsonArray(prompts))
            put("resources", JsonArray(resources))
            put("resource_templates", JsonArray(templates))
        }
    }

    private fun buildToolDescriptor(tool: ToolDescriptor): JsonObject {
        val arguments = buildArgumentsFromSchema(tool.inputSchema)
        val description = tool.description ?: tool.title ?: tool.name
        return buildActionDescriptor(tool.name, description, arguments)
    }

    private fun buildPromptDescriptor(prompt: PromptDescriptor): JsonObject {
        val args =
            prompt
                .arguments
                .orEmpty()
                .map { arg ->
                    buildJsonObject {
                        put("name", JsonPrimitive(arg.name))
                        put("description", JsonPrimitive(arg.description ?: arg.name))
                        if (arg.required == true) {
                            put("required", JsonPrimitive(true))
                        }
                    }
                }
        val description = prompt.description ?: prompt.name
        return buildActionDescriptor(prompt.name, description, args)
    }

    private fun buildResourceDescriptor(resource: ResourceDescriptor): JsonObject {
        val key = resource.uri ?: resource.name
        val description = resource.description ?: resource.title ?: resource.name
        val arguments =
            if (isResourceTemplate(key)) {
                buildTemplateArguments(key)
            } else {
                emptyList()
            }
        return buildActionDescriptor(key, description, arguments)
    }

    private fun buildArgumentsFromSchema(schema: ToolSchema?): List<JsonObject> {
        val properties = schema?.properties ?: return emptyList()
        val required = schema.required?.toSet().orEmpty()
        return properties.mapNotNull { (name, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val description =
                (obj["description"] as? JsonPrimitive)?.content
                    ?: (obj["title"] as? JsonPrimitive)?.content
                    ?: name
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("description", JsonPrimitive(description))
                if (name in required) {
                    put("required", JsonPrimitive(true))
                }
            }
        }
    }

    private fun buildTemplateArguments(template: String): List<JsonObject> {
        val placeholders =
            RESOURCE_TEMPLATE_REGEX
                .findAll(template)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        return placeholders.map { name ->
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("description", JsonPrimitive("Template value for $name."))
                put("required", JsonPrimitive(true))
            }
        }
    }

    private fun buildActionDescriptor(
        name: String,
        description: String,
        arguments: List<JsonObject>,
    ): JsonObject =
        buildJsonObject {
            put("name", JsonPrimitive(name))
            put("description", JsonPrimitive(description))
            put("arguments", JsonArray(arguments))
        }

    private fun resolveTemplateUri(
        template: String,
        arguments: JsonObject,
    ): Result<String> {
        val placeholders =
            RESOURCE_TEMPLATE_REGEX
                .findAll(template)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        val missing = placeholders.filter { !arguments.containsKey(it) }
        return when {
            placeholders.isEmpty() -> Result.success(template)
            missing.isNotEmpty() ->
                Result.failure(
                    IllegalArgumentException("Missing template arguments: ${missing.joinToString()}"),
                )
            else -> {
                var resolved = template
                placeholders.forEach { name ->
                    val value = arguments[name]
                    val replacement = value.asStringValue()
                    resolved = resolved.replace("{$name}", replacement)
                }
                Result.success(resolved)
            }
        }
    }

    private fun JsonObject.stringValue(key: String): String? {
        val value = (this[key] as? JsonPrimitive)?.content
        return value?.takeIf { it.isNotBlank() }
    }

    private fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement?.asStringValue(): String =
        when (this) {
            null,
            JsonNull,
            -> ""
            is JsonPrimitive -> if (isString) content else toString()
            else -> toString()
        }

    private fun JsonObject.toPromptArguments(): Map<String, String>? {
        if (isEmpty()) return null
        val args =
            mapNotNull { (key, value) ->
                when (value) {
                    JsonNull -> null
                    is JsonPrimitive -> key to (if (value.isString) value.content else value.toString())
                    else -> key to value.toString()
                }
            }.toMap()
        return if (args.isEmpty()) null else args
    }

    private fun successResult(payload: JsonObject): CallToolResult =
        CallToolResult(
            content = listOf(TextContent(payload.toString())),
            isError = false,
            structuredContent = payload,
            meta = JsonObject(emptyMap()),
        )

    private fun errorResult(
        message: String,
        requestContext: LogRequestContext,
        target: LogTargetContext = LogTargetContext(),
        failure: Throwable? = null,
    ): CallToolResult =
        CallToolResult(
            content = listOf(TextContent(message)),
            isError = true,
            structuredContent = JsonObject(mapOf("error" to JsonPrimitive(message))),
            meta = JsonObject(emptyMap()),
        ).also {
            LogEventBuilder.facadeToLlmError(
                request = requestContext,
                errorMessage = message,
                target = target,
                failure = failure,
            )
        }

    private fun decodeActionToolResult(
        json: Json,
        raw: JsonElement,
        requestContext: LogRequestContext,
        target: LogTargetContext,
    ): CallToolResult =
        runCatching { decodeCallToolResult(json, raw) }
            .onFailure { failure ->
                LogEventBuilder.decodeFailed(
                    request = requestContext,
                    target = target,
                    rawResponse = raw,
                    failure = failure,
                )
            }.getOrElse {
                fallbackCallToolResult(raw)
            }

    private fun parseToolTarget(name: String): LogTargetContext {
        val sepIdx = name.indexOf('_')
        val serverId = if (sepIdx > 0) name.substring(0, sepIdx) else "unknown"
        val downstreamTool = if (sepIdx > 0 && sepIdx < name.length - 1) name.substring(sepIdx + 1) else name
        return LogTargetContext(targetServerId = serverId, downstreamName = downstreamTool)
    }

    private fun buildExecuteActionSchema(): ToolSchema {
        val properties =
            buildJsonObject {
                put(
                    "action_type",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("enum", JsonArray(actionTypes.map { JsonPrimitive(it) }))
                        put(
                            "description",
                            JsonPrimitive(
                                "Type of action to run (tool, prompt, resource, or template).",
                            ),
                        )
                    },
                )
                put(
                    "name",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put(
                            "description",
                            JsonPrimitive(
                                "Name of the tool or prompt, or URI of the resource/template.",
                            ),
                        )
                    },
                )
                put(
                    "arguments",
                    buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("description", JsonPrimitive("Optional arguments passed through to the MCP action."))
                    },
                )
            }
        return ToolSchema(properties = properties, required = listOf("action_type", "name"))
    }

    private fun isResourceTemplate(key: String): Boolean = key.contains('{') && key.contains('}')
}

private val RESOURCE_TEMPLATE_REGEX = "\\{([^}]+)}".toRegex()
