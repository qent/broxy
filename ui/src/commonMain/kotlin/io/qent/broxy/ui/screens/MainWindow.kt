@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppNavigationRail
import io.qent.broxy.ui.components.AppSnackbarHost
import io.qent.broxy.ui.components.AuthorizationPopupDialog
import io.qent.broxy.ui.components.BroxyFab
import io.qent.broxy.ui.components.GlobalHeader
import io.qent.broxy.ui.liquidglass.GlassBackdrop
import io.qent.broxy.ui.liquidglass.GlassScrim
import io.qent.broxy.ui.liquidglass.GlassSurface
import io.qent.broxy.ui.liquidglass.GlassSurfaceVariant
import io.qent.broxy.ui.liquidglass.ProvideGlassConfig
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.viewmodels.PresetEditorState
import io.qent.broxy.ui.viewmodels.Screen
import io.qent.broxy.ui.viewmodels.ServerEditorState
import kotlinx.coroutines.launch

private const val LUMINANCE_THRESHOLD = 0.5f
private const val DISABLED_FAB_ALPHA = 0.5f
private const val CHROME_TEXT_LIGHT_HEX = 0xFFDFDFDF
private const val SCREEN_FADE_DURATION_MS = 150

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun MainWindow(
    state: AppState,
    ui: UIState,
    store: AppStore,
    headerDragArea: @Composable (Modifier) -> Unit = { modifier -> Spacer(modifier) },
    useTransparentTitleBar: Boolean = false,
) {
    AppTheme(themeStyle = state.themeStyle.value) {
        val glassConfig = state.glassConfig.value
        val glassBackground = state.glassBackgroundScenario.value
        val strings = LocalStrings.current
        val screen = state.currentScreen.value
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val notify: (String) -> Unit = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
        val settingsFabState = remember { mutableStateOf<SettingsFabState?>(null) }
        val readyUi = ui as? UIState.Ready

        // Basic mapping: show snackbar on adapter Error state
        if (ui is UIState.Error) {
            LaunchedEffect(ui.message) {
                snackbarHostState.showSnackbar(strings.errorMessage(ui.message))
            }
        }
        val toastMessage = readyUi?.toastMessage
        val toastMessageId = readyUi?.toastMessageId
        if (toastMessage != null && toastMessageId != null) {
            LaunchedEffect(toastMessageId) {
                snackbarHostState.showSnackbar(strings.errorMessage(toastMessage))
            }
        }

        ProvideGlassConfig(config = glassConfig) {
            Box(Modifier.fillMaxSize()) {
                GlassBackdrop(scenario = glassBackground)
                GlassScrim(scenario = glassBackground)
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        val glassTopBarVariant =
                            if (useTransparentTitleBar) {
                                GlassSurfaceVariant.Clear
                            } else {
                                GlassSurfaceVariant.Regular
                            }
                        val chromeContainerColor = Color.Transparent
                        val chromeContentColor =
                            if (MaterialTheme.colorScheme.background.luminance() < LUMINANCE_THRESHOLD) {
                                Color(CHROME_TEXT_LIGHT_HEX)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }

                        GlassSurface(
                            modifier = Modifier.fillMaxWidth(),
                            variant = glassTopBarVariant,
                            shape = RectangleShape,
                        ) {
                            GlobalHeader(
                                ui = ui,
                                notify = notify,
                                colors =
                                    TopAppBarDefaults.topAppBarColors(
                                        containerColor = chromeContainerColor,
                                        scrolledContainerColor = chromeContainerColor,
                                        titleContentColor = chromeContentColor,
                                        navigationIconContentColor = chromeContentColor,
                                        actionIconContentColor = chromeContentColor,
                                    ),
                                dragArea = headerDragArea,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    snackbarHost = { AppSnackbarHost(snackbarHostState) },
                    floatingActionButton = {
                        when (screen) {
                            Screen.Servers -> {
                                if (state.serverEditor.value == null && state.serverDetailsId.value == null) {
                                    BroxyFab(onClick = { state.serverEditor.value = ServerEditorState.Create }) {
                                        Icon(
                                            Icons.Outlined.Add,
                                            contentDescription = strings.addServerContentDescription,
                                        )
                                    }
                                }
                            }

                            Screen.Presets -> {
                                if (state.presetEditor.value == null) {
                                    BroxyFab(onClick = { state.presetEditor.value = PresetEditorState.Create }) {
                                        Icon(
                                            Icons.Outlined.Add,
                                            contentDescription = strings.addPresetContentDescription,
                                        )
                                    }
                                }
                            }

                            Screen.Clients -> Unit

                            Screen.Settings -> {
                                val fabState = settingsFabState.value
                                if (fabState != null) {
                                    BroxyFab(
                                        onClick = {
                                            if (fabState.enabled) {
                                                fabState.onClick()
                                            }
                                        },
                                        modifier = Modifier.alpha(if (fabState.enabled) 1f else DISABLED_FAB_ALPHA),
                                    ) {
                                        Icon(
                                            Icons.Outlined.Save,
                                            contentDescription = strings.saveSettingsContentDescription,
                                        )
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        AppNavigationRail(
                            selected = screen,
                            onSelect = {
                                state.presetEditor.value = null
                                state.serverEditor.value = null
                                state.serverDetailsId.value = null
                                state.currentScreen.value = it
                            },
                            proxyStatus = readyUi?.proxyStatus,
                            onToggleProxy = readyUi?.intents?.let { { it.toggleProxyServer() } },
                            modifier = Modifier.fillMaxHeight(),
                        )
                        Box(Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.xs)) {
                            AnimatedContent(
                                targetState = screen,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(SCREEN_FADE_DURATION_MS)) togetherWith
                                        fadeOut(animationSpec = tween(SCREEN_FADE_DURATION_MS))
                                },
                                label = "screen",
                            ) { s ->
                                when (s) {
                                    Screen.Servers -> ServersScreen(ui, state, store, notify)
                                    Screen.Presets -> PresetsScreen(ui, state, store)
                                    Screen.Clients -> ClientsScreen(ui)
                                    Screen.Settings ->
                                        SettingsScreen(
                                            ui = ui,
                                            themeStyle = state.themeStyle.value,
                                            onThemeStyleChange = { state.themeStyle.value = it },
                                            glassConfig = state.glassConfig.value,
                                            onGlassConfigChange = { state.glassConfig.value = it },
                                            backgroundScenario = state.glassBackgroundScenario.value,
                                            onBackgroundScenarioChange = { state.glassBackgroundScenario.value = it },
                                            onFabStateChange = { settingsFabState.value = it },
                                            notify = notify,
                                        )
                                }
                            }
                        }
                    }
                }
            }
            val authPopup = readyUi?.authorizationPopup
            if (readyUi != null && authPopup != null) {
                AuthorizationPopupDialog(
                    popup = authPopup,
                    onCancel = { readyUi.intents.cancelAuthorization(authPopup.serverId) },
                    onOpenInBrowser = {
                        readyUi.intents.openAuthorizationInBrowser(authPopup.serverId, authPopup.authorizationUrl)
                    },
                    onDismiss = { readyUi.intents.dismissAuthorizationPopup(authPopup.serverId) },
                )
            }
        }
    }
}
