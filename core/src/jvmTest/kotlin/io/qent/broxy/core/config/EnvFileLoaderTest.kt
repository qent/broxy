package io.qent.broxy.core.config

import io.qent.broxy.core.utils.ConfigurationException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvFileLoaderTest {
    @Test
    fun load_parses_export_comments_and_quoted_values() {
        val base = Files.createTempDirectory("broxy-env-loader")
        val envDir = Files.createDirectories(base.resolve("env"))
        Files.writeString(
            envDir.resolve("server.env"),
            """
            # comment
            export TOKEN=abc
            URL="https://example.com"
            NAME='broxy'
            """.trimIndent(),
        )
        val loader = EnvFileLoader(ConfigErrorHandler(ConfigTestLogger))

        val loaded = loader.load(serverId = "alpha", envFileValue = "env/server.env", mcpFileDirectory = base)

        assertEquals("abc", loaded["TOKEN"])
        assertEquals("https://example.com", loaded["URL"])
        assertEquals("broxy", loaded["NAME"])
    }

    @Test
    fun load_supports_tilde_paths() {
        val originalHome = System.getProperty("user.home")
        val fakeHome = Files.createTempDirectory("broxy-env-home")
        Files.writeString(fakeHome.resolve(".alpha.env"), "TOKEN=abc")
        System.setProperty("user.home", fakeHome.toString())
        try {
            val loader = EnvFileLoader(ConfigErrorHandler(ConfigTestLogger))

            val loaded = loader.load(serverId = "alpha", envFileValue = "~/.alpha.env", mcpFileDirectory = fakeHome)

            assertEquals("abc", loaded["TOKEN"])
        } finally {
            if (originalHome != null) {
                System.setProperty("user.home", originalHome)
            }
        }
    }

    @Test
    fun load_fails_for_missing_file() {
        val base = Files.createTempDirectory("broxy-env-loader")
        val loader = EnvFileLoader(ConfigErrorHandler(ConfigTestLogger))

        assertFailsWith<ConfigurationException> {
            loader.load(serverId = "alpha", envFileValue = "missing.env", mcpFileDirectory = base)
        }
    }
}
