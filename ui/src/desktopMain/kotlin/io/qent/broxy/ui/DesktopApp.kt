package io.qent.broxy.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.qent.broxy.headless.logStdioInfo
import io.qent.broxy.headless.runStdioProxy
import io.qent.broxy.ui.adapter.models.UiPresetCore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.adapter.store.createAppStore
import io.qent.broxy.ui.icons.createApplicationIconImage
import io.qent.broxy.ui.icons.createTrayIconImage
import io.qent.broxy.ui.icons.rememberApplicationIconPainter
import io.qent.broxy.ui.presets.resolvePresetCapabilityStatus
import io.qent.broxy.ui.screens.MainWindow
import io.qent.broxy.ui.strings.AppLanguage
import io.qent.broxy.ui.strings.AppStrings
import io.qent.broxy.ui.strings.AppStringsProvider
import io.qent.broxy.ui.strings.ProvideAppStrings
import io.qent.broxy.ui.theme.PRESET_STATUS_DOT_NO_CAPABILITIES_HEX
import io.qent.broxy.ui.theme.PRESET_STATUS_DOT_PARTIAL_HEX
import io.qent.broxy.ui.theme.ThemeStyle
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.viewmodels.Screen
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.io.PushbackInputStream
import java.util.Locale
import kotlin.system.exitProcess
import java.awt.Color as AwtColor
import java.awt.Window as AwtWindow

private val TRAY_PRESET_DOT_PARTIAL_COLOR =
    AwtColor(
        ((PRESET_STATUS_DOT_PARTIAL_HEX shr 16) and 0xFF).toInt(),
        ((PRESET_STATUS_DOT_PARTIAL_HEX shr 8) and 0xFF).toInt(),
        (PRESET_STATUS_DOT_PARTIAL_HEX and 0xFF).toInt(),
    )
private val TRAY_PRESET_DOT_NO_CAPABILITIES_COLOR =
    AwtColor(
        ((PRESET_STATUS_DOT_NO_CAPABILITIES_HEX shr 16) and 0xFF).toInt(),
        ((PRESET_STATUS_DOT_NO_CAPABILITIES_HEX shr 8) and 0xFF).toInt(),
        (PRESET_STATUS_DOT_NO_CAPABILITIES_HEX and 0xFF).toInt(),
    )

