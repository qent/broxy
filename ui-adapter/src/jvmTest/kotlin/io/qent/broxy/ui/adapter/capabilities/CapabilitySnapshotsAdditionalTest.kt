package io.qent.broxy.ui.adapter.capabilities

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilitySnapshotsAdditionalTest {
    @Test
    fun to_snapshot_handles_schema_variants_and_resource_fallbacks() {
        val snapshot = buildCapabilities().toSnapshot(buildConfig())

        assertToolArguments(snapshot)
        assertResourceFallbacks(snapshot)
    }

    private fun buildCapabilities(): ServerCapabilities =
        ServerCapabilities(
            tools = listOf(ToolDescriptor(name = "t1", inputSchema = buildSchema())),
            resources =
                listOf(
                    ResourceDescriptor(
                        name = "doc",
                        uri = "mcp://docs/{id}",
                        description = null,
                        title = null,
                    ),
                    ResourceDescriptor(
                        name = "plain",
                        uri = null,
                        description = null,
                        title = null,
                    ),
                ),
        )

    private fun buildSchema(): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    putJsonObject("enumProp") {
                        put(
                            "enum",
                            buildJsonArray {
                                add(JsonPrimitive("a"))
                                add(JsonPrimitive("b"))
                            },
                        )
                    }
                    putJsonObject("arrayType") {
                        put(
                            "type",
                            buildJsonArray {
                                add(JsonPrimitive("string"))
                                add(JsonPrimitive("number"))
                            },
                        )
                    }
                    putJsonObject("itemsProp") {
                        putJsonObject("items") {
                            put("type", "string")
                        }
                    }
                    putJsonObject("oneOfProp") {
                        put(
                            "oneOf",
                            buildJsonArray {
                                add(buildJsonObject { put("type", "string") })
                                add(buildJsonObject { put("type", "number") })
                            },
                        )
                    }
                    putJsonObject("allOfProp") {
                        put(
                            "allOf",
                            buildJsonArray {
                                add(buildJsonObject { put("type", "string") })
                                add(buildJsonObject { put("type", "number") })
                            },
                        )
                    }
                    putJsonObject("unknownProp") {
                    }
                },
        )

    private fun buildConfig(): McpServerConfig =
        McpServerConfig(
            id = "s1",
            name = "Server One",
            transport = TransportConfig.StdioTransport(command = "noop"),
        )

    private fun assertToolArguments(snapshot: ServerCapsSnapshot) {
        val args =
            snapshot.tools
                .single()
                .arguments
                .associateBy { it.name }
        assertEquals("enum", args.getValue("enumProp").type)
        assertEquals("string | number", args.getValue("arrayType").type)
        assertEquals("array<string>", args.getValue("itemsProp").type)
        assertEquals("string | number", args.getValue("oneOfProp").type)
        assertEquals("string & number", args.getValue("allOfProp").type)
        assertEquals("unspecified", args.getValue("unknownProp").type)
    }

    private fun assertResourceFallbacks(snapshot: ServerCapsSnapshot) {
        val resourceByName = snapshot.resources.associateBy { it.name }
        assertTrue(resourceByName.getValue("doc").description.contains("mcp://docs/"))
        assertEquals("", resourceByName.getValue("plain").description)
    }
}
