package io.qent.broxy.ui.adapter.clients

import java.nio.file.Path
import java.nio.file.Paths

class RooCodeClientConnector(
    baseDir: Path = defaultBaseDir(),
) : McpJsonClientConnector(
        descriptor =
            AiClientDescriptor(
                id = "roo-code",
                name = "Roo Code",
                description = "The AI dev team that gets things done",
                iconId = "roo-code",
                infoUrl = "https://roocode.com/",
            ),
        baseDir = baseDir,
        configFileName = "mcp_settings.json",
    )

private fun defaultBaseDir(): Path =
    Paths.get(
        System.getProperty("user.home"),
        "Library",
        "Application Support",
        "Code",
        "User",
        "globalStorage",
        "rooveterinaryinc.roo-cline",
        "settings",
    )
