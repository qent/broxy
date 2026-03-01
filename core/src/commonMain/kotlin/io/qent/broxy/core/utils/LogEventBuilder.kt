package io.qent.broxy.core.utils

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal object LogEventBuilder {
    fun llmToFacadeRequest(
        request: LogRequestContext,
        arguments: JsonElement?,
        meta: JsonElement? = null,
    ) {
        request.logger.infoJson(eventName(request.type, "llm_to_facade", "request")) {
            putRequestIdentity(request.type, request.name)
            putIfNotNull("arguments", LogRedactor.redact(arguments))
            putIfNotNull("meta", LogRedactor.redact(meta))
        }
    }

    fun facadeToDownstreamRequest(
        request: LogRequestContext,
        downstream: DownstreamContext,
        arguments: JsonElement? = null,
    ) {
        request.logger.infoJson(eventName(request.type, "facade_to_downstream", "request")) {
            putRequestIdentity(request.type, request.name)
            put("resolvedServerId", JsonPrimitive(downstream.resolvedServerId))
            putDownstreamName(request.type, downstream.downstreamName)
            putIfNotNull("arguments", LogRedactor.redact(arguments))
        }
    }

    fun downstreamResponse(
        request: LogRequestContext,
        downstream: DownstreamContext,
        response: JsonElement?,
    ) {
        request.logger.infoJson(eventName(request.type, "downstream", "response")) {
            putRequestIdentity(request.type, request.name)
            put("resolvedServerId", JsonPrimitive(downstream.resolvedServerId))
            putDownstreamName(request.type, downstream.downstreamName)
            put("response", redactOrNull(response))
        }
    }

    fun downstreamError(
        request: LogRequestContext,
        downstream: DownstreamContext,
        errorMessage: String,
        failure: Throwable? = null,
    ) {
        request.logger.errorJson(eventName(request.type, "downstream", "response.error"), failure) {
            putRequestIdentity(request.type, request.name)
            put("resolvedServerId", JsonPrimitive(downstream.resolvedServerId))
            putDownstreamName(request.type, downstream.downstreamName)
            put("errorMessage", JsonPrimitive(errorMessage))
        }
    }

    fun facadeToLlmResponse(
        request: LogRequestContext,
        response: JsonElement?,
        target: LogTargetContext = LogTargetContext(),
    ) {
        request.logger.infoJson(eventName(request.type, "facade_to_llm", "response")) {
            putRequestIdentity(request.type, request.name)
            target.targetServerId?.let { put("targetServerId", JsonPrimitive(it)) }
            target.downstreamName?.let { putDownstreamName(request.type, it) }
            put("response", redactOrNull(response))
        }
    }

    fun facadeToLlmError(
        request: LogRequestContext,
        errorMessage: String,
        target: LogTargetContext = LogTargetContext(),
        failure: Throwable? = null,
    ) {
        request.logger.errorJson(eventName(request.type, "facade_to_llm", "error"), failure) {
            putRequestIdentity(request.type, request.name)
            target.targetServerId?.let { put("targetServerId", JsonPrimitive(it)) }
            target.downstreamName?.let { putDownstreamName(request.type, it) }
            put("errorMessage", JsonPrimitive(errorMessage))
        }
    }

    fun toolDenied(
        request: LogRequestContext,
        reason: String,
    ) {
        request.logger.warnJson("proxy.tool.denied") {
            putRequestIdentity(request.type, request.name)
            put("reason", JsonPrimitive(reason))
        }
    }

    fun decodeFailed(
        request: LogRequestContext,
        target: LogTargetContext,
        rawResponse: JsonElement?,
        failure: Throwable? = null,
    ) {
        request.logger.warnJson("facade_to_llm.decode_failed", failure) {
            putRequestIdentity(request.type, request.name)
            target.targetServerId?.let { put("targetServerId", JsonPrimitive(it)) }
            target.downstreamName?.let { putDownstreamName(request.type, it) }
            put("rawResponse", redactOrNull(rawResponse))
        }
    }
}

private fun eventName(
    type: LogRequestType,
    prefix: String,
    action: String,
): String = "$prefix${type.eventSuffix}.$action"

private fun redactOrNull(element: JsonElement?): JsonElement = LogRedactor.redact(element) ?: JsonNull
