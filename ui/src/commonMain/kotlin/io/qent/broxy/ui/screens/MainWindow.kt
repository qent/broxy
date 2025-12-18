package io.qent.broxy.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppNavigationRail
import io.qent.broxy.ui.components.GlobalHeader
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.viewmodels.PresetEditorState
import io.qent.broxy.ui.viewmodels.Screen
import kotlinx.coroutines.launch

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainWindow(
    state: AppState,
    ui: UIState,
    store: AppStore,
    useTransparentTitleBar: Boolean = false
) {
    AppTheme(themeStyle = state.themeStyle.value) {
        val screen = state.currentScreen.value
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val notify: (String) -> Unit = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }

        // Basic mapping: show snackbar on adapter Error state
        if (ui is UIState.Error) {
            LaunchedEffect(ui.message) {
                snackbarHostState.showSnackbar("Error: ${ui.message}")
            }
        }

        Scaffold(
            topBar = {
                val chromeContainerColor = if (useTransparentTitleBar) {
                    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                        Color(0xFF314674)
                    } else {
                        Color(0xFFF9FAFB)
                    }
                } else {
                    MaterialTheme.colorScheme.surface
                }
                val chromeContentColor = if (chromeContainerColor.luminance() < 0.5f) {
                    Color(0xFFDFDFDF)
                } else {
                    MaterialTheme.colorScheme.onSurface
                }

                GlobalHeader(
                    ui = ui,
                    notify = notify,
                    colors = if (useTransparentTitleBar) {
                        TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = chromeContainerColor,
                            scrolledContainerColor = chromeContainerColor,
                            titleContentColor = chromeContentColor,
                            navigationIconContentColor = chromeContentColor,
                            actionIconContentColor = chromeContentColor
                        )
                    } else {
                        TopAppBarDefaults.centerAlignedTopAppBarColors()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                when (screen) {
                    Screen.Servers -> FloatingActionButton(onClick = { state.showAddServerDialog.value = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add server")
                    }
                    Screen.Presets -> {
                        if (state.presetEditor.value == null) {
                            FloatingActionButton(onClick = { state.presetEditor.value = PresetEditorState.Create }) {
                                Icon(Icons.Outlined.Add, contentDescription = "Add preset")
                            }
                        }
                    }
                    else -> {}
                }
            }
        ) { padding ->
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                AppNavigationRail(
                    selected = screen,
                    onSelect = {
                        if (it != Screen.Presets) {
                            state.presetEditor.value = null
                        }
                        state.currentScreen.value = it
                    },
                    proxyStatus = (ui as? UIState.Ready)?.proxyStatus,
                    modifier = Modifier.fillMaxHeight()
                )
                Box(Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.xs)) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(150))
                        },
                        label = "screen"
                    ) { s ->
                        when (s) {
                            Screen.Servers -> ServersScreen(ui, state, store, notify)
                            Screen.Presets -> PresetsScreen(ui, state, store)
                            Screen.Settings -> SettingsScreen(
                                ui = ui,
                                themeStyle = state.themeStyle.value,
                                onThemeStyleChange = { state.themeStyle.value = it },
                                notify = notify
                            )
                        }
                    }
                }
            }
        }

        // Dialogs
        if (state.showAddServerDialog.value) AddServerDialog(ui, state, notify)
    }
}
