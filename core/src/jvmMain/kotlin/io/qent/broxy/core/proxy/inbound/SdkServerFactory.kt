package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.AudioContent
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.ImageContent
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.LIB_VERSION
import io.modelcontextprotocol.kotlin.sdk.Prompt
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.IMPLEMENTATION_NAME
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
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds an MCP Server (SDK) instance backed by our ProxyMcpServer for filtering and routing.
 */
fun buildSdkServer(proxy: ProxyMcpServer, logger: Logger = ConsoleLogger): Server {
    val options = ServerOptions(
        capabilities = ServerCapabilities(
            // Enable all high-level capabilities; server will list registered items
            prompts = ServerCapabilities.Prompts(listChanged = null),
            resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
            tools = ServerCapabilities.Tools(listChanged = null)
        )
    )

    val server = Server(
        serverInfo = Implementation(name = IMPLEMENTATION_NAME, version = LIB_VERSION),
        options = options
    )

    // Snapshot current filtered capabilities and register with SDK server.
    val caps = proxy.getCapabilities()
    val json = Json { ignoreUnknownKeys = true }

    // Register tools
    caps.tools.forEach { td ->
        server.addTool(
            name = td.name,
            title = td.title,
            description = td.description ?: td.title ?: td.name,
            inputSchema = td.inputSchema ?: Tool.Input(),
            outputSchema = td.outputSchema,
            toolAnnotations = td.annotations
        ) { req: CallToolRequest ->
            logger.infoJson("llm_to_facade.request") {
                put("toolName", JsonPrimitive(req.name))
                put("arguments", req.arguments)
                putIfNotNull("meta", req._meta)
            }
            val result = kotlinx.coroutines.runBlocking { proxy.callTool(req.name, req.arguments) }
            val colonIdx = req.name.indexOf(':')
            val serverId = if (colonIdx > 0) req.name.substring(0, colonIdx) else "unknown"
            val downstreamTool = if (colonIdx > 0 && colonIdx < req.name.length - 1) req.name.substring(colonIdx + 1) else req.name
            if (result.isSuccess) {
                val el = result.getOrNull() ?: JsonNull
                val decodedBase = runCatching {
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
                val decoded = if (decodedBase is CallToolResult) decodedBase else CallToolResult(
                    content = decodedBase.content,
                    structuredContent = decodedBase.structuredContent,
                    isError = decodedBase.isError,
                    _meta = decodedBase._meta
                )
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
                    structuredContent = JsonObject(mapOf("error" to JsonPrimitive(errMsg))),
                    isError = true,
                    _meta = JsonObject(emptyMap())
                )
            }
        }
    }

    // Register prompts (list + downstream getPrompt handler)
    caps.prompts.forEach { pd ->
        val prompt = Prompt(
            name = pd.name,
            description = pd.description ?: pd.name,
            arguments = pd.arguments ?: emptyList()
        )
        server.addPrompt(prompt) { req ->
            logger.info("Received prompt request from LLM: name='${req.name}'")
            val promptResult = kotlinx.coroutines.runBlocking { proxy.getPrompt(req.name, req.arguments) }
            if (promptResult.isSuccess) {
                val el = promptResult.getOrThrow()
                logger.info("Forwarding prompt '${req.name}' result from downstream to LLM")
                decodePromptResult(json, el)
            } else {
                // Fallback to empty prompt result with error flag in meta
                logger.error("Prompt '${req.name}' failed: ${promptResult.exceptionOrNull()?.message}", promptResult.exceptionOrNull())
                GetPromptResult(
                    description = prompt.description,
                    messages = emptyList(),
                    _meta = JsonObject(mapOf("error" to JsonPrimitive(promptResult.exceptionOrNull()?.message ?: "getPrompt failed")))
                )
            }
        }
    }

    // Register resources (list + downstream read handler)
    caps.resources.forEach { rd ->
        val uri = rd.uri ?: rd.name
        val desc = rd.description ?: ""
        server.addResource(
            uri = uri,
            name = rd.name,
            description = desc,
            mimeType = rd.mimeType ?: "text/html"
        ) { _ ->
            logger.info("Received resource request from LLM: uri='$uri'")
            val json = kotlinx.coroutines.runBlocking { proxy.readResource(uri) }
            if (json.isSuccess) {
                val el = json.getOrThrow()
                logger.info("Forwarding resource '$uri' result from downstream to LLM")
                kotlinx.serialization.json.Json.decodeFromJsonElement(
                    io.modelcontextprotocol.kotlin.sdk.ReadResourceResult.serializer(),
                    el
                )
            } else {
                logger.error("Resource '$uri' failed: ${json.exceptionOrNull()?.message}", json.exceptionOrNull())
                io.modelcontextprotocol.kotlin.sdk.ReadResourceResult(
                    contents = emptyList(),
                    _meta = JsonObject(mapOf("error" to JsonPrimitive(json.exceptionOrNull()?.message ?: "readResource failed")))
                )
            }
        }
    }

    return server
}

