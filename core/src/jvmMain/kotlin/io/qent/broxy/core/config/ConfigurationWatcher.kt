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
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists

class ConfigurationWatcher(
    private val baseDir: Path = Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    private val repo: ConfigurationRepository,
    private val logger: Logger? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val debounceMillis: Long = 300,
    private val emitInitialState: Boolean = true,
) : AutoCloseable {
    private var watchService: WatchService? = null
    private val observers = CopyOnWriteArraySet<ConfigurationObserver>()
    private var watchJob: Job? = null
    private var notifyJob: Job? = null

    private val dirtyLock = ReentrantLock()
    private var dirtyConfig = false
    private val dirtyPresets = mutableSetOf<Path>()

    fun addObserver(observer: ConfigurationObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ConfigurationObserver) {
        observers.remove(observer)
    }

    fun triggerConfigReload() {
        markConfigDirtyAndSchedule()
    }

    fun triggerPresetReload(id: String) {
        markPresetDirtyAndSchedule(Paths.get("preset_$id.json"))
    }

    fun start() {
        if (watchJob != null) {
            return
        }
        val exists = baseDir.exists()
        if (!exists) {
            logger?.warn("Configuration directory ${baseDir.toAbsolutePath()} does not exist; watcher idle")
        }
        val ws = if (exists) FileSystems.getDefault().newWatchService() else null
        val registered = ws?.let { registerWatchService(baseDir, logger, it) } ?: false
        if (registered) {
            watchService = ws
            logger?.info("Watching configuration directory ${baseDir.toAbsolutePath()}")
            if (emitInitialState) {
                // Emit initial state after debounce when explicitly requested
                markConfigDirtyAndSchedule()
            }
            watchJob =
                scope.launch {
                    watchLoop(
                        ws,
                        onConfig = ::markConfigDirtyAndSchedule,
                        onPreset = ::markPresetDirtyAndSchedule,
                    )
                }
        } else {
            watchService = null
        }
    }

    fun stop() {
        close()
    }

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
        dirtyLock.withLock {
            dirtyConfig = true
        }
        scheduleNotify()
    }

    private fun markPresetDirtyAndSchedule(file: Path) {
        dirtyLock.withLock {
            dirtyPresets.add(file)
        }
        scheduleNotify()
    }

    private fun scheduleNotify() {
        notifyJob?.cancel()
        notifyJob =
            scope.launch {
                delay(debounceMillis)
                val snapshot =
                    dirtyLock.withLock {
                        val notifyConfig = dirtyConfig
                        val presetFiles = dirtyPresets.toList()
                        dirtyConfig = false
                        dirtyPresets.clear()
                        DirtySnapshot(notifyConfig = notifyConfig, presetFiles = presetFiles)
                    }

                if (snapshot.notifyConfig) {
                    runCatching { repo.loadMcpConfig() }
                        .onSuccess { cfg -> observers.forEach { it.onConfigurationChanged(cfg) } }
                        .onFailure { ex -> logger?.warn("Failed to reload mcp.json: ${ex.message}", ex) }
                }

                snapshot.presetFiles.forEach { file ->
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

    private data class DirtySnapshot(
        val notifyConfig: Boolean,
        val presetFiles: List<Path>,
    )
}

private suspend fun watchLoop(
    ws: WatchService,
    onConfig: () -> Unit,
    onPreset: (Path) -> Unit,
) {
    withContext(Dispatchers.IO) {
        var running = true
        while (running) {
            val key =
                try {
                    ws.take()
                } catch (_: ClosedWatchServiceException) {
                    running = false
                    null
                }
            if (key != null) {
                handleWatchKeyEvents(
                    key.pollEvents(),
                    onConfig = onConfig,
                    onPreset = onPreset,
                )
                if (!key.reset()) {
                    running = false
                }
            }
        }
    }
}

private fun registerWatchService(
    baseDir: Path,
    logger: Logger?,
    ws: WatchService,
): Boolean =
    try {
        baseDir.register(
            ws,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )
        true
    } catch (e: IOException) {
        logger?.warn("Failed to register watcher: ${e.message}", e)
        false
    }

private fun handleWatchKeyEvents(
    events: List<java.nio.file.WatchEvent<*>>,
    onConfig: () -> Unit,
    onPreset: (Path) -> Unit,
) {
    events
        .asSequence()
        .filter { it.kind() != StandardWatchEventKinds.OVERFLOW }
        .mapNotNull { it.context() as? Path }
        .forEach { handleFileChange(it, onConfig, onPreset) }
}

private fun handleFileChange(
    fileName: Path,
    onConfig: () -> Unit,
    onPreset: (Path) -> Unit,
) {
    val nameStr = fileName.fileName.toString()
    if (nameStr == "mcp.json") {
        onConfig()
    } else if (nameStr.startsWith("preset_") && nameStr.endsWith(".json")) {
        onPreset(fileName)
    }
}
