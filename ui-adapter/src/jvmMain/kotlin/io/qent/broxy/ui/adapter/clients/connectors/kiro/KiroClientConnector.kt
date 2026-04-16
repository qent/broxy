package io.qent.broxy.ui.adapter.clients

import java.nio.file.Path
import java.nio.file.Paths

class KiroClientConnector(
    baseDir: Path = defaultBaseDir(),
) : McpJsonClientConnector(
        descriptor =
            AiClientDescriptor(
                id = "kiro",
                name = "Kiro",
                description = "Agentic AI development from prototype to production",
                iconId = "kiro",
                infoUrl = "https://kiro.dev/",
            ),
        baseDir = baseDir,
    )

private fun defaultBaseDir(): Path = Paths.get(System.getProperty("user.home"), ".kiro", "settings")
