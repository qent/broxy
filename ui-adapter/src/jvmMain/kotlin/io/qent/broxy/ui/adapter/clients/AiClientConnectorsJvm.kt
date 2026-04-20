package io.qent.broxy.ui.adapter.clients

actual fun provideAiClientConnectors(): List<AiClientConnector> =
    listOf(
        ClaudeClientConnector(),
        ClaudeCodeClientConnector(),
        ClineClientConnector(),
        CodexClientConnector(),
        CursorClientConnector(),
        GeminiCliClientConnector(),
        GoogleAntigravityClientConnector(),
        KiloCodeClientConnector(),
        KiroClientConnector(),
        LmStudioClientConnector(),
        RooCodeClientConnector(),
        VisualStudioCodeClientConnector(),
        WindsurfClientConnector(),
    )
