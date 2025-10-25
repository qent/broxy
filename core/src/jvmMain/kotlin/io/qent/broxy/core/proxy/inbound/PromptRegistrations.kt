package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredPrompt
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.qent.broxy.core.utils.LogEventBuilder
import io.qent.broxy.core.utils.LogRequestContext
import io.qent.broxy.core.utils.LogRequestType
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import io.qent.broxy.core.mcp.ServerCapabilities as ProxyServerCapabilities

internal fun buildPromptRegistrations(
    capabilities: ProxyServerCapabilities,
    backend: ProxyBackend,
    logger: Logger,
    json: Json,
): List<RegisteredPrompt> =
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
                val argumentsJson = buildPromptArgumentsJson(req.arguments)
                logPromptRequest(req.name, argumentsJson, logger)
                val promptResult = backend.getPrompt(req.name, req.arguments)
                if (promptResult.isSuccess) {
                    handlePromptSuccess(req.name, promptResult.getOrThrow(), logger, json)
                } else {
                    handlePromptFailure(
                        req.name,
                        prompt.description ?: req.name,
                        promptResult.exceptionOrNull(),
                        logger,
                    )
                }
            },
        )
    }

private fun buildPromptArgumentsJson(arguments: Map<String, String>?): JsonObject? =
    arguments?.let { args ->
        buildJsonObject { args.forEach { (key, value) -> put(key, JsonPrimitive(value)) } }
    }

private fun logPromptRequest(
    name: String,
    argumentsJson: JsonObject?,
    logger: Logger,
) {
    LogEventBuilder.llmToFacadeRequest(
        request = LogRequestContext(logger, LogRequestType.PROMPT, name),
        arguments = argumentsJson,
    )
}

private fun handlePromptSuccess(
    name: String,
    raw: JsonObject,
    logger: Logger,
    json: Json,
): GetPromptResult {
    val decoded = decodePromptResult(json, raw)
    val responseJson = Json.encodeToJsonElement(GetPromptResult.serializer(), decoded)
    LogEventBuilder.facadeToLlmResponse(
        request = LogRequestContext(logger, LogRequestType.PROMPT, name),
        response = responseJson,
    )
    return decoded
}

private fun handlePromptFailure(
    name: String,
    description: String,
    failure: Throwable?,
    logger: Logger,
): GetPromptResult {
    val errMsg = failure?.message ?: "getPrompt failed"
    LogEventBuilder.facadeToLlmError(
        request = LogRequestContext(logger, LogRequestType.PROMPT, name),
        errorMessage = errMsg,
        failure = failure,
    )
    return GetPromptResult(
        messages = emptyList(),
        description = description,
        meta =
            JsonObject(
                mapOf(
                    "error" to JsonPrimitive(errMsg),
                ),
            ),
    )
}
