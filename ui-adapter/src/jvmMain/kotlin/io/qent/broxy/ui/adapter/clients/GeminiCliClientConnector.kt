package io.qent.broxy.ui.adapter.clients

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

class GeminiCliClientConnector(
    baseDir: Path = defaultBaseDir(),
) : McpJsonClientConnector(
        descriptor =
            AiClientDescriptor(
                id = "gemini-cli",
                name = "Gemini CLI",
                description = "Build  debug & deploy with AI",
                iconId = "gemini",
                infoUrl = "https://geminicli.com/",
            ),
        baseDir = baseDir,
        configFileName = CONFIG_FILE_NAME,
        requireConfigFile = true,
        broxyEntryProvider = { request -> JsonObject(mapOf("url" to JsonPrimitive(sseEndpointFor(request.httpEndpoint)))) },
    )

private fun defaultBaseDir(): Path = Paths.get(System.getProperty("user.home"), ".gemini")

private fun sseEndpointFor(httpEndpoint: String): String {
    val uri = runCatching { URI(httpEndpoint) }.getOrNull() ?: return httpEndpoint
    val sseUri = URI(uri.scheme, uri.userInfo, uri.host, uri.port, "/sse", null, null)
    return sseUri.toString()
}

private const val CONFIG_FILE_NAME = "settings.json"
