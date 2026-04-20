@file:Suppress("TooManyFunctions")

package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.error
import io.qent.broxy.core.presetmanagement.CreatePresetRequest
import io.qent.broxy.core.presetmanagement.GetCatalogServerInstallStatusRequest
import io.qent.broxy.core.presetmanagement.InstallCatalogServerRequest
import io.qent.broxy.core.presetmanagement.PresetDescriptionRequest
import io.qent.broxy.core.presetmanagement.PresetManagementAmbiguityException
import io.qent.broxy.core.presetmanagement.PresetManagementBackend
import io.qent.broxy.core.presetmanagement.PresetManagementException
import io.qent.broxy.core.presetmanagement.ServerDescriptionRequest
import io.qent.broxy.core.presetmanagement.SetServerEnabledRequest
import io.qent.broxy.core.utils.LogEventBuilder
import io.qent.broxy.core.utils.LogRequestContext
import io.qent.broxy.core.utils.LogRequestType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private const val PRESET_MANAGEMENT_BACKEND_UNAVAILABLE = "Preset management backend is unavailable."
private const val PRESET_MANAGEMENT_ERROR = "Preset management error"
private const val INVALID_ARGUMENTS_ERROR = "Invalid tool arguments"

internal suspend fun executePresetManagementToolCall(
    context: PresetToolFactoryContext,
    req: CallToolRequest,
    toolName: String,
    call: suspend (PresetManagementBackend) -> CallToolResult,
): CallToolResult {
    val requestContext = LogRequestContext(context.logger, LogRequestType.TOOL, toolName)
    LogEventBuilder.llmToFacadeRequest(
        request = requestContext,
        arguments = req.arguments,
        meta = req.meta?.json,
    )

    val backend = context.backendProvider()
    val result =
        if (backend == null) {
            errorResult(
                message = PRESET_MANAGEMENT_BACKEND_UNAVAILABLE,
                requestContext = requestContext,
            )
        } else {
            runCatching { call(backend) }
                .getOrElse { error ->
                    errorResult(
                        message = error.message ?: PRESET_MANAGEMENT_ERROR,
                        requestContext = requestContext,
                        error = error,
                    )
                }
        }

    LogEventBuilder.facadeToLlmResponse(
        request = requestContext,
        response = context.json.encodeToJsonElement(CallToolResult.serializer(), result),
    )
    return result
}

internal fun decodeServerDescriptionRequest(
    json: Json,
    arguments: JsonObject,
): ServerDescriptionRequest =
    decodeRequest(json, arguments, ServerDescriptionRequest.serializer()) { request ->
        request.copy(
            serverName = request.serverName.trim(),
            serverId = request.serverId?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

internal fun decodePresetDescriptionRequest(
    json: Json,
    arguments: JsonObject,
): PresetDescriptionRequest =
    decodeRequest(json, arguments, PresetDescriptionRequest.serializer()) { request ->
        request.copy(
            presetName = request.presetName.trim(),
            presetId = request.presetId?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

internal fun decodeCreatePresetRequest(
    json: Json,
    arguments: JsonObject,
): CreatePresetRequest =
    decodeRequest(json, arguments, CreatePresetRequest.serializer()) { request ->
        request.copy(
            presetId = request.presetId.trim(),
            presetName = request.presetName.trim(),
            tools =
                request.tools.map { tool ->
                    tool.copy(
                        serverId = tool.serverId.trim(),
                        toolName = tool.toolName.trim(),
                    )
                },
        )
    }

internal fun decodeInstallCatalogServerRequest(
    json: Json,
    arguments: JsonObject,
): InstallCatalogServerRequest =
    decodeRequest(json, arguments, InstallCatalogServerRequest.serializer()) { request ->
        request.copy(serverId = request.serverId.trim())
    }

internal fun decodeGetCatalogServerInstallStatusRequest(
    json: Json,
    arguments: JsonObject,
): GetCatalogServerInstallStatusRequest =
    decodeRequest(json, arguments, GetCatalogServerInstallStatusRequest.serializer()) { request ->
        request.copy(serverId = request.serverId.trim())
    }

internal fun decodeSetServerEnabledRequest(
    json: Json,
    arguments: JsonObject,
): SetServerEnabledRequest =
    decodeRequest(json, arguments, SetServerEnabledRequest.serializer()) { request ->
        request.copy(serverId = request.serverId.trim())
    }

internal fun successResult(
    text: String,
    structured: JsonObject,
): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(text = text)),
        isError = false,
        structuredContent = structured,
        meta = EmptyJsonObject,
    )

internal fun errorResult(
    message: String,
    requestContext: LogRequestContext,
    error: Throwable? = null,
): CallToolResult {
    val resolvedMessage =
        if (error is PresetManagementAmbiguityException) {
            ambiguityErrorMessage(message, error)
        } else {
            message
        }
    LogEventBuilder.facadeToLlmError(
        request = requestContext,
        errorMessage = "$FIELD_ERROR: $resolvedMessage",
        failure = error,
    )
    return CallToolResult.error(content = resolvedMessage, meta = EmptyJsonObject)
}

internal fun <T> Json.encodeStructuredPayload(
    serializer: KSerializer<T>,
    payload: T,
): JsonObject = encodeToJsonElement(serializer, payload).jsonObject

private fun <T> decodeRequest(
    json: Json,
    arguments: JsonObject,
    serializer: KSerializer<T>,
    normalize: (T) -> T,
): T =
    runCatching { json.decodeFromJsonElement(serializer, arguments) }
        .map(normalize)
        .getOrElse { error ->
            throw PresetManagementException(error.message ?: INVALID_ARGUMENTS_ERROR)
        }

private fun ambiguityErrorMessage(
    message: String,
    error: PresetManagementAmbiguityException,
): String {
    val candidates =
        error.candidates
            .joinToString(separator = ", ") { candidate -> "${candidate.id}:${candidate.name}" }
            .ifBlank { "none" }
    return "$message ($FIELD_CANDIDATES: $candidates)"
}
