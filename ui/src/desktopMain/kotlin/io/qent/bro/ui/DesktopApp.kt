package io.qent.bro.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import io.qent.bro.ui.adapter.headless.runStdioProxy
import io.qent.bro.ui.adapter.store.UIState
import io.qent.bro.ui.screens.MainWindow
import io.qent.bro.ui.viewmodels.AppState
import io.qent.bro.ui.adapter.store.createAppStore
import java.awt.Frame
import java.awt.SystemTray

fun main(args: Array<String>) {
    // Headless STDIO mode: allow Claude Desktop to spawn the app as an MCP server.
    if (args.contains("--stdio-proxy")) {
        val presetId = args.indexOf("--preset-id").takeIf { it >= 0 }?.let { idx -> args.getOrNull(idx + 1) }
        val configDir = args.indexOf("--config-dir").takeIf { it >= 0 }?.let { idx -> args.getOrNull(idx + 1) }
        if (presetId.isNullOrBlank()) {
            System.err.println("Usage: bro --stdio-proxy --preset-id <id> [--config-dir <path>]")
            kotlin.system.exitProcess(2)
        }
        val r = runStdioProxy(presetId, configDir)
        if (r.isFailure) {
            System.err.println("[ERROR] Failed to start stdio proxy: ${r.exceptionOrNull()?.message}")
            kotlin.system.exitProcess(1)
        }
        // If runStdioProxy returned successfully, the STDIO session ended gracefully.
        return
    }

    // Default: launch Desktop UI
    application {
        val appState = remember { AppState() }
        val store = remember { createAppStore() }
        LaunchedEffect(Unit) { store.start() }

        val uiState by store.state.collectAsState()
        var isWindowVisible by remember { mutableStateOf(true) }
        val windowState = rememberWindowState()
        val traySupported = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
        val trayPreference = (uiState as? UIState.Ready)?.showTrayIcon ?: true
        val trayActive = traySupported && trayPreference

        LaunchedEffect(trayActive) {
            if (!trayActive) {
                isWindowVisible = true
            }
        }

        Window(
            state = windowState,
            visible = isWindowVisible,
            onCloseRequest = {
                if (trayActive) {
                    isWindowVisible = false
                } else {
                    exitApplication()
                }
            },
            title = "bro"
        ) {
            val window = this.window
            LaunchedEffect(isWindowVisible) {
                if (isWindowVisible) {
                    window.isVisible = true
                    (window as? java.awt.Frame)?.state = java.awt.Frame.NORMAL
                    window.toFront()
                    window.requestFocus()
                }
            }
            MainWindow(appState, uiState, store)
        }

        if (trayActive) {
            val trayVector = remember {
                ImageVector.Builder(
                    name = "BroTrayIcon",
                    defaultWidth = 18.dp,
                    defaultHeight = 18.dp,
                    viewportWidth = 18f,
                    viewportHeight = 18f
                ).apply {
                    path(fill = SolidColor(Color(0xFF1A237E))) {
                        moveTo(9f, 1f)
                        arcToRelative(8f, 8f, 0f, true, true, 0f, 16f)
                        arcToRelative(8f, 8f, 0f, true, true, 0f, -16f)
                        close()
                    }
                    path(fill = SolidColor(Color.White)) {
                        moveTo(6.0f, 4.5f)
                        lineTo(7.5f, 4.5f)
                        lineTo(7.5f, 13.5f)
                        lineTo(6.0f, 13.5f)
                        close()
                    }
                    path(fill = SolidColor(Color.White)) {
                        moveTo(11.5f, 7.0f)
                        arcToRelative(2.25f, 2.25f, 0f, true, true, -4.5f, 0f)
                        arcToRelative(2.25f, 2.25f, 0f, true, true, 4.5f, 0f)
                        close()
                    }
                }.build()
            }
            val trayPainter = rememberVectorPainter(trayVector)
            Tray(
                icon = trayPainter,
                tooltip = "bro",
                onAction = { isWindowVisible = true }
            ) {
                Item("Show bro") {
                    isWindowVisible = true
                }
                Item("Exit") {
                    isWindowVisible = false
                    exitApplication()
                }
            }
        }
    }
}
