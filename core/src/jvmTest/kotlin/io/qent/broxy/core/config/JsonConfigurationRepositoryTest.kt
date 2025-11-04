package io.qent.broxy.core.config

import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.ConfigurationException
import kotlinx.serialization.json.Json
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class JsonConfigurationRepositoryTest {
    @Test
    fun load_mcp_json_with_env_placeholders() {
        val tmp = Files.createTempDirectory("cfgtest")
        try {
            val mcpJson = """
            {
              "requestTimeoutSeconds": 90,
              "capabilitiesTimeoutSeconds": 25,
              "mcpServers": {
                "github": {
                  "transport": "stdio",
                  "command": "npx",
                  "args": ["@modelcontextprotocol/server-github"],
                  "env": { "GITHUB_TOKEN": "${'$'}{GITHUB_TOKEN}" }
                }
              }
            }
            """.trimIndent()
            tmp.resolve("mcp.json").writeText(mcpJson)

            val repo = JsonConfigurationRepository(
                baseDir = tmp,
                json = Json { ignoreUnknownKeys = true },
                logger = ConsoleLogger,
                envResolver = EnvironmentVariableResolver(envProvider = { mapOf("GITHUB_TOKEN" to "t") })
            )
            val cfg = repo.loadMcpConfig()
            assertEquals(1, cfg.servers.size)
            val s = cfg.servers.first()
            assertEquals("github", s.id)
            assertTrue(s.transport is TransportConfig.StdioTransport)
            assertEquals("t", s.env["GITHUB_TOKEN"]) // resolved
            assertEquals(90, cfg.requestTimeoutSeconds)
            assertEquals(25, cfg.capabilitiesTimeoutSeconds)
            assertTrue(cfg.showTrayIcon)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun saving_and_listing_presets() {
        val tmp = Files.createTempDirectory("cfgtest2")
        try {
            val repo = JsonConfigurationRepository(baseDir = tmp)
            val preset = io.qent.broxy.core.models.Preset(
                id = "developer", name = "Developer Assistant", description = "# Dev", tools = emptyList()
            )
            repo.savePreset(preset)
            val list = repo.listPresets()
            assertEquals(1, list.size)
            val loaded = repo.loadPreset("developer")
            assertEquals("developer", loaded.id)
            assertEquals("Developer Assistant", loaded.name)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalid_config_missing_env_var() {
        val tmp = Files.createTempDirectory("cfgtest3")
        try {
            val mcpJson = """
            {"mcpServers":{"s":{"transport":"http","url":"http://","env":{"X":"${'$'}{MISSING}"}}}}
            """.trimIndent()
            tmp.resolve("mcp.json").writeText(mcpJson)
            val repo = JsonConfigurationRepository(baseDir = tmp, envResolver = EnvironmentVariableResolver(envProvider = { emptyMap() }))
            assertFailsWith<ConfigurationException> { repo.loadMcpConfig() }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun preserves_unbounded_timeout_values() {
        val tmp = Files.createTempDirectory("cfgtest4")
        try {
            val repo = JsonConfigurationRepository(baseDir = tmp)
            val cfg = McpServersConfig(requestTimeoutSeconds = 1_200, capabilitiesTimeoutSeconds = 45, showTrayIcon = false)
            repo.saveMcpConfig(cfg)
            val raw = tmp.resolve("mcp.json").readText()
            assertTrue(raw.contains("\"requestTimeoutSeconds\": 1200"))
            assertTrue(raw.contains("\"capabilitiesTimeoutSeconds\": 45"))
            assertTrue(raw.contains("\"showTrayIcon\": false"))
            val loaded = repo.loadMcpConfig()
            assertEquals(1_200, loaded.requestTimeoutSeconds)
            assertEquals(45, loaded.capabilitiesTimeoutSeconds)
            assertFalse(loaded.showTrayIcon)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
