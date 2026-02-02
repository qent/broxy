package io.qent.broxy.core.utils

import java.nio.file.Files
import java.util.Locale
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CommandLocatorTest {
    @Test
    fun resolveCommand_finds_command_in_path() {
        val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
        val tempDir = Files.createTempDirectory("broxy-cmd")
        val commandName = "broxy-test-cmd"
        val fileName = if (isWindows) "$commandName.bat" else commandName
        val commandPath = tempDir.resolve(fileName)
        commandPath.writeText(if (isWindows) "@echo off\r\necho ok\r\n" else "#!/bin/sh\necho ok\n")
        commandPath.toFile().setExecutable(true)

        val resolved = CommandLocator.resolveCommand(commandName, pathOverride = tempDir.toString())

        assertNotNull(resolved)
        assertEquals(commandPath.toAbsolutePath().toString(), resolved)
    }

    @Test
    fun resolveCommand_uses_explicit_path_and_returns_null_when_missing() {
        val tempDir = Files.createTempDirectory("broxy-cmd")
        val commandPath = tempDir.resolve("direct-cmd")
        commandPath.writeText("#!/bin/sh\necho ok\n")
        commandPath.toFile().setExecutable(true)

        val resolved = CommandLocator.resolveCommand(commandPath.toString())
        assertEquals(commandPath.toAbsolutePath().toString(), resolved)

        val missing = CommandLocator.resolveCommand("missing-cmd", pathOverride = tempDir.toString())
        assertNull(missing)
    }

    @Test
    fun resolveCommand_appends_windows_extensions_when_needed() {
        val original = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            val tempDir = Files.createTempDirectory("broxy-cmd-win")
            val commandPath = tempDir.resolve("broxy-tool.EXE")
            commandPath.writeText("@echo off\r\necho ok\r\n")
            commandPath.toFile().setExecutable(true)

            val resolved = CommandLocator.resolveCommand("broxy-tool", pathOverride = tempDir.toString())
            assertNotNull(resolved)
            assertEquals(commandPath.toAbsolutePath().toString(), resolved)

            val explicit = CommandLocator.resolveCommand(tempDir.resolve("broxy-tool").toString())
            assertEquals(commandPath.toAbsolutePath().toString(), explicit)
        } finally {
            if (original != null) {
                System.setProperty("os.name", original)
            }
        }
    }
}
