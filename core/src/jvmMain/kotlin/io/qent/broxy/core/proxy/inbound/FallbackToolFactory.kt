package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.utils.LogEventBuilder
import io.qent.broxy.core.utils.LogRequestContext
import io.qent.broxy.core.utils.LogRequestType
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal object FallbackToolFactory {
    fun buildPromptFallbackTools(
        prompts: List<PromptDescriptor>,
        backend: ProxyBackend,
        logger: Logger,
    ): List<RegisteredTool> =
        prompts.mapNotNull { prompt ->
            val toolName = buildPromptFallbackToolName(prompt.name)
            val inputSchema = buildPromptFallbackSchema(prompt.arguments)
            buildFallbackTool(
                name = toolName,
                description = prompt.description ?: prompt.name,
                inputSchema = inputSchema,
                logger = logger,
                invokeBackend = { args ->
                    val promptArgs = args.toPromptArguments()
                    backend.getPrompt(prompt.name, promptArgs)
                },
            )
        }

    fun buildResourceFallbackTools(
        resources: List<ResourceDescriptor>,
        backend: ProxyBackend,
        logger: Logger,
    ): List<RegisteredTool> =
        resources.mapNotNull { resource ->
            val key = resource.uri ?: resource.name
            val toolName = buildResourceFallbackToolName(key)
            val inputSchema = buildResourceFallbackSchema(resource.uri)
            buildFallbackTool(
                name = toolName,
                description = resource.description ?: resource.title ?: resource.name,
                inputSchema = inputSchema,
                logger = logger,
                invokeBackend = { _ -> backend.readResource(key) },
            )
        }
}

private fun buildFallbackTool(
    name: String,
    description: String,
    inputSchema: ToolSchema,
    logger: Logger,
    invokeBackend: suspend (JsonObject) -> Result<JsonObject>,
): RegisteredTool =
    RegisteredTool(
        tool =
            Tool(
                name = name,
                title = null,
                description = description,
                inputSchema = inputSchema,
                outputSchema = null,
                annotations = null,
            ),
        handler = { req: CallToolRequest ->
            logFallbackToolRequest(logger, req)
            val arguments = req.arguments ?: JsonObject(emptyMap())
            val result = invokeBackend(arguments)
            buildFallbackToolResult(logger, req, result)
        },
    )

private fun logFallbackToolRequest(
    logger: Logger,
    req: CallToolRequest,
) {
    LogEventBuilder.llmToFacadeRequest(
        request = LogRequestContext(logger, LogRequestType.TOOL, req.name),
        arguments = req.arguments,
        meta = req.meta?.json,
    )
}

private fun buildFallbackToolResult(
    logger: Logger,
    req: CallToolRequest,
    result: Result<JsonObject>,
): CallToolResult =
    if (result.isSuccess) {
        val payload = result.getOrNull() ?: JsonObject(emptyMap())
        val response =
            CallToolResult(
                content = listOf(TextContent(payload.toString())),
                isError = false,
                structuredContent = payload,
                meta = JsonObject(emptyMap()),
            )
        val responseJson = Json.encodeToJsonElement(CallToolResult.serializer(), response)
        LogEventBuilder.facadeToLlmResponse(
            request = LogRequestContext(logger, LogRequestType.TOOL, req.name),
            response = responseJson,
        )
        response
    } else {
        val errMsg = result.exceptionOrNull()?.message ?: "Tool error"
        LogEventBuilder.facadeToLlmError(
            request = LogRequestContext(logger, LogRequestType.TOOL, req.name),
            errorMessage = errMsg,
            failure = result.exceptionOrNull(),
        )
        CallToolResult(
            content = listOf(TextContent(errMsg)),
            isError = true,
            structuredContent = JsonObject(mapOf("error" to JsonPrimitive(errMsg))),
            meta = JsonObject(emptyMap()),
        )
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

private fun buildPromptFallbackSchema(arguments: List<PromptArgument>?): ToolSchema {
    if (arguments.isNullOrEmpty()) return ToolSchema()
    val required = arguments.filter { it.required == true }.map { it.name }.takeIf { it.isNotEmpty() }
    val properties =
        buildJsonObject {
            arguments.forEach { arg ->
                put(
                    arg.name,
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        arg.description?.let { put("description", JsonPrimitive(it)) }
                        arg.title?.let { put("title", JsonPrimitive(it)) }
                    },
                )
            }
        }
    return ToolSchema(properties = properties, required = required)
}

private fun buildResourceFallbackSchema(uri: String?): ToolSchema {
    val placeholders =
        uri
            ?.let { RESOURCE_PLACEHOLDER_REGEX.findAll(it).map { match -> match.groupValues[1] }.toList() }
            .orEmpty()
            .distinct()
    if (placeholders.isEmpty()) return ToolSchema()
    val properties =
        buildJsonObject {
            placeholders.forEach { name ->
                put(
                    name,
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                    },
                )
            }
        }
    return ToolSchema(properties = properties, required = placeholders)
}

private fun buildPromptFallbackToolName(promptName: String): String = "${PROMPT_FALLBACK_PREFIX}_$promptName"

private fun buildResourceFallbackToolName(resourceKey: String): String = "${RESOURCE_FALLBACK_PREFIX}_$resourceKey"

private const val PROMPT_FALLBACK_PREFIX = "prompt"
private const val RESOURCE_FALLBACK_PREFIX = "resource"
private val RESOURCE_PLACEHOLDER_REGEX = "\\{([^}]+)}".toRegex()
