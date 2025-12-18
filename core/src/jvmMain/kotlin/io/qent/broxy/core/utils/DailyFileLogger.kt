package io.qent.broxy.core.utils

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DailyFileLogger(
    private val baseDir: Path
) : Logger {
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lock = Any()

    override fun debug(message: String) = append(LogLevel.DEBUG, message, null)
    override fun info(message: String) = append(LogLevel.INFO, message, null)
    override fun warn(message: String, throwable: Throwable?) = append(LogLevel.WARN, message, throwable)
    override fun error(message: String, throwable: Throwable?) = append(LogLevel.ERROR, message, throwable)

    private fun append(level: LogLevel, message: String, throwable: Throwable?) {
        val now = LocalDateTime.now()
        val date = now.toLocalDate()
        val logLine = formatLine(now, date, level, message, throwable)
        val file = logFilePath(date)

        synchronized(lock) {
            runCatching {
                Files.createDirectories(file.parent)
                Files.write(
                    file,
                    (logLine + System.lineSeparator()).toByteArray(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
                )
            }
        }
    }

    private fun formatLine(
        now: LocalDateTime,
        date: LocalDate,
        level: LogLevel,
        message: String,
        throwable: Throwable?
    ): String {
        val normalized = message
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\n", "\\n")
        val suffix = throwable?.message?.takeIf { it.isNotBlank() }?.let { " (${it.replace("\n", "\\n")})" } ?: ""
        return "${dateFormatter.format(date)} ${timeFormatter.format(now)} ${level.name} $normalized$suffix"
    }

    private fun logFilePath(date: LocalDate): Path {
        val logsDir = baseDir.resolve("logs")
        val fileName = "${dateFormatter.format(date)}.log"
        return logsDir.resolve(fileName)
    }
}

