package io.qent.bro.ui

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "MCP Proxy") {
        BroApp()
    }
}
