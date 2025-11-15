package io.qent.broxy.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.headless.logStdioInfo
import io.qent.broxy.ui.adapter.headless.runStdioProxy
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.adapter.store.createAppStore
import io.qent.broxy.ui.icons.createApplicationIconImage
import io.qent.broxy.ui.icons.createTrayIconImage
import io.qent.broxy.ui.icons.rememberApplicationIconPainter
import io.qent.broxy.ui.screens.MainWindow
import io.qent.broxy.ui.viewmodels.AppState
import java.awt.AWTException
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Taskbar
import java.awt.TrayIcon
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.Color as AwtColor

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
        val trayController = remember { DesktopTrayController() }
        val traySupported = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
        val trayPreference = (uiState as? UIState.Ready)?.showTrayIcon ?: true
        val trayActive = traySupported && trayPreference
        val isMacOs = remember { System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true }
        val isDarkTheme = isSystemInDarkTheme()
        val windowIconPainter = rememberApplicationIconPainter()
        val applicationIconImage = remember { createApplicationIconImage(size = 256) }

        LaunchedEffect(trayActive) {
            if (!trayActive) {
                isWindowVisible = true
            }
        }

        DisposableEffect(Unit) {
            onDispose { trayController.dispose() }
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
            title = "broxy",
            icon = windowIconPainter
        ) {
            val window = this.window
            // Set minimum window height (in pixels). Width left unconstrained.
            SideEffect {
                window.minimumSize = Dimension(720, 640)
                (window as? Frame)?.iconImage = applicationIconImage
                updateTaskbarIcon(applicationIconImage)
            }
            val topBarModifier = remember(isMacOs, window) {
                if (isMacOs) {
                    Modifier.windowDrag(window)
                } else {
                    Modifier
                }
            }

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
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    val chromeColor = if (isDarkTheme) {
                        AwtColor(0x31, 0x46, 0x74)
                    } else {
                        AwtColor(0xF9, 0xFA, 0xFB)
                    }
                    window.background = chromeColor
                    window.rootPane.background = chromeColor
                    window.contentPane.background = chromeColor
                    window.rootPane.repaint()
                    window.repaint()
                }
            }

            MainWindow(
                state = appState,
                ui = uiState,
                store = store,
                topBarModifier = topBarModifier.height(28.dp),
                useTransparentTitleBar = isMacOs
            )
        }

        if (trayActive && traySupported) {
            val trayIconImage = remember { createTrayIconImage(size = 256) }
            val trayModel = createTrayModel(
                uiState = uiState,
                trayIconImage = trayIconImage,
                onShowWindow = { isWindowVisible = true },
                onExit = {
                    isWindowVisible = false
                    exitApplication()
                }
            )
            SideEffect {
                trayController.update(trayModel)
            }
        } else {
            SideEffect {
                trayController.dispose()
            }
        }
    }
}

private fun updateTaskbarIcon(image: Image) {
    val taskbar = runCatching { Taskbar.getTaskbar() }.getOrNull() ?: return
    if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
        runCatching { taskbar.iconImage = image }
    }
}

private fun createTrayModel(
    uiState: UIState,
    trayIconImage: Image,
    onShowWindow: () -> Unit,
    onExit: () -> Unit
): TrayModel {
    val content: TrayMenuContent = when (uiState) {
        UIState.Loading -> TrayMenuContent.Loading
        is UIState.Error -> TrayMenuContent.Error(uiState.message.ifBlank { "Failed to load presets" })
        is UIState.Ready -> {
            val fallbackPresetId = uiState.selectedPresetId ?: uiState.presets.singleOrNull()?.id
            val presets = uiState.presets.map { preset ->
                TrayPresetItem(
                    id = preset.id,
                    name = preset.name,
                    isActive = preset.id == uiState.selectedPresetId
                )
            }
            TrayMenuContent.Ready(
                presets = presets,
                fallbackPresetId = fallbackPresetId,
                proxyStatus = uiState.proxyStatus,
                onPresetInvoked = { presetId ->
                    val wasActive = presetId == uiState.selectedPresetId
                    uiState.intents.selectProxyPreset(presetId)
                    if (wasActive && uiState.proxyStatus is UiProxyStatus.Running) {
                        uiState.intents.startProxySimple(presetId)
                    }
                },
                onSelectPresetSilently = { presetId ->
                    if (uiState.selectedPresetId != presetId) {
                        uiState.intents.selectProxyPreset(presetId)
                    }
                },
                onStartProxy = { presetId -> uiState.intents.startProxySimple(presetId) },
                onStopProxy = { uiState.intents.stopProxy() }
            )
        }
    }

    return TrayModel(
        tooltip = "broxy",
        icon = trayIconImage,
        content = content,
        onShow = onShowWindow,
        onExit = onExit
    )
}

private data class TrayModel(
    val tooltip: String,
    val icon: Image,
    val content: TrayMenuContent,
    val onShow: () -> Unit,
    val onExit: () -> Unit
)

