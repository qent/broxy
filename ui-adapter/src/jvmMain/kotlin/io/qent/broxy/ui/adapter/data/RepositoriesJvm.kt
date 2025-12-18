package io.qent.broxy.ui.adapter.data

import io.qent.broxy.core.config.JsonConfigurationRepository
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.CompositeLogger
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.DailyFileLogger
import java.awt.Desktop
import java.nio.file.Path
import java.nio.file.Paths

actual fun provideConfigurationRepository(): ConfigurationRepository = JsonConfigurationRepository()

actual fun provideDefaultLogger(): CollectingLogger {
    val baseDir = defaultConfigDir()
    val sink = CompositeLogger(
        ConsoleLogger,
        DailyFileLogger(baseDir)
    )
    return CollectingLogger(delegate = sink)
}

actual fun openLogsFolder(): Result<Unit> = runCatching {
    val folder = defaultConfigDir().resolve("logs").toFile()
    folder.mkdirs()
    if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().open(folder)
    } else {
        error("Desktop API is not supported")
    }
}

private fun defaultConfigDir(): Path =
    Paths.get(System.getProperty("user.home"), ".config", "broxy")
