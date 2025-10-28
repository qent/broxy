package io.qent.bro.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.LIB_VERSION
import io.modelcontextprotocol.kotlin.sdk.Prompt
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.IMPLEMENTATION_NAME
import io.qent.bro.core.proxy.ProxyMcpServer
import io.qent.bro.core.utils.ConsoleLogger
import io.qent.bro.core.utils.Logger
import io.qent.bro.core.utils.errorJson
import io.qent.bro.core.utils.infoJson
import io.qent.bro.core.utils.putIfNotNull
import io.qent.bro.core.utils.warnJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

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
                    json.decodeFromJsonElement(CallToolResultBase.serializer(), el)
                }.onFailure { failure ->
                    logger.warnJson("facade_to_llm.decode_failed", failure) {
                        put("toolName", JsonPrimitive(req.name))
                        put("targetServerId", JsonPrimitive(serverId))
                        put("downstreamTool", JsonPrimitive(downstreamTool))
                        put("rawResponse", el)
                    }
                }.getOrElse {
                    CallToolResult(
                        content = emptyList(),
                        structuredContent = (el as? JsonObject) ?: JsonObject(mapOf("raw" to el)),
                        isError = false,
                        _meta = JsonObject(emptyMap())
                    )
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
            val json = kotlinx.coroutines.runBlocking { proxy.getPrompt(req.name) }
            if (json.isSuccess) {
                val el = json.getOrThrow()
                logger.info("Forwarding prompt '${req.name}' result from downstream to LLM")
                kotlinx.serialization.json.Json.decodeFromJsonElement(
                    io.modelcontextprotocol.kotlin.sdk.GetPromptResult.serializer(),
                    el
                )
            } else {
                // Fallback to empty prompt result with error flag in meta
                logger.error("Prompt '${req.name}' failed: ${json.exceptionOrNull()?.message}", json.exceptionOrNull())
                GetPromptResult(
                    description = prompt.description,
                    messages = emptyList(),
                    _meta = JsonObject(mapOf("error" to JsonPrimitive(json.exceptionOrNull()?.message ?: "getPrompt failed")))
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
