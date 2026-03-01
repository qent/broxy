package io.qent.broxy.ui.adapter.clients

import java.nio.file.Path
import java.nio.file.Paths

class CursorClientConnector(
    baseDir: Path = defaultBaseDir(),
) : McpJsonClientConnector(
        descriptor =
            AiClientDescriptor(
                id = "cursor",
                name = "Cursor",
                description = "The best way to build software",
                iconId = "cursor",
                infoUrl = "https://cursor.com/",
            ),
        baseDir = baseDir,
    )

private fun defaultBaseDir(): Path = Paths.get(System.getProperty("user.home"), ".cursor")
