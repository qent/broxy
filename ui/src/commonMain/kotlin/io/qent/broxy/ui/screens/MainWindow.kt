package io.qent.broxy.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.sp
import io.qent.broxy.ui.components.AppNavigationRail
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.viewmodels.Screen
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.adapter.store.AppStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainWindow(
    state: AppState,
    ui: UIState,
    store: AppStore,
    topBarModifier: Modifier = Modifier,
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
                val colors = if (useTransparentTitleBar) {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        scrolledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    TopAppBarDefaults.topAppBarColors()
                }
                    TopAppBar(
                    modifier = topBarModifier,
                    title = { Text(
                        "",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = AppTheme.layout.navigationRailWidth + AppTheme.spacing.md)
                    ) },
                    colors = colors
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                when (screen) {
                    Screen.Servers -> FloatingActionButton(onClick = { state.showAddServerDialog.value = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add server")
                    }
                    Screen.Presets -> FloatingActionButton(onClick = { state.showAddPresetDialog.value = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add preset")
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
                    onSelect = { state.currentScreen.value = it },
                    modifier = Modifier.fillMaxHeight()
                )
                Spacer(Modifier.width(AppTheme.spacing.md))
                Box(Modifier.fillMaxSize()) {
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
                            Screen.Proxy -> ProxyScreen(ui, state, notify)
                            Screen.Logs -> LogsScreen(ui)
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
        if (state.showAddPresetDialog.value) AddPresetDialog(ui, state, store)
    }
}
