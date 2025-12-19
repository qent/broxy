package io.qent.broxy.ui.adapter.headless

import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadlessEntrypointJvmTest {
    @org.junit.Test
    fun runStdioProxyBlocksUntilStdinCloses() {
        val tempDir = Files.createTempDirectory("broxy-stdio-proxy-test")
        Files.writeString(
            tempDir.resolve("mcp.json"),
            """{"defaultPresetId":"test","mcpServers":{}}""",
        )
        Files.writeString(
            tempDir.resolve("preset_test.json"),
            """{"id":"test","name":"Test","tools":[]}""",
        )

        val originalIn = System.`in`
        val originalOut = System.out

        val pipedIn = PipedInputStream()
        val pipedOut = PipedOutputStream(pipedIn)
        val sinkOut = PrintStream(ByteArrayOutputStream())

        val executor = Executors.newSingleThreadExecutor()
        try {
            System.setIn(pipedIn)
            System.setOut(sinkOut)

            val future = executor.submit(Callable { runStdioProxy(configDir = tempDir.toString()).isSuccess })

            // It should block waiting for the MCP client / stdio session to end.
            Thread.sleep(100)
            assertFalse(future.isDone, "Expected runStdioProxy to block while stdin is open")

            // Closing stdin should end the session and allow a graceful shutdown.
            pipedOut.close()
            val ok = future.get(5, TimeUnit.SECONDS)
            assertTrue(ok, "Expected runStdioProxy to exit successfully after stdin closes")
        } finally {
            executor.shutdownNow()
            runCatching { pipedOut.close() }
            runCatching { pipedIn.close() }
            runCatching { sinkOut.close() }
            System.setIn(originalIn)
            System.setOut(originalOut)
        }
    }

    @org.junit.Test
    fun runStdioProxyUsesDefaultPresetIdFromMcpJson() {
        val tempDir = Files.createTempDirectory("broxy-stdio-proxy-test")
        Files.writeString(
            tempDir.resolve("mcp.json"),
            """{"defaultPresetId":"test","mcpServers":{}}""",
        )
        Files.writeString(
            tempDir.resolve("preset_test.json"),
            """{"id":"test","name":"Test","tools":[]}""",
        )

        val originalIn = System.`in`
        val originalOut = System.out
        val originalErr = System.err

        val pipedIn = PipedInputStream()
        val pipedOut = PipedOutputStream(pipedIn)
        val sinkOut = PrintStream(ByteArrayOutputStream())
        val capturedErr = ByteArrayOutputStream()
        val sinkErr = PrintStream(capturedErr)

        val executor = Executors.newSingleThreadExecutor()
        try {
            System.setIn(pipedIn)
            System.setOut(sinkOut)
            System.setErr(sinkErr)

            val future = executor.submit(Callable { runStdioProxy(configDir = tempDir.toString()).isSuccess })

            // Wait until startup logs show selected preset.
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline) {
                val text = capturedErr.toString(Charsets.UTF_8)
                if (text.contains("Starting broxy STDIO proxy")) break
                Thread.sleep(10)
            }

            val logs = capturedErr.toString(Charsets.UTF_8)
            assertContains(logs, "presetId='test'")

            pipedOut.close()
            val ok = future.get(5, TimeUnit.SECONDS)
            assertTrue(ok, "Expected runStdioProxy to exit successfully after stdin closes")
        } finally {
            executor.shutdownNow()
            runCatching { pipedOut.close() }
            runCatching { pipedIn.close() }
            runCatching { sinkOut.close() }
            runCatching { sinkErr.close() }
            System.setIn(originalIn)
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
