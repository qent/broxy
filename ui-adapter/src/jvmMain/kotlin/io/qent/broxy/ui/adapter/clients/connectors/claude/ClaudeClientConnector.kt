package io.qent.broxy.ui.adapter.clients

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.nio.file.Paths

class ClaudeClientConnector(
    baseDir: Path = defaultBaseDir(),
    command: String = DEFAULT_COMMAND,
    args: List<String> = DEFAULT_ARGS,
) : McpJsonClientConnector(
        descriptor =
            AiClientDescriptor(
                id = "claude",
                name = "Claude",
                description = "Bring Claude to your desktop",
                iconId = "claude",
                infoUrl = "https://claude.com/download",
            ),
        baseDir = baseDir,
        configFileName = "claude_desktop_config.json",
        broxyEntryProvider = { buildStdioEntry(command, args) },
    )

private fun defaultBaseDir(): Path = Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Claude")

private fun buildStdioEntry(
    command: String,
    args: List<String>,
): JsonObject =
    JsonObject(
        mapOf(
            "command" to JsonPrimitive(command),
            "args" to JsonArray(args.map { JsonPrimitive(it) }),
        ),
    )

private const val DEFAULT_COMMAND = "/Applications/broxy.app/Contents/MacOS/broxy"
private val DEFAULT_ARGS = listOf("--stdio-proxy")
