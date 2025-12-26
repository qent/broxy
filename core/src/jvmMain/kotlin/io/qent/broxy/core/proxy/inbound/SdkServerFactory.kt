package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.LIB_VERSION
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredPrompt
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredResource
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.IMPLEMENTATION_NAME
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentTypes
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.core.utils.errorJson
import io.qent.broxy.core.utils.infoJson
import io.qent.broxy.core.utils.putIfNotNull
import io.qent.broxy.core.utils.warnJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.qent.broxy.core.mcp.ServerCapabilities as ProxyServerCapabilities

/**
 * Builds an MCP Server (SDK) instance backed by our ProxyMcpServer for filtering and routing.
 */
fun buildSdkServer(
    proxy: ProxyMcpServer,
    logger: Logger = ConsoleLogger,
): Server {
    val options =
        ServerOptions(
            capabilities =
                ServerCapabilities(
                    // Explicit booleans keep initialize responses schema-valid
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                    resources = ServerCapabilities.Resources(listChanged = false, subscribe = false),
                    tools = ServerCapabilities.Tools(listChanged = false),
                    logging = ServerCapabilities.Logging,
                ),
        )

    val server =
        Server(
            serverInfo = Implementation(name = IMPLEMENTATION_NAME, version = LIB_VERSION),
            options = options,
        )

    // Initial sync from current filtered capabilities. Later preset updates can re-sync without server restart.
    syncSdkServer(server, proxy, logger)

    return server
}

internal data class ProxyBackend(
    val callTool: suspend (toolName: String, arguments: JsonObject) -> Result<JsonElement>,
    val getPrompt: suspend (name: String, arguments: Map<String, String>?) -> Result<JsonObject>,
    val readResource: suspend (uri: String) -> Result<JsonObject>,
)

fun syncSdkServer(
    server: Server,
    proxy: ProxyMcpServer,
    logger: Logger = ConsoleLogger,
) {
    val backend =
        ProxyBackend(
            callTool = { toolName, arguments -> proxy.callTool(toolName, arguments) },
            getPrompt = { name, arguments -> proxy.getPrompt(name, arguments) },
            readResource = { uri -> proxy.readResource(uri) },
        )
    syncSdkServer(
        server = server,
        capabilities = proxy.getCapabilities(),
        backend = backend,
        logger = logger,
        fallbackPromptsAndResourcesToTools = proxy.shouldFallbackPromptsAndResourcesToTools(),
    )
}

