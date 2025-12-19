package io.qent.broxy.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadlessStdioProxyModeTest {
    @Test
    fun `--stdio-proxy forces headless mode`() {
        val args = arrayOf("--stdio-proxy")
        assertTrue(shouldRunHeadlessStdioProxy(args) { false })
    }

    @Test
    fun `no args and stdin data enables headless mode`() {
        val args = emptyArray<String>()
        assertTrue(shouldRunHeadlessStdioProxy(args) { true })
    }

    @Test
    fun `no args and no stdin data keeps UI mode`() {
        val args = emptyArray<String>()
        assertFalse(shouldRunHeadlessStdioProxy(args) { false })
    }

    @Test
    fun `non-empty args do not trigger auto headless mode`() {
        val args = arrayOf("--some-arg")
        assertFalse(shouldRunHeadlessStdioProxy(args) { true })
    }
}
