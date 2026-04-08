package io.qent.broxy.core.mcp.auth

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthModelsSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun protected_resource_metadata_accepts_string_resource() {
        val metadata =
            json.decodeFromString<ProtectedResourceMetadata>(
                """{"resource":"https://mcp.example.com","authorization_servers":["https://auth.example.com"]}""",
            )

        assertEquals("https://mcp.example.com", metadata.resource)
    }

    @Test
    fun protected_resource_metadata_accepts_array_resource() {
        val payload =
            """
            {
              "resource": [" ", "https://mcp.example.com", "https://backup.example.com"],
              "authorization_servers": ["https://auth.example.com"]
            }
            """.trimIndent()
        val metadata =
            json.decodeFromString<ProtectedResourceMetadata>(
                payload,
            )

        assertEquals("https://mcp.example.com", metadata.resource)
    }
}