internal fun syncSdkServer(
    server: Server,
    capabilities: ProxyServerCapabilities,
    backend: ProxyBackend,
    logger: Logger = ConsoleLogger,
    fallbackPromptsAndResourcesToTools: Boolean = false,
) {
    val json = Json { ignoreUnknownKeys = true }

    // Replace existing registered snapshot with the current filtered view.
    server.removeTools(server.tools.keys.toList())
    server.removePrompts(server.prompts.keys.toList())
    server.removeResources(server.resources.keys.toList())

    val toolsToAdd =
        capabilities.tools.map { td ->
            RegisteredTool(
                tool =
                    Tool(
                        name = td.name,
                        title = td.title,
                        description = td.description ?: td.title ?: td.name,
                        inputSchema = td.inputSchema ?: ToolSchema(),
                        outputSchema = td.outputSchema,
                        annotations = td.annotations,
                    ),
                handler = { req: CallToolRequest ->
                    logger.infoJson("llm_to_facade.request") {
                        put("toolName", JsonPrimitive(req.name))
                        put("arguments", req.arguments ?: JsonNull)
                        putIfNotNull("meta", req.meta?.json)
                    }
                    val arguments = req.arguments ?: JsonObject(emptyMap())
                    val result = backend.callTool(req.name, arguments)
                    val colonIdx = req.name.indexOf(':')
                    val serverId = if (colonIdx > 0) req.name.substring(0, colonIdx) else "unknown"
                    val downstreamTool =
                        if (colonIdx > 0 && colonIdx < req.name.length - 1) req.name.substring(colonIdx + 1) else req.name
                    if (result.isSuccess) {
                        val el = result.getOrNull() ?: JsonNull
                        val decodedBase =
                            runCatching {
                                decodeCallToolResult(json, el)
                            }.onFailure { failure ->
                                logger.warnJson("facade_to_llm.decode_failed", failure) {
                                    put("toolName", JsonPrimitive(req.name))
                                    put("targetServerId", JsonPrimitive(serverId))
                                    put("downstreamTool", JsonPrimitive(downstreamTool))
                                    put("rawResponse", el)
                                }
                            }.getOrElse {
                                fallbackCallToolResult(el)
                            }
                        val decoded = decodedBase
                        logger.infoJson("facade_to_llm.response") {
                            put("toolName", JsonPrimitive(req.name))
                            put("targetServerId", JsonPrimitive(serverId))
                            put("downstreamTool", JsonPrimitive(downstreamTool))
                            put("response", Json.encodeToJsonElement(CallToolResult.serializer(), decoded))
                        }
                        decoded
                    } else {
                        val errMsg = result.exceptionOrNull()?.message ?: "Tool error"
                        logger.errorJson("facade_to_llm.error", result.exceptionOrNull()) {
                            put("toolName", JsonPrimitive(req.name))
                            put("targetServerId", JsonPrimitive(serverId))
                            put("downstreamTool", JsonPrimitive(downstreamTool))
                            put("errorMessage", JsonPrimitive(errMsg))
                        }
                        CallToolResult(
                            content = emptyList(),
                            isError = true,
                            structuredContent = JsonObject(mapOf("error" to JsonPrimitive(errMsg))),
                            meta = JsonObject(emptyMap()),
                        )
                    }
                },
            )
        }

    val promptFallbackTools =
        if (fallbackPromptsAndResourcesToTools) {
            capabilities.prompts.mapNotNull { prompt ->
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
        } else {
            emptyList()
        }

    val resourceFallbackTools =
        if (fallbackPromptsAndResourcesToTools) {
            capabilities.resources.mapNotNull { resource ->
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
        } else {
            emptyList()
        }

    val allTools = (toolsToAdd + promptFallbackTools + resourceFallbackTools).distinctBy { it.tool.name }
    if (allTools.isNotEmpty()) {
        server.addTools(allTools)
    }

    val promptsToAdd =
        capabilities.prompts.map { pd ->
            val prompt =
                Prompt(
                    name = pd.name,
                    description = pd.description ?: pd.name,
                    arguments = pd.arguments ?: emptyList(),
                )
            RegisteredPrompt(
                prompt = prompt,
                messageProvider = { req ->
                    logger.info("Received prompt request from LLM: name='${req.name}'")
                    val promptResult = backend.getPrompt(req.name, req.arguments)
                    if (promptResult.isSuccess) {
                        val el = promptResult.getOrThrow()
                        logger.info("Forwarding prompt '${req.name}' result from downstream to LLM")
                        decodePromptResult(json, el)
                    } else {
                        logger.error(
                            "Prompt '${req.name}' failed: ${promptResult.exceptionOrNull()?.message}",
                            promptResult.exceptionOrNull(),
                        )
                        GetPromptResult(
                            messages = emptyList(),
                            description = prompt.description,
                            meta =
                                JsonObject(
                                    mapOf(
                                        "error" to
                                            JsonPrimitive(
                                                promptResult.exceptionOrNull()?.message ?: "getPrompt failed",
                                            ),
                                    ),
                                ),
                        )
                    }
                },
            )
        }
    if (promptsToAdd.isNotEmpty()) {
        server.addPrompts(promptsToAdd)
    }

    val resourcesToAdd =
        capabilities.resources.map { rd ->
            val uri = rd.uri ?: rd.name
            val desc = rd.description ?: ""
            RegisteredResource(
                resource =
                    Resource(
                        uri = uri,
                        name = rd.name,
                        description = desc,
                        mimeType = rd.mimeType ?: "text/html",
                    ),
                readHandler = { _ ->
                    logger.info("Received resource request from LLM: uri='$uri'")
                    val readResult = backend.readResource(uri)
                    if (readResult.isSuccess) {
                        val el = readResult.getOrThrow()
                        logger.info("Forwarding resource '$uri' result from downstream to LLM")
                        Json.decodeFromJsonElement(ReadResourceResult.serializer(), el)
                    } else {
                        logger.error(
                            "Resource '$uri' failed: ${readResult.exceptionOrNull()?.message}",
                            readResult.exceptionOrNull(),
                        )
                        ReadResourceResult(
                            contents = emptyList(),
                            meta =
                                JsonObject(
                                    mapOf(
                                        "error" to
                                            JsonPrimitive(
                                                readResult.exceptionOrNull()?.message ?: "readResource failed",
                                            ),
                                    ),
                                ),
                        )
                    }
                },
            )
        }
    if (resourcesToAdd.isNotEmpty()) {
        server.addResources(resourcesToAdd)
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
            logger.infoJson("llm_to_facade.request") {
                put("toolName", JsonPrimitive(req.name))
                put("arguments", req.arguments ?: JsonNull)
                putIfNotNull("meta", req.meta?.json)
            }
            val arguments = req.arguments ?: JsonObject(emptyMap())
            val result = invokeBackend(arguments)
            if (result.isSuccess) {
                val payload = result.getOrNull() ?: JsonObject(emptyMap())
                val response =
                    CallToolResult(
                        content = listOf(TextContent(payload.toString())),
                        isError = false,
                        structuredContent = payload,
                        meta = JsonObject(emptyMap()),
                    )
                logger.infoJson("facade_to_llm.response") {
                    put("toolName", JsonPrimitive(req.name))
                    put("response", Json.encodeToJsonElement(CallToolResult.serializer(), response))
                }
                response
            } else {
                val errMsg = result.exceptionOrNull()?.message ?: "Tool error"
                logger.errorJson("facade_to_llm.error", result.exceptionOrNull()) {
                    put("toolName", JsonPrimitive(req.name))
                    put("errorMessage", JsonPrimitive(errMsg))
                }
                CallToolResult(
                    content = listOf(TextContent(errMsg)),
                    isError = true,
                    structuredContent = JsonObject(mapOf("error" to JsonPrimitive(errMsg))),
                    meta = JsonObject(emptyMap()),
                )
            }
        },
    )

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

private fun buildPromptFallbackToolName(promptName: String): String = "${PROMPT_FALLBACK_PREFIX}:$promptName"

private fun buildResourceFallbackToolName(resourceKey: String): String = "${RESOURCE_FALLBACK_PREFIX}:$resourceKey"

private const val PROMPT_FALLBACK_PREFIX = "prompt"
private const val RESOURCE_FALLBACK_PREFIX = "resource"
private val RESOURCE_PLACEHOLDER_REGEX = "\\{([^}]+)}".toRegex()

internal fun decodeCallToolResult(
    json: Json,
    element: JsonElement,
): CallToolResult {
    return try {
        json.decodeFromJsonElement(CallToolResult.serializer(), element)
    } catch (original: Exception) {
        val normalized = (element as? JsonObject)?.let { normalizeCallToolResult(it) }
        if (normalized != null) {
            json.decodeFromJsonElement(CallToolResult.serializer(), normalized)
        } else {
            throw original
        }
    }
}

private fun normalizeCallToolResult(original: JsonObject): JsonObject? {
    val content = original["content"] as? JsonArray ?: return null
    var changed = false
    val normalizedContent =
        JsonArray(
            content.map { item ->
                val normalized = normalizeContentElement(item)
                if (normalized != null) {
                    changed = true
                    normalized
                } else {
                    item
                }
            },
        )
    return if (changed) JsonObject(original + ("content" to normalizedContent)) else null
}

internal fun decodePromptResult(
    json: Json,
    element: JsonElement,
): GetPromptResult {
    return try {
        json.decodeFromJsonElement(GetPromptResult.serializer(), element)
    } catch (original: Exception) {
        val normalized = (element as? JsonObject)?.let { normalizePromptResult(it) }
        if (normalized != null) {
            json.decodeFromJsonElement(GetPromptResult.serializer(), normalized)
        } else {
            throw original
        }
    }
}

private fun normalizePromptResult(original: JsonObject): JsonObject? {
    val messages = original["messages"] as? JsonArray ?: return null
    var changed = false
    val normalizedMessages =
        JsonArray(
            messages.map { messageElement ->
                val messageObject = messageElement as? JsonObject ?: return@map messageElement
                val currentContent = messageObject["content"]
                val normalizedContent = currentContent?.let { normalizeContentElement(it) }
                if (normalizedContent != null) {
                    changed = true
                    JsonObject(messageObject + ("content" to normalizedContent))
                } else {
                    messageElement
                }
            },
        )
    return if (changed) JsonObject(original + ("messages" to normalizedMessages)) else null
}

private fun normalizeContentElement(element: JsonElement): JsonElement? =
    when (element) {
        is JsonObject -> addTypeIfMissing(element)
        is JsonArray -> {
            var changed = false
            val normalizedItems =
                element.map { entry ->
                    val obj = entry as? JsonObject ?: return@map entry
                    val normalized = addTypeIfMissing(obj)
                    if (normalized != null) {
                        changed = true
                        normalized
                    } else {
                        obj
                    }
                }
            if (changed) JsonArray(normalizedItems) else null
        }

        else -> null
    }

private fun addTypeIfMissing(obj: JsonObject): JsonObject? {
    if ("type" in obj) return null
    val inferredType = inferContentType(obj) ?: return null
    return JsonObject(obj + ("type" to JsonPrimitive(inferredType.value)))
}

private fun inferContentType(obj: JsonObject): ContentTypes? =
    when {
        "type" in obj ->
            obj["type"]?.jsonPrimitive?.content?.let { typeValue ->
                ContentTypes.entries.firstOrNull { it.value == typeValue }
            }

        "text" in obj -> ContentTypes.TEXT
        "image" in obj -> ContentTypes.IMAGE
        "data" in obj && obj["mimeType"]?.jsonPrimitive?.content?.startsWith("image/") == true -> ContentTypes.IMAGE
        "audio" in obj -> ContentTypes.AUDIO
        "data" in obj && obj["mimeType"]?.jsonPrimitive?.content?.startsWith("audio/") == true -> ContentTypes.AUDIO
        "resource" in obj -> ContentTypes.EMBEDDED_RESOURCE
        else -> null
    }

internal fun fallbackCallToolResult(raw: JsonElement): CallToolResult {
    val rawObject = raw as? JsonObject
    val structured =
        when (val sc = rawObject?.get("structuredContent")) {
            is JsonObject -> sc
            else -> rawObject ?: JsonObject(mapOf("raw" to raw))
        }
    val meta =
        (rawObject?.get("_meta") as? JsonObject)
            ?: (rawObject?.get("meta") as? JsonObject)
            ?: JsonObject(emptyMap())
    val isError = rawObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false
    val contentArray = rawObject?.get("content") as? JsonArray
    val fallbackContent =
        contentArray
            ?.mapNotNull { it.toTextContentOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(TextContent(text = raw.toString()))
    return CallToolResult(
        content = fallbackContent,
        structuredContent = structured,
        isError = isError,
        meta = meta,
    )
}

private fun JsonElement.toTextContentOrNull(): TextContent? =
    when (this) {
        is JsonPrimitive -> TextContent(text = if (isString) content else toString())
        is JsonObject -> {
            val textNode = this["text"]
            val textValue =
                when (textNode) {
                    is JsonPrimitive -> if (textNode.isString) textNode.content else textNode.toString()
                    null -> toString()
                    else -> textNode.toString()
                }
            TextContent(text = textValue)
        }

        else -> null
    }
