package io.qent.bro.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.qent.bro.ui.screens.MainWindow
import io.qent.bro.ui.viewmodels.AppState
import io.qent.bro.ui.adapter.store.createAppStore

fun main() = application {
    val appState = remember { AppState() }
    val store = remember { createAppStore() }
    Window(onCloseRequest = ::exitApplication, title = "bro") {
        LaunchedEffect(Unit) { store.start() }
        val uiState by store.state.collectAsState()
        MainWindow(appState, uiState)
    }
}
