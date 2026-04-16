package io.qent.broxy.ui.adapter.clients

import java.nio.file.Path
import java.nio.file.Paths

class ClineClientConnector(
    baseDir: Path = defaultBaseDir(),
) : McpJsonClientConnector(
        descriptor =
            AiClientDescriptor(
                id = "cline",
                name = "Cline",
                description = "The Open Coding Agent",
                iconId = "cline",
                infoUrl = "https://cline.bot/",
            ),
        baseDir = baseDir,
        configFileName = "cline_mcp_settings.json",
    )

private fun defaultBaseDir(): Path =
    Paths.get(
        System.getProperty("user.home"),
        "Library",
        "Application Support",
        "Code",
        "User",
        "globalStorage",
        "saoudrizwan.claude-dev",
        "settings",
    )
