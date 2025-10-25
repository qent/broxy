package io.qent.broxy.cli.commands

import io.qent.broxy.cli.support.CliLoggerFactory
import io.qent.broxy.core.config.ConfigurationObserver
import io.qent.broxy.core.config.ConfigurationWatcher
import io.qent.broxy.core.config.EnvironmentVariableResolver
import io.qent.broxy.core.config.JsonConfigurationRepository
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.proxy.runtime.ProxyLifecycle
import io.qent.broxy.core.proxy.runtime.createProxyController
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

internal data class ProxyCommandRunnerDependencies(
    val loggerFactory: (LogLevelOption, Path) -> Logger = CliLoggerFactory::create,
    val proxyControllerFactory: (CollectingLogger, String) -> io.qent.broxy.core.proxy.runtime.ProxyController =
        { logger, baseDir ->
            createProxyController(logger, baseDir)
        },
    val repositoryFactory: (Path, Logger) -> JsonConfigurationRepository = { baseDir, logger ->
        JsonConfigurationRepository(
            baseDir = baseDir,
            json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
            logger = logger,
            envResolver = EnvironmentVariableResolver(logger = logger),
        )
    },
    val watcherFactory: (Path, ConfigurationRepository, Logger, CoroutineScope) -> ConfigurationWatcher =
        { baseDir, repo, logger, scope ->
            ConfigurationWatcher(
                baseDir = baseDir,
                repo = repo,
                logger = logger,
                scope = scope,
                emitInitialState = false,
            )
        },
    val scopeFactory: () -> CoroutineScope = {
        CoroutineScope(Dispatchers.Default + SupervisorJob())
    },
    val shutdownHookInstaller: (Thread) -> Unit = {
        Runtime.getRuntime().addShutdownHook(it)
    },
)

internal open class ProxyCommandRunner(
    private val dependencies: ProxyCommandRunnerDependencies = ProxyCommandRunnerDependencies(),
) {
    open fun run(options: CliOptions) {
        val baseDir = Paths.get(options.configDir.absolutePath)
        val logger = dependencies.loggerFactory(options.logLevel, baseDir)
        val scope = dependencies.scopeFactory()
        val shutdownSignal = CompletableDeferred<Unit>()
        val shutdownOnce = AtomicBoolean(false)
        var watcher: ConfigurationWatcher? = null
        var started = false

        val collectingLogger = CollectingLogger(delegate = logger)
        val proxyController = dependencies.proxyControllerFactory(collectingLogger, baseDir.toString())
        val proxyLifecycle = ProxyLifecycle(proxyController, logger)

        fun shutdown() {
            if (!shutdownOnce.compareAndSet(false, true)) {
                return
            }
            if (started) {
                logger.info("Shutting down proxy...")
            }
            watcher?.stop()
            proxyLifecycle.stop()
            scope.cancel()
        }
        try {
            val repo = dependencies.repositoryFactory(baseDir, logger)
            var serversCfg = repo.loadMcpConfig()
            var currentPreset = repo.loadPreset(options.presetId)
            val inboundTransport = options.toInboundTransport()

            val startResult = proxyLifecycle.start(serversCfg, currentPreset, inboundTransport)
            if (startResult.isFailure) {
                val exception = startResult.exceptionOrNull()
                val message = exception?.message ?: "Failed to start proxy"
                logger.error(message, exception)
                shutdown()
                throw IllegalStateException(message, exception)
            }
            started = true

            val activeWatcher = dependencies.watcherFactory(baseDir, repo, logger, scope)
            watcher = activeWatcher
            activeWatcher.addObserver(
                object : ConfigurationObserver {
                    override fun onConfigurationChanged(config: McpServersConfig) {
                        logger.info("Configuration changed; updating downstream connections")
                        val result = proxyLifecycle.updateServers(config)
                        if (result.isSuccess) {
                            serversCfg = config
                        } else {
                            val msg = result.exceptionOrNull()?.message ?: "Failed to apply new config"
                            logger.error(msg, result.exceptionOrNull())
                        }
                    }

                    override fun onPresetChanged(preset: Preset) {
                        logger.info("Preset changed to '${preset.id}'; applying to proxy")
                        val result = proxyLifecycle.applyPreset(preset)
                        if (result.isSuccess) {
                            currentPreset = preset
                        } else {
                            val msg = result.exceptionOrNull()?.message ?: "Failed to apply preset"
                            logger.error(msg, result.exceptionOrNull())
                        }
                    }
                },
            )
            activeWatcher.start()

            dependencies.shutdownHookInstaller(
                Thread {
                    shutdownSignal.complete(Unit)
                    shutdown()
                },
            )

            runBlocking { shutdownSignal.await() }
            shutdown()
        } finally {
            shutdown()
        }
    }
}
