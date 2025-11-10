package io.qent.broxy.core.config

import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.*
import kotlin.io.path.exists

class ConfigurationWatcher(
    private val baseDir: Path = Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    private val repo: ConfigurationRepository,
    private val logger: Logger? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val debounceMillis: Long = 300,
    private val emitInitialState: Boolean = true
) : AutoCloseable {

    private var watchService: WatchService? = null
    private val observers = mutableSetOf<ConfigurationObserver>()
    private var watchJob: Job? = null
    private var notifyJob: Job? = null

    private var dirtyConfig = false
    private val dirtyPresets = mutableSetOf<Path>()

    fun addObserver(observer: ConfigurationObserver) { observers.add(observer) }
    fun removeObserver(observer: ConfigurationObserver) { observers.remove(observer) }

    fun triggerConfigReload() { markConfigDirtyAndSchedule() }
    fun triggerPresetReload(id: String) { markPresetDirtyAndSchedule(Paths.get("preset_${id}.json")) }

    fun start() {
        if (watchJob != null) return
        if (!baseDir.exists()) {
            logger?.warn("Configuration directory ${baseDir.toAbsolutePath()} does not exist; watcher idle")
            return
        }
        val ws = FileSystems.getDefault().newWatchService()
        watchService = ws
        try {
            baseDir.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
        } catch (e: IOException) {
            logger?.warn("Failed to register watcher: ${e.message}", e)
            return
        }

        logger?.info("Watching configuration directory ${baseDir.toAbsolutePath()}")
        if (emitInitialState) {
            // Emit initial state after debounce when explicitly requested
            markConfigDirtyAndSchedule()
        }

        watchJob = scope.launch {
            // Watcher loop
            withContext(Dispatchers.IO) {
                while (true) {
                    val key = try { ws.take() } catch (_: ClosedWatchServiceException) { break }
                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue
                        val ev = event as WatchEvent<Path>
                        val fileName = ev.context()
                        val nameStr = fileName.fileName.toString()
                        if (nameStr == "mcp.json") {
                            markConfigDirtyAndSchedule()
                        } else if (nameStr.startsWith("preset_") && nameStr.endsWith(".json")) {
                            markPresetDirtyAndSchedule(fileName)
                        }
                    }
                    val valid = key.reset()
                    if (!valid) break
                }
            }
        }
    }

    fun stop() { close() }

    override fun close() {
        runCatching { watchService?.close() }
        watchService = null
        watchJob?.cancel()
        watchJob = null
        notifyJob?.cancel()
        notifyJob = null
        observers.clear()
    }

    private fun markConfigDirtyAndSchedule() {
        dirtyConfig = true
        scheduleNotify()
    }

    private fun markPresetDirtyAndSchedule(file: Path) {
        dirtyPresets.add(file)
        scheduleNotify()
    }

    private fun scheduleNotify() {
        notifyJob?.cancel()
        notifyJob = scope.launch {
            delay(debounceMillis)
            val notifyConfig = dirtyConfig
            val presetFiles = dirtyPresets.toList()
            dirtyConfig = false
            dirtyPresets.clear()

            if (notifyConfig) {
                runCatching { repo.loadMcpConfig() }
                    .onSuccess { cfg -> observers.forEach { it.onConfigurationChanged(cfg) } }
                    .onFailure { ex -> logger?.warn("Failed to reload mcp.json: ${ex.message}", ex) }
            }

            presetFiles.forEach { file ->
                val name = file.fileName.toString()
                if (Files.exists(baseDir.resolve(name))) {
                    val id = name.removePrefix("preset_").removeSuffix(".json")
                    runCatching { repo.loadPreset(id) }
                        .onSuccess { p: Preset -> observers.forEach { it.onPresetChanged(p) } }
                        .onFailure { ex -> logger?.warn("Failed to reload preset '$name': ${ex.message}", ex) }
                } else {
                    logger?.info("Preset file deleted: $name")
                }
            }
        }
    }
}
