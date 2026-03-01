package io.qent.broxy.ui.adapter.clients

import java.nio.file.Path
import java.nio.file.Paths

class VisualStudioCodeClientConnector(
    baseDir: Path = defaultBaseDir(),
) : McpJsonClientConnector(
        descriptor =
            AiClientDescriptor(
                id = "vscode",
                name = "Visual Studio Code",
                description = "The open source AI code editor",
                iconId = "vscode",
                infoUrl = "https://code.visualstudio.com/",
            ),
        baseDir = baseDir,
        serversKey = "servers",
    )

private fun defaultBaseDir(): Path = Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Code", "User")