internal fun decodeCallToolResult(json: Json, element: JsonElement): CallToolResultBase {
    return try {
        json.decodeFromJsonElement(CallToolResultBase.serializer(), element)
    } catch (original: Exception) {
        val normalized = (element as? JsonObject)?.let { normalizeCallToolResult(it) }
        if (normalized != null) {
            json.decodeFromJsonElement(CallToolResultBase.serializer(), normalized)
        } else {
            throw original
        }
    }
}

private fun normalizeCallToolResult(original: JsonObject): JsonObject? {
    val content = original["content"] as? JsonArray ?: return null
    var changed = false
    val normalizedContent = JsonArray(content.map { item ->
        val normalized = normalizeContentElement(item)
        if (normalized != null) {
            changed = true
            normalized
        } else item
    })
    return if (changed) JsonObject(original + ("content" to normalizedContent)) else null
}

internal fun decodePromptResult(json: Json, element: JsonElement): GetPromptResult {
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
    val normalizedMessages = JsonArray(messages.map { messageElement ->
        val messageObject = messageElement as? JsonObject ?: return@map messageElement
        val currentContent = messageObject["content"]
        val normalizedContent = currentContent?.let { normalizeContentElement(it) }
        if (normalizedContent != null) {
            changed = true
            JsonObject(messageObject + ("content" to normalizedContent))
        } else {
            messageElement
        }
    })
    return if (changed) JsonObject(original + ("messages" to normalizedMessages)) else null
}

private fun normalizeContentElement(element: JsonElement): JsonElement? = when (element) {
    is JsonObject -> addTypeIfMissing(element)
    is JsonArray -> {
        var changed = false
        val normalizedItems = element.map { entry ->
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
    return JsonObject(obj + ("type" to JsonPrimitive(inferredType)))
}

private fun inferContentType(obj: JsonObject): String? = when {
    "type" in obj -> obj["type"]?.jsonPrimitive?.content
    "text" in obj -> TextContent.TYPE
    "image" in obj -> ImageContent.TYPE
    "data" in obj && obj["mimeType"]?.jsonPrimitive?.content?.startsWith("image/") == true -> ImageContent.TYPE
    "audio" in obj -> AudioContent.TYPE
    "data" in obj && obj["mimeType"]?.jsonPrimitive?.content?.startsWith("audio/") == true -> AudioContent.TYPE
    "resource" in obj -> EmbeddedResource.TYPE
    else -> null
}

internal fun fallbackCallToolResult(raw: JsonElement): CallToolResult {
    val rawObject = raw as? JsonObject
    val structured = when (val sc = rawObject?.get("structuredContent")) {
        is JsonObject -> sc
        else -> rawObject ?: JsonObject(mapOf("raw" to raw))
    }
    val meta = (rawObject?.get("_meta") as? JsonObject) ?: JsonObject(emptyMap())
    val isError = rawObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false
    val contentArray = rawObject?.get("content") as? JsonArray
    val fallbackContent = contentArray
        ?.mapNotNull { it.toTextContentOrNull() }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(TextContent(text = raw.toString()))
    return CallToolResult(
        content = fallbackContent,
        structuredContent = structured,
        isError = isError,
        _meta = meta
    )
}

private fun JsonElement.toTextContentOrNull(): TextContent? = when (this) {
    is JsonPrimitive -> TextContent(text = if (isString) content else toString())
    is JsonObject -> {
        val textNode = this["text"]
        val textValue = when (textNode) {
            is JsonPrimitive -> if (textNode.isString) textNode.content else textNode.toString()
            null -> toString()
            else -> textNode.toString()
        }
        TextContent(text = textValue)
    }
    else -> null
}
