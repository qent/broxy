package io.qent.broxy.ui.adapter.clients

import java.nio.file.Path
import java.nio.file.Paths

class KiloCodeClientConnector(
    baseDir: Path = defaultBaseDir(),
) : McpJsonClientConnector(
        descriptor =
            AiClientDescriptor(
                id = "kilo",
                name = "KILO CODE",
                description = "The all-in-one agentic engineering platform",
                iconId = "kilo",
                infoUrl = "https://kilo.ai/",
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
        "kilocode.kilo-code",
        "settings",
    )
