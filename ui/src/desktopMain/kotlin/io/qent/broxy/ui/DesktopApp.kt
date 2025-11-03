package io.qent.broxy.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import io.qent.broxy.ui.adapter.headless.logStdioInfo
import io.qent.broxy.ui.adapter.headless.runStdioProxy
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.screens.MainWindow
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.adapter.store.createAppStore
import java.awt.Frame
import java.awt.SystemTray

fun main(args: Array<String>) {
    // Headless STDIO mode: allow Claude Desktop to spawn the app as an MCP server.
    if (args.contains("--stdio-proxy")) {
        val presetId = args.indexOf("--preset-id").takeIf { it >= 0 }?.let { idx -> args.getOrNull(idx + 1) }
        val configDir = args.indexOf("--config-dir").takeIf { it >= 0 }?.let { idx -> args.getOrNull(idx + 1) }
        if (presetId.isNullOrBlank()) {
            logStdioInfo("Usage: broxy --stdio-proxy --preset-id <id> [--config-dir <path>]")
            kotlin.system.exitProcess(2)
        }
        val r = runStdioProxy(presetId, configDir)
        if (r.isFailure) {
            logStdioInfo("[ERROR] Failed to start stdio proxy: ${r.exceptionOrNull()?.message}")
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
        val isMacOs = remember { System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true }

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
            title = "broxy"
        ) {
            val window = this.window
            val isDarkTheme = isSystemInDarkTheme()

            LaunchedEffect(isWindowVisible) {
                if (isWindowVisible) {
                    window.isVisible = true
                    (window as? java.awt.Frame)?.state = java.awt.Frame.NORMAL
                    window.toFront()
                    window.requestFocus()
                }
            }

            if (isMacOs) {
                SideEffect {
                    val appearance = if (isDarkTheme) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua"
                    window.rootPane.putClientProperty("apple.awt.windowAppearance", appearance)
                    window.rootPane.putClientProperty("apple.awt.application.appearance", appearance)
                    System.setProperty("apple.awt.application.appearance", appearance)
                    window.rootPane.repaint()
                }
            }

            MainWindow(appState, uiState, store)
        }

        if (trayActive) {
            val trayVector = remember {
                ImageVector.Builder(
                    name = "BroxyTrayIcon",
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
            val trayState = uiState
            Tray(
                icon = trayPainter,
                tooltip = "broxy",
                onAction = { isWindowVisible = true }
            ) {
                when (val state = trayState) {
                    UIState.Loading -> {
                        Item("Loading presets...", enabled = false) {}
                        Separator()
                        Item("Server status: unknown", enabled = false) {}
                    }
                    is UIState.Error -> {
                        Item("Failed to load presets", enabled = false) {}
                        Separator()
                        Item("Server status: unavailable", enabled = false) {}
                    }
                    is UIState.Ready -> {
                        if (state.presets.isEmpty()) {
                            Item("No presets available", enabled = false) {}
                        } else {
                            state.presets.forEach { preset ->
                                val isActive = preset.id == state.selectedPresetId
                                val label = buildString {
                                    append(preset.name)
                                    if (isActive) append(" \u2713")
                                }
                                Item(label) {
                                    val wasActive = isActive
                                    state.intents.selectProxyPreset(preset.id)
                                    if (wasActive && state.proxyStatus is UiProxyStatus.Running) {
                                        state.intents.startProxySimple(preset.id)
                                    }
                                }
                            }
                        }
                        Separator()
                        val running = state.proxyStatus is UiProxyStatus.Running
                        val statusLabel = "Server status: ${if (running) "on" else "off"}"
                        Item(statusLabel) {
                            if (running) {
                                state.intents.stopProxy()
                            } else {
                                val presetId = state.selectedPresetId
                                    ?: state.presets.singleOrNull()?.id
                                if (presetId != null) {
                                    if (state.selectedPresetId != presetId) {
                                        state.intents.selectProxyPreset(presetId)
                                    }
                                    state.intents.startProxySimple(presetId)
                                } else {
                                    isWindowVisible = true
                                }
                            }
                        }
                    }
                }
                Item("Show Broxy") {
                    isWindowVisible = true
                }
                Separator()
                Item("Exit") {
                    isWindowVisible = false
                    exitApplication()
                }
            }
        }
    }
}