fun main(args: Array<String>) {
    // Headless STDIO mode: allow MCP clients to spawn the app as an MCP server.
    // The preset is resolved from mcp.json (`defaultPresetId`) and is managed via the UI.
    if (shouldRunHeadlessStdioProxy(args) { probeStdinHasData(timeoutMillis = 200) }) {
        val r = runStdioProxy()
        if (r.isFailure) {
            logStdioInfo("[ERROR] Failed to start stdio proxy: ${r.exceptionOrNull()?.message}")
            exitProcess(1)
        }
        // If runStdioProxy returned successfully, the STDIO session ended gracefully.
        return
    }

    // Default: launch Desktop UI
    application {
        val appState = remember { AppState(initialScreen = Screen.Servers) }
        val store = remember { createAppStore() }
        LaunchedEffect(Unit) { store.start() }

        val uiState by store.state.collectAsState()
        var isWindowVisible by remember { mutableStateOf(true) }
        var bringToFrontRequest by remember { mutableStateOf(0) }
        val windowState = rememberWindowState()
        val trayController = remember { DesktopTrayController() }
        val traySupported = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
        val trayPreference = (uiState as? UIState.Ready)?.showTrayIcon ?: true
        val trayActive = traySupported && trayPreference
        val language = remember { AppLanguage.fromTag(Locale.getDefault().toLanguageTag()) }
        val strings = remember(language) { AppStringsProvider.forLanguage(language) }
        val isMacOs = remember { System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true }
        val isDarkTheme = appState.themeStyle.value == ThemeStyle.Dark
        val windowIconPainter = rememberApplicationIconPainter()
        val applicationIconImage = remember { createApplicationIconImage(size = 256) }
        val systemMenuSettingsHandler =
            rememberUpdatedState {
                openSettingsFromSystemMenu(
                    appState = appState,
                    requestShowAndFocusWindow = {
                        isWindowVisible = true
                        bringToFrontRequest += 1
                    },
                )
            }

        LaunchedEffect(trayActive) {
            if (!trayActive) {
                isWindowVisible = true
            }
        }

        DisposableEffect(isMacOs) {
            if (!isMacOs) {
                return@DisposableEffect onDispose {}
            }

            val desktop =
                runCatching {
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
                }.getOrNull()
            val disposeSystemMenuHandlers =
                desktop?.let {
                    installMacOsSystemMenuHandlers(
                        menuBridge = DesktopMacOsSystemMenuBridge(it),
                        onOpenSettingsRequested = {
                            EventQueue.invokeLater { systemMenuSettingsHandler.value.invoke() }
                        },
                    )
                } ?: {}
            onDispose { disposeSystemMenuHandlers() }
        }

        DisposableEffect(Unit) {
            onDispose {
                runCatching { store.stop() }
                trayController.dispose()
            }
        }

        ProvideAppStrings(language = language) {
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
                title = strings.appName,
                icon = windowIconPainter,
            ) {
                val window = this.window
                // Set minimum window height (in pixels). Width left unconstrained.
                SideEffect {
                    window.minimumSize = Dimension(780, 640)
                    (window as? Frame)?.iconImage = applicationIconImage
                    updateTaskbarIcon(applicationIconImage)
                }

                LaunchedEffect(isWindowVisible, bringToFrontRequest) {
                    if (isWindowVisible) {
                        bringWindowToFront(window)
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
                        val chromeColor =
                            if (isDarkTheme) {
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
                    headerDragArea = { modifier ->
                        if (isMacOs) {
                            this.WindowDraggableArea(modifier = modifier)
                        } else {
                            Spacer(modifier)
                        }
                    },
                    useTransparentTitleBar = isMacOs,
                )
            }
        }

        if (trayActive && traySupported) {
            val trayStatusDotColor =
                remember(uiState, isMacOs) {
                    resolveTrayPresetStatusDotColor(uiState, isMacOs)
                }
            val trayIconImage =
                remember(trayStatusDotColor) {
                    createTrayIconImage(size = 256, statusDotColor = trayStatusDotColor)
                }
            val trayModel =
                createTrayModel(
                    uiState = uiState,
                    trayIconImage = trayIconImage,
                    strings = strings,
                    onShowWindow = {
                        isWindowVisible = true
                        bringToFrontRequest += 1
                    },
                    onExit = {
                        isWindowVisible = false
                        runCatching { store.stop() }
                        exitApplication()
                    },
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

internal fun shouldRunHeadlessStdioProxy(
    args: Array<String>,
    stdinHasData: () -> Boolean,
): Boolean {
    val forceStdio = args.contains("--stdio-proxy")
    val autoStdio = !forceStdio && args.isEmpty() && stdinHasData()
    return forceStdio || autoStdio
}

internal fun openSettingsFromSystemMenu(
    appState: AppState,
    requestShowAndFocusWindow: () -> Unit,
) {
    requestShowAndFocusWindow()
    appState.presetEditor.value = null
    appState.serverEditor.value = null
    appState.serverDetailsId.value = null
    appState.catalogInstall.value = null
    appState.currentScreen.value = Screen.Settings
}

internal interface MacOsSystemMenuBridge {
    fun isSupported(action: Desktop.Action): Boolean

    fun setDefaultAboutHandler()

    fun setPreferencesHandler(handler: (() -> Unit)?)
}

private class DesktopMacOsSystemMenuBridge(
    private val desktop: Desktop,
) : MacOsSystemMenuBridge {
    override fun isSupported(action: Desktop.Action): Boolean = desktop.isSupported(action)

    override fun setDefaultAboutHandler() {
        desktop.setAboutHandler(null)
    }

    override fun setPreferencesHandler(handler: (() -> Unit)?) {
        if (handler == null) {
            desktop.setPreferencesHandler(null)
            return
        }
        desktop.setPreferencesHandler { _ -> handler() }
    }
}

internal fun installMacOsSystemMenuHandlers(
    menuBridge: MacOsSystemMenuBridge,
    onOpenSettingsRequested: () -> Unit,
): () -> Unit {
    val aboutSupported = runCatching { menuBridge.isSupported(Desktop.Action.APP_ABOUT) }.getOrDefault(false)
    if (aboutSupported) {
        runCatching { menuBridge.setDefaultAboutHandler() }
    }

    val preferencesSupported = runCatching { menuBridge.isSupported(Desktop.Action.APP_PREFERENCES) }.getOrDefault(false)
    if (preferencesSupported) {
        runCatching { menuBridge.setPreferencesHandler(onOpenSettingsRequested) }
    }

    return {
        if (preferencesSupported) {
            runCatching { menuBridge.setPreferencesHandler(null) }
        }
        if (aboutSupported) {
            runCatching { menuBridge.setDefaultAboutHandler() }
        }
    }
}

private fun probeStdinHasData(timeoutMillis: Long): Boolean {
    val original = System.`in`
    val pushback = PushbackInputStream(original, 4096)
    System.setIn(pushback)
    val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(0)
    while (System.currentTimeMillis() < deadline) {
        val available = runCatching { pushback.available() }.getOrDefault(0)
        if (available > 0) {
            val toRead = minOf(available, 4096)
            val buffer = ByteArray(toRead)
            val read = runCatching { pushback.read(buffer) }.getOrDefault(-1)
            if (read > 0) {
                pushback.unread(buffer, 0, read)
                return true
            }
            break
        }
        Thread.sleep(10)
    }
    // No input detected quickly → restore original stdin and proceed with normal UI.
    System.setIn(original)
    return false
}

private fun updateTaskbarIcon(image: Image) {
    val taskbar = runCatching { Taskbar.getTaskbar() }.getOrNull() ?: return
    if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
        runCatching { taskbar.iconImage = image }
    }
}

private fun bringWindowToFront(window: AwtWindow) {
    window.isVisible = true
    (window as? Frame)?.state = Frame.NORMAL
    window.toFront()
    window.requestFocus()
    window.requestFocusInWindow()
}

private fun resolveTrayPresetStatusDotColor(
    uiState: UIState,
    isMacOs: Boolean,
): AwtColor? {
    if (!isMacOs) return null
    val readyState = uiState as? UIState.Ready ?: return null
    val activePresetId = readyState.activeProxyPresetId ?: return null
    val activePreset = readyState.presets.firstOrNull { it.id == activePresetId } ?: return null
    val enabledServerIds =
        readyState.servers
            .asSequence()
            .filter { it.enabled }
            .map { it.id }
            .toSet()
    val capabilityStatus =
        resolvePresetCapabilityStatus(
            preset = activePreset,
            enabledServerIds = enabledServerIds,
        )
    return when {
        capabilityStatus.hasNoAvailableCapabilities -> TRAY_PRESET_DOT_NO_CAPABILITIES_COLOR
        capabilityStatus.hasCapabilityWarning -> TRAY_PRESET_DOT_PARTIAL_COLOR
        else -> null
    }
}

private fun createTrayModel(
    uiState: UIState,
    trayIconImage: Image,
    strings: AppStrings,
    onShowWindow: () -> Unit,
    onExit: () -> Unit,
): TrayModel {
    val content: TrayMenuContent =
        when (uiState) {
            UIState.Loading -> TrayMenuContent.Loading
            is UIState.Error -> TrayMenuContent.Error(uiState.message.ifBlank { strings.trayFailedToLoadPresets })
            is UIState.Ready -> {
                val activePresetId = uiState.activeProxyPresetId ?: UiPresetCore.EMPTY_PRESET_ID
                val builtInPresets =
                    listOf(
                        TrayPresetItem(
                            id = UiPresetCore.EMPTY_PRESET_ID,
                            name = strings.noPreset,
                            isActive = activePresetId == UiPresetCore.EMPTY_PRESET_ID,
                        ),
                        TrayPresetItem(
                            id = UiPresetCore.ALL_ENABLED_PRESET_ID,
                            name = strings.allEnabledServers,
                            isActive = activePresetId == UiPresetCore.ALL_ENABLED_PRESET_ID,
                        ),
                    )
                val presets =
                    uiState.presets.map { preset ->
                        TrayPresetItem(
                            id = preset.id,
                            name = preset.name,
                            isActive = preset.id == activePresetId,
                        )
                    }
                TrayMenuContent.Ready(
                    builtInPresets = builtInPresets,
                    presets = presets,
                    onPresetSelected = { presetId -> uiState.intents.selectProxyPreset(presetId) },
                )
            }
        }

    return TrayModel(
        tooltip = strings.appName,
        icon = trayIconImage,
        strings = strings,
        content = content,
        onShow = onShowWindow,
        onExit = onExit,
    )
}

private data class TrayModel(
    val tooltip: String,
    val icon: Image,
    val strings: AppStrings,
    val content: TrayMenuContent,
    val onShow: () -> Unit,
    val onExit: () -> Unit,
)

private sealed interface TrayMenuContent {
    data object Loading : TrayMenuContent

    data class Error(
        val message: String,
    ) : TrayMenuContent

    data class Ready(
        val builtInPresets: List<TrayPresetItem>,
        val presets: List<TrayPresetItem>,
        val onPresetSelected: (String?) -> Unit,
    ) : TrayMenuContent
}

private data class TrayPresetItem(
    val id: String,
    val name: String,
    val isActive: Boolean,
)

private class TrayActionListener(
    var callback: () -> Unit,
) : ActionListener {
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

    private fun getOrCreateIcon(
        systemTray: SystemTray,
        model: TrayModel,
    ): TrayIcon {
        val existing = trayIcon
        if (existing != null) {
            return existing
        }
        val icon =
            TrayIcon(model.icon, model.tooltip).apply {
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

    private fun attachActivationListener(
        icon: TrayIcon,
        onShow: () -> Unit,
    ) {
        val listener =
            activationListener ?: TrayActionListener(onShow).also {
                icon.addActionListener(it)
                activationListener = it
            }
        listener.callback = onShow
    }

    private fun rebuildMenu(
        icon: TrayIcon,
        model: TrayModel,
    ) {
        val strings = model.strings
        val menu = icon.popupMenu ?: PopupMenu().also { icon.popupMenu = it }
        menu.removeAll()
        when (val content = model.content) {
            TrayMenuContent.Loading -> {
                menu.add(disabledItem(strings.loadingPresets))
            }

            is TrayMenuContent.Error -> {
                menu.add(disabledItem(content.message))
            }

            is TrayMenuContent.Ready -> {
                content.builtInPresets.forEach { preset ->
                    menu.add(
                        menuItem(labelForPreset(preset, strings)) {
                            content.onPresetSelected(preset.id)
                        },
                    )
                }
                menu.addSeparator()
                if (content.presets.isEmpty()) {
                    menu.add(disabledItem(strings.trayNoPresetsAvailable))
                } else {
                    content.presets.forEach { preset ->
                        menu.add(
                            menuItem(labelForPreset(preset, strings)) {
                                content.onPresetSelected(preset.id)
                            },
                        )
                    }
                }
            }
        }
        menu.addSeparator()
        menu.add(menuItem(strings.trayShowApp) { model.onShow() })
        menu.add(menuItem(strings.trayExit) { model.onExit() })
    }

    private fun labelForPreset(
        preset: TrayPresetItem,
        strings: AppStrings,
    ): String = if (preset.isActive) preset.name + strings.trayActivePresetMarker else preset.name

    private fun menuItem(
        label: String,
        enabled: Boolean = true,
        action: (() -> Unit)? = null,
    ): MenuItem =
        MenuItem(label).apply {
            isEnabled = enabled
            if (action != null) {
                addActionListener { action() }
            }
        }

    private fun disabledItem(label: String): MenuItem = menuItem(label, enabled = false)
}
