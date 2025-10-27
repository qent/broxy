package io.qent.bro.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.animation.togetherWith
import io.qent.bro.ui.components.AppNavigationRail
import io.qent.bro.ui.theme.AppTheme
import io.qent.bro.ui.viewmodels.AppState
import io.qent.bro.ui.viewmodels.Screen
import io.qent.bro.ui.adapter.store.UIState
import kotlinx.coroutines.launch

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainWindow(state: AppState, ui: UIState) {
    AppTheme(settings = state.theme.value) {
        val screen = state.currentScreen.value
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val notify: (String) -> Unit = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(screen.title) }
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
            Row(Modifier.fillMaxSize().padding(padding)) {
                AppNavigationRail(
                    selected = screen,
                    onSelect = { state.currentScreen.value = it },
                    modifier = Modifier.fillMaxHeight()
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(150))
                        },
                        label = "screen"
                    ) { s ->
                        when (s) {
                            Screen.Servers -> ServersScreen(ui, state, notify)
                            Screen.Presets -> PresetsScreen(ui, state)
                            Screen.Proxy -> ProxyScreen(ui, state, notify)
                            Screen.Settings -> SettingsScreen(state)
                        }
                    }
                }
            }
        }

        // Dialogs
        if (state.showAddServerDialog.value) AddServerDialog(ui, state, notify)
        if (state.showAddPresetDialog.value) AddPresetDialog(ui, state)
    }
}
