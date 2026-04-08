package io.qent.broxy.ui.adapter.clients

import java.nio.file.Path
import java.nio.file.Paths

class LmStudioClientConnector(
    baseDir: Path = defaultBaseDir(),
) : McpJsonClientConnector(
        descriptor =
            AiClientDescriptor(
                id = "lmstudio",
                name = "LM Studio",
                description = "Local AI, on Your Computer",
                iconId = "lmstudio",
                infoUrl = "https://lmstudio.ai/",
            ),
        baseDir = baseDir,
    )

private fun defaultBaseDir(): Path = Paths.get(System.getProperty("user.home"), ".lmstudio")
