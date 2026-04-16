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
import kotlinx.serialization.json.Json
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

@Suppress("TooManyFunctions")
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
    private val watchJson = Json { ignoreUnknownKeys = true }
    private val watchedDirectories = mutableSetOf<Path>()
    private var watchedMcpFile: Path = baseDir.resolve(JsonConfigurationRepository.DEFAULT_MCP_FILE_NAME).normalize()

    private val dirtyLock = ReentrantLock()
    private var dirtyConfig = false
    private val dirtyPresets = mutableSetOf<Path>()

    fun addObserver(observer: ConfigurationObserver) {
        observers.add(observer)
    }

    fun start() {
        if (watchJob != null) {
            return
        }
        val ws =
            try {
                FileSystems.getDefault().newWatchService()
            } catch (e: IOException) {
                logger?.warn("Failed to initialize watcher: ${e.message}", e)
                null
            } ?: return
        watchService = ws
        watchedDirectories.clear()
        refreshWatchedMcpFile(register = true)
        registerWatchDir(ws, baseDir)
        registerWatchDir(ws, watchedMcpFile.parent)
        if (watchedDirectories.isEmpty()) {
            logger?.warn("No existing directories to watch; watcher idle")
        } else {
            val watched =
                watchedDirectories.joinToString { watchedDir ->
                    watchedDir.toAbsolutePath().toString()
                }
            logger?.info(
                "Watching configuration directories: $watched",
            )
        }
        if (emitInitialState) {
            markConfigDirtyAndSchedule()
        }
        watchJob =
            scope.launch {
                watchLoop(
                    ws = ws,
                    onPathChanged = ::onPathChanged,
                )
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
        watchedDirectories.clear()
    }

    private fun onPathChanged(path: Path) {
        val normalized = path.normalize()
        val configPath = baseDir.resolve(JsonConfigurationRepository.CONFIG_FILE_NAME).normalize()
        val isConfigPath = normalized == configPath
        val isMcpPath = normalized == watchedMcpFile
        val isPresetPath =
            normalized.parent == baseDir &&
                normalized.fileName
                    ?.toString()
                    ?.let { name -> name.startsWith("preset_") && name.endsWith(".json") } == true

        if (isConfigPath) {
            refreshWatchedMcpFile(register = true)
            markConfigDirtyAndSchedule()
        } else if (isMcpPath) {
            markConfigDirtyAndSchedule()
        } else if (isPresetPath) {
            markPresetDirtyAndSchedule(normalized)
        }
    }

    private fun refreshWatchedMcpFile(register: Boolean) {
        val next =
            JsonConfigurationRepository.readConfiguredMcpPath(
                baseDir = baseDir,
                json = watchJson,
                logger = logger,
            )
        watchedMcpFile = next
        if (!register) return
        registerWatchDir(watchService, next.parent)
    }

    private fun registerWatchDir(
        ws: WatchService?,
        dir: Path?,
    ) {
        if (ws == null || dir == null) return
        val normalized = dir.normalize()
        val shouldRegister = Files.exists(normalized) && watchedDirectories.add(normalized)
        if (!shouldRegister) return
        try {
            normalized.register(
                ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE,
            )
        } catch (e: IOException) {
            watchedDirectories.remove(normalized)
            logger?.warn("Failed to register watcher for ${normalized.toAbsolutePath()}: ${e.message}", e)
        }
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
                        .onSuccess { cfg ->
                            refreshWatchedMcpFile(register = true)
                            observers.forEach { it.onConfigurationChanged(cfg) }
                        }.onFailure { ex -> logger?.warn("Failed to reload config: ${ex.message}", ex) }
                }

                snapshot.presetFiles.forEach { file ->
                    if (Files.exists(file)) {
                        val name = file.fileName.toString()
                        val id = name.removePrefix("preset_").removeSuffix(".json")
                        runCatching { repo.loadPreset(id) }
                            .onSuccess { p: Preset -> observers.forEach { it.onPresetChanged(p) } }
                            .onFailure { ex -> logger?.warn("Failed to reload preset '$name': ${ex.message}", ex) }
                    } else {
                        logger?.info("Preset file deleted: ${file.fileName}")
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
    onPathChanged: (Path) -> Unit,
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
                val watchedDir = key.watchable() as? Path
                if (watchedDir != null) {
                    key
                        .pollEvents()
                        .asSequence()
                        .filter { it.kind() != StandardWatchEventKinds.OVERFLOW }
                        .mapNotNull { event -> event.context() as? Path }
                        .forEach { relative ->
                            onPathChanged(watchedDir.resolve(relative).normalize())
                        }
                }
                if (!key.reset()) {
                    running = false
                }
            }
        }
    }
}
