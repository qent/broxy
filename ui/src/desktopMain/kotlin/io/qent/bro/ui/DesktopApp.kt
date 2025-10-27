package io.qent.bro.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.qent.bro.ui.screens.MainWindow
import io.qent.bro.ui.viewmodels.AppState
import io.qent.bro.core.config.ConfigurationObserver
import io.qent.bro.core.config.ConfigurationWatcher
import io.qent.bro.core.config.JsonConfigurationRepository
import io.qent.bro.core.models.McpServersConfig
import io.qent.bro.core.models.Preset
import io.qent.bro.core.utils.ConsoleLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

fun main() = application {
    val state = AppState()
    Window(onCloseRequest = ::exitApplication, title = "bro") {
        // Initial load of servers and presets
        val repo = remember { JsonConfigurationRepository(logger = ConsoleLogger) }
        LaunchedEffect(Unit) {
            runCatching { repo.loadMcpConfig() }
                .onSuccess { cfg ->
                    state.servers.clear(); state.servers.addAll(cfg.servers)
                }
                .onFailure { ConsoleLogger.error("Failed to load mcp.json: ${it.message}", it) }
            runCatching { repo.listPresets() }
                .onSuccess { presets ->
                    state.presets.clear()
                    state.presets.addAll(presets.map { p ->
                        io.qent.bro.ui.viewmodels.UiPreset(
                            id = p.id,
                            name = p.name,
                            description = p.description.ifBlank { null },
                            toolsCount = p.tools.count { it.enabled }
                        )
                    })
                }
                .onFailure { ConsoleLogger.error("Failed to load presets: ${it.message}", it) }
        }

        // Watch for external changes (reactive updates)
        val watcher = remember { mutableStateOf<ConfigurationWatcher?>(null) }
        LaunchedEffect(Unit) {
            val w = ConfigurationWatcher(repo = repo, logger = ConsoleLogger)
            w.addObserver(object : ConfigurationObserver {
                override fun onConfigurationChanged(config: McpServersConfig) {
                    GlobalScope.launch(Dispatchers.Main) {
                        state.servers.clear(); state.servers.addAll(config.servers)
                    }
                }
                override fun onPresetChanged(preset: Preset) {
                    GlobalScope.launch(Dispatchers.Main) {
                        val idx = state.presets.indexOfFirst { it.id == preset.id }
                        val ui = io.qent.bro.ui.viewmodels.UiPreset(
                            id = preset.id,
                            name = preset.name,
                            description = preset.description.ifBlank { null },
                            toolsCount = preset.tools.count { it.enabled }
                        )
                        if (idx >= 0) state.presets[idx] = ui else state.presets.add(ui)
                    }
                }
            })
            w.start()
            watcher.value = w
        }
        DisposableEffect(Unit) {
            onDispose { watcher.value?.close() }
        }
        MainWindow(state)
    }
}
