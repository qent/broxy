package io.qent.bro.ui

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.qent.bro.ui.screens.MainWindow
import io.qent.bro.ui.viewmodels.AppState

fun main() = application {
    val state = AppState()
    Window(onCloseRequest = ::exitApplication, title = "MCP Proxy") {
        MainWindow(state)
    }
}

