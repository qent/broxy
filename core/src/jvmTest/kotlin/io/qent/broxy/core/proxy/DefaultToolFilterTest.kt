package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.ToolReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultToolFilterTest {
    @Test
    fun filters_and_prefixes_with_mappings() {
        val all = mapOf(
            "s1" to ServerCapabilities(
                tools = listOf(ToolDescriptor("t1", "d1"), ToolDescriptor("t2")),
                resources = listOf(ResourceDescriptor("r1", uri = "uri1"), ResourceDescriptor("r2", uri = "uri2")),
                prompts = listOf(PromptDescriptor("p1"))
            ),
            "s2" to ServerCapabilities(
                tools = listOf(ToolDescriptor("t3")),
                resources = listOf(ResourceDescriptor("r3", uri = "uri3")),
                prompts = listOf(PromptDescriptor("p2"))
            )
        )
        val preset = Preset(
            id = "id",
            name = "name",
            description = "desc",
            tools = listOf(
                ToolReference("s1", "t1", enabled = true),
                ToolReference("s2", "t3", enabled = true),
                ToolReference("s1", "missing", enabled = true),
            )
        )

        val res = DefaultToolFilter().filter(all, preset)
        val names = res.capabilities.tools.map { it.name }.toSet()
        assertEquals(setOf("s1:t1", "s2:t3"), names)
        assertEquals(names, res.allowedPrefixedTools)
        assertEquals(1, res.missingTools.size)
        assertEquals("missing", res.missingTools.first().toolName)

        // resources/prompts aggregated from in-scope servers (s1 and s2)
        val resNames = res.capabilities.resources.map { it.name }.toSet()
        assertEquals(setOf("r1", "r2", "r3"), resNames)
        val promptNames = res.capabilities.prompts.map { it.name }.toSet()
        assertEquals(setOf("p1", "p2"), promptNames)

        // routing maps
        assertEquals("s1", res.promptServerByName["p1"])
        assertEquals("s2", res.promptServerByName["p2"])
        assertEquals("s1", res.resourceServerByUri["uri1"])
        assertEquals("s2", res.resourceServerByUri["uri3"])
        assertTrue("uri2" in res.resourceServerByUri)
    }
}

