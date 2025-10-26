package io.qent.bro.core.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelsSerializationTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun transportConfig_roundtrip_for_http() {
        val cfg = McpServerConfig(
            id = "s1",
            name = "Server 1",
            transport = TransportConfig.HttpTransport(url = "http://localhost:1234/mcp", headers = mapOf("X" to "y")),
            enabled = true
        )
        val encoded = json.encodeToString(McpServerConfig.serializer(), cfg)
        val decoded = json.decodeFromString(McpServerConfig.serializer(), encoded)
        assertEquals(cfg, decoded)
        // Ensure discriminator is present (implementation detail of kotlinx sealed classes)
        assertTrue(encoded.contains("\"type\":\"http\""))
    }

    @Test
    fun transportConfig_roundtrip_for_stdio() {
        val cfg = McpServerConfig(
            id = "s2",
            name = "Server 2",
            transport = TransportConfig.StdioTransport(command = "node", args = listOf("server.js")),
            enabled = false
        )
        val encoded = json.encodeToString(McpServerConfig.serializer(), cfg)
        val decoded = json.decodeFromString(McpServerConfig.serializer(), encoded)
        assertEquals(cfg, decoded)
        assertTrue(encoded.contains("\"type\":\"stdio\""))
    }

    @Test
    fun preset_and_toolReference_roundtrip() {
        val preset = Preset(
            id = "dev",
            name = "Developer",
            description = "Developer preset",
            tools = listOf(
                ToolReference(serverId = "s1", toolName = "t1", enabled = true),
                ToolReference(serverId = "s2", toolName = "t2", enabled = false)
            )
        )
        val encoded = json.encodeToString(Preset.serializer(), preset)
        val decoded = json.decodeFromString(Preset.serializer(), encoded)
        assertEquals(preset, decoded)
    }
}

