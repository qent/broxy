package io.qent.broxy.ui.adapter.clients

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.nio.file.Paths

class ClaudeCodeClientConnector(
    baseDir: Path = defaultBaseDir(),
) : McpJsonClientConnector(
        descriptor =
            AiClientDescriptor(
                id = "claude-code",
                name = "Claude Code",
                description = "AI coding agent for terminal & IDE",
                iconId = "claude_code",
                infoUrl = "https://www.claude.com/product/claude-code",
            ),
        baseDir = baseDir,
        configFileName = CONFIG_FILE_NAME,
        requireConfigFile = true,
        broxyEntryProvider = { request ->
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive(HTTP_TYPE),
                    "url" to JsonPrimitive(request.httpEndpoint),
                ),
            )
        },
    )

private fun defaultBaseDir(): Path = Paths.get(System.getProperty("user.home"))

private const val CONFIG_FILE_NAME = ".claude.json"
private const val HTTP_TYPE = "http"
