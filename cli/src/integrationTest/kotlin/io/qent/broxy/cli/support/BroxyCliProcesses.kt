package io.qent.broxy.cli.support

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val MAX_LOG_LINES = 500 // Keep integration test process logs bounded in memory.

internal object BroxyCliProcesses {
    fun startCliProcess(command: List<String>): RunningProcess {
        val process =
            ProcessBuilder(command)
                .directory(BroxyCliIntegrationFiles.jarPath().parent?.toFile())
                .redirectErrorStream(true)
                .start()
        BroxyCliIntegrationConfig.log("Started broxy CLI process pid=${process.pid()}")
        return RunningProcess(process)
    }

    fun startTestServerProcess(command: List<String>): RunningProcess {
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        BroxyCliIntegrationConfig.log("Started test MCP server process pid=${process.pid()}")
        return RunningProcess(process)
    }
}

internal class RunningProcess(
    private val process: Process,
) : AutoCloseable {
    private val collector = ProcessOutputCollector(process)

    fun logs(): String = collector.snapshot()

    fun isAlive(): Boolean = process.isAlive

    override fun close() {
        BroxyCliIntegrationConfig.log("Stopping process pid=${process.pid()}")
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        collector.close()
    }
}

internal class ProcessOutputCollector(
    process: Process,
) : AutoCloseable {
    private val lines = ArrayDeque<String>(MAX_LOG_LINES)
    private val readerThread =
        thread(name = "broxy-cli-it") {
            process.inputStream.bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    synchronized(lines) {
                        if (lines.size == MAX_LOG_LINES) {
                            lines.removeFirst()
                        }
                        lines.addLast(line)
                    }
                }
            }
        }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    override fun close() {
        readerThread.interrupt()
        runCatching { readerThread.join(500) }
    }
}
