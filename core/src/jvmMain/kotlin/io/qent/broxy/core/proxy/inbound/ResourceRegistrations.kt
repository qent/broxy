package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredResource
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.qent.broxy.core.utils.LogEventBuilder
import io.qent.broxy.core.utils.LogRequestContext
import io.qent.broxy.core.utils.LogRequestType
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import io.qent.broxy.core.mcp.ServerCapabilities as ProxyServerCapabilities

internal fun buildResourceRegistrations(
    capabilities: ProxyServerCapabilities,
    backend: ProxyBackend,
    logger: Logger,
): List<RegisteredResource> =
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
            readHandler = { _ -> handleResourceRequest(uri, backend, logger) },
        )
    }

private suspend fun handleResourceRequest(
    uri: String,
    backend: ProxyBackend,
    logger: Logger,
): ReadResourceResult {
    LogEventBuilder.llmToFacadeRequest(
        request = LogRequestContext(logger, LogRequestType.RESOURCE, uri),
        arguments = null,
    )
    val readResult = backend.readResource(uri)
    return if (readResult.isSuccess) {
        val el = readResult.getOrThrow()
        val decoded = Json.decodeFromJsonElement(ReadResourceResult.serializer(), el)
        val responseJson = Json.encodeToJsonElement(ReadResourceResult.serializer(), decoded)
        LogEventBuilder.facadeToLlmResponse(
            request = LogRequestContext(logger, LogRequestType.RESOURCE, uri),
            response = responseJson,
        )
        decoded
    } else {
        val failure = readResult.exceptionOrNull()
        val errMsg = failure?.message ?: "readResource failed"
        LogEventBuilder.facadeToLlmError(
            request = LogRequestContext(logger, LogRequestType.RESOURCE, uri),
            errorMessage = errMsg,
            failure = failure,
        )
        ReadResourceResult(
            contents = emptyList(),
            meta = JsonObject(mapOf("error" to JsonNull)),
        )
    }
}
