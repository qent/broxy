package io.qent.broxy.core.proxy.inbound

import kotlin.test.Test
import kotlin.test.assertEquals

class InboundUrlParsingTest {
    @Test
    fun parse_applies_defaults_for_host_port_and_path() {
        val (host, port, path) = parse("http://localhost")
        assertEquals("localhost", host)
        assertEquals(80, port)
        assertEquals("/mcp", path)
    }

    @Test
    fun parse_trims_trailing_slashes() {
        val (host, port, path) = parse("https://example.com/")
        assertEquals("example.com", host)
        assertEquals(443, port)
        assertEquals("", path)
    }

    @Test
    fun normalize_path_returns_display_and_segments() {
        val normalized = normalizePath("/mcp/api/")
        assertEquals("/mcp/api/", normalized.display)
        assertEquals("mcp/api/", normalized.routeSegments)

        val root = normalizePath(" ")
        assertEquals("/", root.display)
        assertEquals("", root.routeSegments)
    }
}
