package io.qent.broxy.cli.support

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.test.fail

internal object BroxyCliIntegrationFiles {
    fun prepareConfigDir(httpServerUrl: String): Path {
        val dir = Files.createTempDirectory("broxy-cli-it-")
        writeTestServerConfig(dir.resolve("mcp.json"), httpServerUrl)
        copyResource("/integration/preset_test.json", dir.resolve("preset_test.json"))
        BroxyCliIntegrationConfig.log("Wrote integration config to ${dir.pathString}")
        return dir
    }

    fun buildCliCommand(configDir: Path, inboundArgs: List<String>): List<String> = buildList {
        add(javaExecutable())
        add("-jar")
        add(jarPath().pathString)
        add("proxy")
        add("--config-dir")
        add(configDir.pathString)
        add("--preset-id")
        add(BroxyCliIntegrationConfig.PRESET_ID)
        add("--log-level")
        add(BroxyCliIntegrationConfig.CLI_LOG_LEVEL)
        addAll(inboundArgs)
    }

    fun resolveTestServerCommand(): String {
        val property = BroxyCliIntegrationConfig.TEST_SERVER_HOME_PROPERTY
        val home = System.getProperty(property)?.takeIf { it.isNotBlank() }
            ?: fail("System property '$property' must point to the test MCP server install dir")
        val binDir = Paths.get(home, "bin")
        val scriptName = if (isWindows()) "test-mcp-server.bat" else "test-mcp-server"
        val path = binDir.resolve(scriptName)
        if (!path.exists()) {
            fail("Test MCP server executable not found at ${path.pathString}")
        }
        BroxyCliIntegrationConfig.log("Using test MCP server command ${path.pathString}")
        return path.toAbsolutePath().pathString
    }

    private fun writeTestServerConfig(destination: Path, httpServerUrl: String) {
        val template = readResource("/integration/mcp.json")
        val command = jsonEscape(resolveTestServerCommand())
        val resolved = template
            .replace(BroxyCliIntegrationConfig.TEST_SERVER_COMMAND_PLACEHOLDER, command)
            .replace(BroxyCliIntegrationConfig.TEST_SERVER_HTTP_URL_PLACEHOLDER, jsonEscape(httpServerUrl))
        Files.writeString(destination, resolved)
    }

    private fun readResource(resource: String): String {
        val stream: InputStream = requireNotNull(javaClass.getResourceAsStream(resource)) {
            "Missing classpath resource $resource"
        }
        return stream.use { it.bufferedReader().readText() }
    }

    private fun copyResource(resource: String, destination: Path) {
        val stream: InputStream = requireNotNull(javaClass.getResourceAsStream(resource)) {
            "Missing classpath resource $resource"
        }
        stream.use {
            Files.copy(it, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    private fun javaExecutable(): String {
        val javaHome = System.getProperty("java.home") ?: return "java"
        val exeName = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        val candidate = Paths.get(javaHome, "bin", exeName)
        return if (candidate.exists()) candidate.pathString else "java"
    }

    fun jarPath(): Path = Paths.get(
        System.getProperty("broxy.cliJar")
            ?: fail("System property 'broxy.cliJar' must point to the assembled CLI jar")
    ).toAbsolutePath()

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}
