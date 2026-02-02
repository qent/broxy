package io.qent.broxy.core.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Annotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

class DescriptorEqualityTest {
    @Test
    fun tool_descriptor_compares_all_fields() {
        val schema = ToolSchema(properties = JsonObject(emptyMap()), required = emptyList())
        val base =
            ToolDescriptor(
                name = "tool",
                description = "desc",
                title = "title",
                inputSchema = schema,
                outputSchema = schema,
                annotations = ToolAnnotations(),
            )

        val variants =
            listOf(
                base.copy(name = "other"),
                base.copy(description = "changed"),
                base.copy(title = "new"),
                base.copy(inputSchema = null),
                base.copy(outputSchema = null),
                base.copy(annotations = null),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }

    @Test
    fun resource_descriptor_compares_all_fields() {
        val base =
            ResourceDescriptor(
                name = "res",
                uri = "uri://res",
                description = "desc",
                mimeType = "text/plain",
                title = "Title",
                size = 10L,
                annotations = Annotations(),
            )

        val variants =
            listOf(
                base.copy(name = "other"),
                base.copy(uri = "uri://other"),
                base.copy(description = "changed"),
                base.copy(mimeType = "application/json"),
                base.copy(title = "Other"),
                base.copy(size = 11L),
                base.copy(annotations = null),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }

    @Test
    fun server_capabilities_compares_collections() {
        val base =
            ServerCapabilities(
                tools = listOf(ToolDescriptor(name = "t1")),
                resources = listOf(ResourceDescriptor(name = "r1")),
                prompts = listOf(PromptDescriptor(name = "p1")),
            )
        val variants =
            listOf(
                base.copy(tools = listOf(ToolDescriptor(name = "t2"))),
                base.copy(resources = listOf(ResourceDescriptor(name = "r2"))),
                base.copy(prompts = listOf(PromptDescriptor(name = "p2"))),
            )

        variants.forEach { variant ->
            assertTrue(base != variant)
        }
    }
}
