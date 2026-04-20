package io.qent.broxy.ui.adapter.clients

import java.nio.file.Path
import java.nio.file.Paths

class WindsurfClientConnector(
    baseDir: Path = defaultBaseDir(),
) : McpJsonClientConnector(
        descriptor =
            AiClientDescriptor(
                id = "windsurf",
                name = "Windsurf",
                description = "Built to Keep You in Flow State",
                iconId = "windsurf",
                infoUrl = "https://windsurf.com/editor",
            ),
        baseDir = baseDir,
        configFileName = "mcp_config.json",
    )

private fun defaultBaseDir(): Path = Paths.get(System.getProperty("user.home"), ".codeium", "windsurf")
