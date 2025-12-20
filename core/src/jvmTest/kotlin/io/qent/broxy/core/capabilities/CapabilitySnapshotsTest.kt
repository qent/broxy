package io.qent.broxy.core.capabilities

import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CapabilitySnapshotsTest {
    @Test
    fun toSnapshot_expands_tool_prompt_and_resource_details() {
        val schema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        putJsonObject("query") {
                            put("type", "string")
                            put("format", "uuid")
                        }
                        putJsonObject("tags") {
                            put("items", buildJsonObject { put("type", "string") })
                        }
                        putJsonObject("choice") {
                            put(
                                "anyOf",
                                buildJsonArray {
                                    add(buildJsonObject { put("type", "string") })
                                    add(buildJsonObject { put("type", "number") })
                                },
                            )
                        }
                    },
                required = listOf("query", "tags"),
            )
        val capabilities =
            ServerCapabilities(
                tools = listOf(ToolDescriptor(name = "search", inputSchema = schema)),
                prompts =
                    listOf(
                        PromptDescriptor(
                            name = "prompt",
                            description = "desc",
                            arguments = listOf(PromptArgument("topic", null, true, null)),
                        ),
                    ),
                resources =
                    listOf(
                        ResourceDescriptor(
                            name = "doc",
                            uri = "mcp://docs/{id}/page/{page}",
                            description = "",
                            title = "Docs",
                        ),
                    ),
            )

        val snapshot =
            capabilities.toSnapshot(
                McpServerConfig(
                    id = "s1",
                    name = "Server One",
                    transport = TransportConfig.StdioTransport(command = "noop"),
                ),
            )

        val toolArgs = snapshot.tools.single().arguments.associateBy { it.name }
        val queryArg = toolArgs["query"]
        assertNotNull(queryArg)
        assertEquals("string (uuid)", queryArg.type)
        assertEquals(true, queryArg.required)

        val tagsArg = toolArgs["tags"]
        assertNotNull(tagsArg)
        assertEquals("array<string>", tagsArg.type)
        assertEquals(true, tagsArg.required)

        val choiceArg = toolArgs["choice"]
        assertNotNull(choiceArg)
        assertEquals("string | number", choiceArg.type)
        assertEquals(false, choiceArg.required)

        val promptArg = snapshot.prompts.single().arguments.single()
        assertEquals("topic", promptArg.name)
        assertEquals(true, promptArg.required)

        val resource = snapshot.resources.single()
        assertEquals("Docs", resource.description)
        assertEquals(listOf("id", "page"), resource.arguments.map { it.name })
        assertEquals(listOf(true, true), resource.arguments.map { it.required })
    }
}
