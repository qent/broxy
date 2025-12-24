package io.qent.broxy.ui.adapter.data

import io.qent.broxy.core.capabilities.CapabilityCachePersistence
import io.qent.broxy.core.capabilities.FileCapabilityCachePersistence
import io.qent.broxy.core.config.JsonConfigurationRepository
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.AppCacheDir
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.CompositeLogger
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.DailyFileLogger
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

actual fun provideConfigurationRepository(): ConfigurationRepository = JsonConfigurationRepository()

actual fun provideDefaultLogger(): CollectingLogger {
    val baseDir = defaultConfigDir()
    val sink =
        CompositeLogger(
            ConsoleLogger,
            DailyFileLogger(baseDir),
        )
    return CollectingLogger(delegate = sink)
}

actual fun provideCapabilityCachePersistence(logger: CollectingLogger): CapabilityCachePersistence =
    FileCapabilityCachePersistence(AppCacheDir.resolve(), logger)

actual fun openLogsFolder(): Result<Unit> =
    runCatching {
        val folder = defaultConfigDir().resolve("logs").toFile()
        folder.mkdirs()
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(folder)
        } else {
            error("Desktop API is not supported")
        }
    }

actual fun openExternalUrl(url: String): Result<Unit> =
    runCatching {
        if (!Desktop.isDesktopSupported()) {
            error("Desktop browsing is not supported on this platform.")
        }
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            error("Desktop browsing is not supported on this platform.")
        }
        desktop.browse(URI(url))
    }

private fun defaultConfigDir(): Path = Paths.get(System.getProperty("user.home"), ".config", "broxy")
