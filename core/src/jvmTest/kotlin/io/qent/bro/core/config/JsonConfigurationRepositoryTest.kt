package io.qent.bro.core.config

import io.qent.bro.core.models.McpServersConfig
import io.qent.bro.core.models.TransportConfig
import io.qent.bro.core.utils.ConsoleLogger
import io.qent.bro.core.utils.ConfigurationException
import kotlinx.serialization.json.Json
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class JsonConfigurationRepositoryTest {
    @Test
    fun load_mcp_json_with_env_placeholders() {
        val tmp = Files.createTempDirectory("cfgtest")
        try {
            val mcpJson = """
            {
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
                envResolver = EnvironmentVariableResolver(envProvider = { mapOf("GITHUB_TOKEN" to "t") }),
                logger = ConsoleLogger
            )
            val cfg = repo.loadMcpConfig()
            assertEquals(1, cfg.servers.size)
            val s = cfg.servers.first()
            assertEquals("github", s.id)
            assertTrue(s.transport is TransportConfig.StdioTransport)
            assertEquals("t", s.env["GITHUB_TOKEN"]) // resolved
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun saving_and_listing_presets() {
        val tmp = Files.createTempDirectory("cfgtest2")
        try {
            val repo = JsonConfigurationRepository(baseDir = tmp)
            val preset = io.qent.bro.core.models.Preset(
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
}