private sealed interface TrayMenuContent {
    data object Loading : TrayMenuContent
    data class Error(val message: String) : TrayMenuContent
    data class Ready(
        val presets: List<TrayPresetItem>,
        val fallbackPresetId: String?,
        val proxyStatus: UiProxyStatus,
        val onPresetInvoked: (String) -> Unit,
        val onSelectPresetSilently: (String) -> Unit,
        val onStartProxy: (String) -> Unit,
        val onStopProxy: () -> Unit
    ) : TrayMenuContent
}

private data class TrayPresetItem(
    val id: String,
    val name: String,
    val isActive: Boolean
)

private class TrayActionListener(var callback: () -> Unit) : ActionListener {
    override fun actionPerformed(e: ActionEvent?) {
        callback()
    }
}

private class DesktopTrayController {
    private val tray: SystemTray? = runCatching { SystemTray.getSystemTray() }.getOrNull()
    private var trayIcon: TrayIcon? = null
    private var activationListener: TrayActionListener? = null

    fun update(model: TrayModel) {
        val systemTray = tray ?: return
        EventQueue.invokeLater {
            val icon = getOrCreateIcon(systemTray, model)
            icon.image = model.icon
            icon.toolTip = model.tooltip
            attachActivationListener(icon, model.onShow)
            rebuildMenu(icon, model)
        }
    }

    fun dispose() {
        val systemTray = tray ?: return
        EventQueue.invokeLater {
            trayIcon?.let { icon ->
                activationListener?.let { icon.removeActionListener(it) }
                icon.popupMenu?.removeAll()
                runCatching { systemTray.remove(icon) }
            }
            trayIcon = null
            activationListener = null
        }
    }

    private fun getOrCreateIcon(systemTray: SystemTray, model: TrayModel): TrayIcon {
        val existing = trayIcon
        if (existing != null) {
            return existing
        }
        val icon = TrayIcon(model.icon, model.tooltip).apply {
            isImageAutoSize = true
            popupMenu = PopupMenu()
        }
        try {
            systemTray.add(icon)
        } catch (awt: AWTException) {
            trayIcon = null
            throw awt
        }
        trayIcon = icon
        return icon
    }

    private fun attachActivationListener(icon: TrayIcon, onShow: () -> Unit) {
        val listener = activationListener ?: TrayActionListener(onShow).also {
            icon.addActionListener(it)
            activationListener = it
        }
        listener.callback = onShow
    }

    private fun rebuildMenu(icon: TrayIcon, model: TrayModel) {
        val menu = icon.popupMenu ?: PopupMenu().also { icon.popupMenu = it }
        menu.removeAll()
        when (val content = model.content) {
            TrayMenuContent.Loading -> {
                menu.add(disabledItem("Loading presets..."))
                menu.addSeparator()
                menu.add(disabledItem("Server status: unknown"))
            }
            is TrayMenuContent.Error -> {
                menu.add(disabledItem(content.message))
                menu.addSeparator()
                menu.add(disabledItem("Server status: unavailable"))
            }
            is TrayMenuContent.Ready -> {
                if (content.presets.isEmpty()) {
                    menu.add(disabledItem("No presets available"))
                } else {
                    content.presets.forEach { preset ->
                        menu.add(menuItem(labelForPreset(preset)) {
                            content.onPresetInvoked(preset.id)
                        })
                    }
                }
                menu.addSeparator()
                val busy = content.proxyStatus is UiProxyStatus.Starting || content.proxyStatus is UiProxyStatus.Stopping
                menu.add(menuItem("Server status: ${statusText(content.proxyStatus)}", enabled = !busy) {
                    handleStatusClick(content, model.onShow)
                })
            }
        }
        menu.addSeparator()
        menu.add(menuItem("Show Broxy") { model.onShow() })
        menu.addSeparator()
        menu.add(menuItem("Exit") { model.onExit() })
    }

    private fun handleStatusClick(content: TrayMenuContent.Ready, showWindow: () -> Unit) {
        when (content.proxyStatus) {
            UiProxyStatus.Running -> content.onStopProxy()
            UiProxyStatus.Starting, UiProxyStatus.Stopping -> Unit
            UiProxyStatus.Stopped, is UiProxyStatus.Error -> {
                val presetId = content.fallbackPresetId
                if (presetId != null) {
                    content.onSelectPresetSilently(presetId)
                    content.onStartProxy(presetId)
                } else {
                    showWindow()
                }
            }
        }
    }

    private fun statusText(status: UiProxyStatus): String = when (status) {
        UiProxyStatus.Starting -> "starting"
        UiProxyStatus.Running -> "on"
        UiProxyStatus.Stopping -> "stopping"
        UiProxyStatus.Stopped -> "off"
        is UiProxyStatus.Error -> "error"
    }

    private fun labelForPreset(preset: TrayPresetItem): String {
        return if (preset.isActive) "${preset.name} \u2713" else preset.name
    }

    private fun menuItem(label: String, enabled: Boolean = true, action: (() -> Unit)? = null): MenuItem {
        return MenuItem(label).apply {
            isEnabled = enabled
            if (action != null) {
                addActionListener { action() }
            }
        }
    }

    private fun disabledItem(label: String): MenuItem = menuItem(label, enabled = false)
}
