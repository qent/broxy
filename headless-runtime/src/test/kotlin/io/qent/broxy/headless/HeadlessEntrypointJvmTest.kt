package io.qent.broxy.headless

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

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
            try {
                future.get(100, TimeUnit.MILLISECONDS)
                fail("Expected runStdioProxy to block while stdin is open")
            } catch (_: TimeoutException) {
                assertFalse(future.isDone, "Expected runStdioProxy to block while stdin is open")
            }

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

        val expectedPresetLog = "presetId='test'"
        val pipedIn = PipedInputStream()
        val pipedOut = PipedOutputStream(pipedIn)
        val sinkOut = PrintStream(ByteArrayOutputStream())
        val capturedErr = WatchedOutputStream(expectedPresetLog)
        val sinkErr = PrintStream(capturedErr)

        val executor = Executors.newSingleThreadExecutor()
        try {
            System.setIn(pipedIn)
            System.setOut(sinkOut)
            System.setErr(sinkErr)

            val future = executor.submit(Callable { runStdioProxy(configDir = tempDir.toString()).isSuccess })

            capturedErr.awaitText(5, TimeUnit.SECONDS)

            val logs = capturedErr.text()
            assertContains(logs, expectedPresetLog)

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

    private class WatchedOutputStream(
        private val watchedText: String,
    ) : OutputStream() {
        private val delegate = ByteArrayOutputStream()
        private val seen = CountDownLatch(1)

        override fun write(b: Int) {
            synchronized(delegate) {
                delegate.write(b)
                countDownIfSeen()
            }
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            synchronized(delegate) {
                delegate.write(b, off, len)
                countDownIfSeen()
            }
        }

        fun awaitText(
            timeout: Long,
            unit: TimeUnit,
        ) {
            seen.await(timeout, unit)
        }

        fun text(): String =
            synchronized(delegate) {
                delegate.toString(Charsets.UTF_8)
            }

        private fun countDownIfSeen() {
            if (delegate.toString(Charsets.UTF_8).contains(watchedText)) {
                seen.countDown()
            }
        }
    }
}
