package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NamespaceManagerTest {
    private val ns = DefaultNamespaceManager()

    @Test
    fun prefix_and_parse_roundtrip() {
        val prefixed = ns.prefixToolName("s1", "echo")
        assertEquals("s1:echo", prefixed)
        val (sid, tool) = ns.parsePrefixedToolName(prefixed)
        assertEquals("s1", sid)
        assertEquals("echo", tool)
    }

    @Test
    fun parse_invalid_format_throws() {
        assertFailsWith<IllegalArgumentException> { ns.parsePrefixedToolName("invalid") }
        assertFailsWith<IllegalArgumentException> { ns.parsePrefixedToolName(":bad") }
        assertFailsWith<IllegalArgumentException> { ns.parsePrefixedToolName("bad:") }
    }

    @Test
    fun prefix_all_capabilities_prefixes_tools_and_keeps_others() {
        val all = mapOf(
            "s1" to ServerCapabilities(
                tools = listOf(ToolDescriptor("t1", "d1")),
                resources = listOf(ResourceDescriptor("r1", uri = "u1")),
                prompts = listOf(PromptDescriptor("p1"))
            ),
            "s2" to ServerCapabilities(tools = listOf(ToolDescriptor("t2")))
        )

        val prefixed = ns.prefixAllCapabilities(all)
        val toolNames = prefixed.tools.map { it.name }.toSet()
        assertEquals(setOf("s1:t1", "s2:t2"), toolNames)
        // ensure descriptions preserved for tools
        val d1 = prefixed.tools.first { it.name == "s1:t1" }.description
        assertEquals("d1", d1)
        // prompts/resources are concatenated
        assertEquals(setOf("p1"), prefixed.prompts.map { it.name }.toSet())
        assertEquals(setOf("r1"), prefixed.resources.map { it.name }.toSet())
    }
}

