package io.qent.broxy.core.utils

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertTrue

class StdErrLoggerTest {
    @Test
    fun writes_log_lines_to_stderr() {
        val original = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer, true, StandardCharsets.UTF_8))
        try {
            StdErrLogger.debug("dbg")
            StdErrLogger.warn("warn", IllegalArgumentException("bad"))
            StdErrLogger.error("err", null)
        } finally {
            System.setErr(original)
        }

        val output = buffer.toString(StandardCharsets.UTF_8)
        assertTrue(output.contains("[DEBUG] dbg"))
        assertTrue(output.contains("[WARN] warn: bad"))
        assertTrue(output.contains("[ERROR] err"))
    }
}
