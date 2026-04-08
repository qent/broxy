package io.qent.broxy.ui.adapter.clients

import java.nio.file.Path
import java.nio.file.Paths

class GoogleAntigravityClientConnector(
    baseDir: Path = defaultBaseDir(),
) : McpJsonClientConnector(
        descriptor =
            AiClientDescriptor(
                id = "antigravity",
                name = "Google Antigravity",
                description = "Experience liftoff with the next-generation IDE",
                iconId = "antigravity",
                infoUrl = "https://antigravity.google/",
            ),
        baseDir = baseDir,
        configFileName = "mcp_config.json",
    )

private fun defaultBaseDir(): Path = Paths.get(System.getProperty("user.home"), ".gemini", "antigravity")
