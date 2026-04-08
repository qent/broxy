package io.qent.broxy.ui

import java.awt.EventQueue
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val WINDOWS_ACTIVATED_MARKER = "BROXY_ACTIVATED"
private const val WINDOWS_NOTIFICATION_APP_ID = "io.qent.broxy"
private const val WINDOWS_NOTIFICATION_WAIT_MILLIS = 60_000L
private const val WINDOWS_SETTING_QUERY_TIMEOUT_SECONDS = 15L
private const val LINUX_NOTIFICATION_DEFAULT_ACTION = "default"

internal interface DesktopSystemNotificationCenter {
    fun requestPermissionProbeIfNeeded(
        title: String,
        body: String,
    )

    fun showAgentRunNotification(
        agentId: String,
        title: String,
        body: String,
    )

    fun dispose()
}

internal fun createDesktopSystemNotificationCenter(
    onAgentRunNotificationActivated: (String) -> Unit,
    onPermissionProbeActivated: () -> Unit,
): DesktopSystemNotificationCenter =
    when {
        isMacOsDesktop() ->
            MacOsNotificationCenter(
                onAgentRunNotificationActivated = onAgentRunNotificationActivated,
                onPermissionProbeActivated = onPermissionProbeActivated,
            )

        isWindowsDesktop() ->
            WindowsToastNotificationCenter(
                onAgentRunNotificationActivated = onAgentRunNotificationActivated,
            )

        isLinuxDesktop() ->
            LinuxNotifySendNotificationCenter(
                onAgentRunNotificationActivated = onAgentRunNotificationActivated,
            )

        else -> NoopDesktopSystemNotificationCenter
    }

internal fun isMacOsDesktop(): Boolean = System.getProperty("os.name")?.contains("Mac", ignoreCase = true) == true

internal fun isWindowsDesktop(): Boolean = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true

internal fun isLinuxDesktop(): Boolean {
    val os = System.getProperty("os.name").orEmpty()
    return os.contains("Linux", ignoreCase = true) || os.contains("Unix", ignoreCase = true)
}

private object NoopDesktopSystemNotificationCenter : DesktopSystemNotificationCenter {
    override fun requestPermissionProbeIfNeeded(
        title: String,
        body: String,
    ) {
    }

    override fun showAgentRunNotification(
        agentId: String,
        title: String,
        body: String,
    ) {
    }

    override fun dispose() {
    }
}

private class WindowsToastNotificationCenter(
    private val onAgentRunNotificationActivated: (String) -> Unit,
) : DesktopSystemNotificationCenter {
    private val executor = Executors.newCachedThreadPool()
    private val shellCommand = resolvePowerShellCommand()
    private val permissionProbeHandled = AtomicBoolean(false)

    override fun requestPermissionProbeIfNeeded(
        title: String,
        body: String,
    ) {
        if (!permissionProbeHandled.compareAndSet(false, true)) {
            return
        }
        val shell = shellCommand ?: return
        executor.execute {
            when (readWindowsToastPermissionStatus(shell)) {
                WindowsToastPermissionStatus.DISABLED_FOR_APPLICATION,
                WindowsToastPermissionStatus.DISABLED_FOR_USER,
                WindowsToastPermissionStatus.DISABLED_BY_GROUP_POLICY,
                -> openWindowsNotificationSettings()

                else -> Unit
            }
        }
    }

    override fun showAgentRunNotification(
        agentId: String,
        title: String,
        body: String,
    ) {
        val shell = shellCommand ?: return
        executor.execute {
            val process =
                runCatching {
                    ProcessBuilder(
                        shell,
                        "-NoProfile",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-Command",
                        buildWindowsToastScript(title, body),
                    ).start()
                }.getOrNull() ?: return@execute
            val output =
                runCatching {
                    process.inputStream.bufferedReader().use { it.readText() }
                }.getOrDefault("")
            runCatching { process.waitFor(70, TimeUnit.SECONDS) }
            if (output.lineSequence().any { it.trim() == WINDOWS_ACTIVATED_MARKER }) {
                EventQueue.invokeLater {
                    onAgentRunNotificationActivated(agentId)
                }
            }
        }
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    private fun resolvePowerShellCommand(): String? =
        sequenceOf("pwsh", "powershell").firstOrNull { command ->
            runCatching {
                val process =
                    ProcessBuilder(
                        command,
                        "-NoProfile",
                        "-NonInteractive",
                        "-Command",
                        "exit 0",
                    ).start()
                process.waitFor(5, TimeUnit.SECONDS)
                process.exitValue() == 0
            }.getOrDefault(false)
        }

    private fun buildWindowsToastScript(
        title: String,
        body: String,
    ): String {
        val p = '$'
        val encodedTitle = Base64.getEncoder().encodeToString(title.toByteArray(StandardCharsets.UTF_8))
        val encodedBody = Base64.getEncoder().encodeToString(body.toByteArray(StandardCharsets.UTF_8))
        return buildString {
            appendLine("${p}ErrorActionPreference = 'Stop'")
            appendLine("Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null")
            appendLine(
                "[Windows.UI.Notifications.ToastNotificationManager, " +
                    "Windows.UI.Notifications, ContentType = WindowsRuntime] > ${p}null",
            )
            appendLine(
                "[Windows.Data.Xml.Dom.XmlDocument, " +
                    "Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] > ${p}null",
            )
            appendLine(
                "function Decode-Broxy([string]${p}value) { " +
                    "[System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String(${p}value)) }",
            )
            appendLine("function Escape-BroxyXml([string]${p}value) { [Security.SecurityElement]::Escape(${p}value) }")
            appendLine("${p}title = Escape-BroxyXml (Decode-Broxy '$encodedTitle')")
            appendLine("${p}body = Escape-BroxyXml (Decode-Broxy '$encodedBody')")
            appendLine(
                "${p}xml = \"<toast><visual><binding template='ToastGeneric'>" +
                    "<text>${p}title</text><text>${p}body</text></binding></visual></toast>\"",
            )
            appendLine("${p}doc = [Windows.Data.Xml.Dom.XmlDocument]::new()")
            appendLine("${p}doc.LoadXml(${p}xml)")
            appendLine("${p}toast = [Windows.UI.Notifications.ToastNotification]::new(${p}doc)")
            appendLine("${p}script:broxyToastActivated = ${p}false")
            appendLine(
                "${p}subscription = Register-ObjectEvent -InputObject ${p}toast " +
                    "-EventName Activated -Action { ${p}script:broxyToastActivated = ${p}true }",
            )
            appendLine(
                "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier" +
                    "('$WINDOWS_NOTIFICATION_APP_ID').Show(${p}toast)",
            )
            appendLine("${p}deadline = [DateTime]::UtcNow.AddMilliseconds($WINDOWS_NOTIFICATION_WAIT_MILLIS)")
            appendLine(
                "while ([DateTime]::UtcNow -lt ${p}deadline -and -not ${p}script:broxyToastActivated) " +
                    "{ Start-Sleep -Milliseconds 200 }",
            )
            appendLine("if (${p}script:broxyToastActivated) { [Console]::Out.WriteLine('$WINDOWS_ACTIVATED_MARKER') }")
            appendLine("if (${p}null -ne ${p}subscription) {")
            appendLine("  Unregister-Event -SourceIdentifier ${p}subscription.Name -ErrorAction SilentlyContinue")
            appendLine("  ${p}subscription | Remove-Job -Force -ErrorAction SilentlyContinue")
            appendLine("}")
        }
    }

    private fun readWindowsToastPermissionStatus(shell: String): WindowsToastPermissionStatus {
        val p = '$'
        val script =
            buildString {
                appendLine("${p}ErrorActionPreference = 'Stop'")
                appendLine("Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null")
                appendLine(
                    "[Windows.UI.Notifications.ToastNotificationManager, " +
                        "Windows.UI.Notifications, ContentType = WindowsRuntime] > ${p}null",
                )
                appendLine(
                    "${p}status = [Windows.UI.Notifications.ToastNotificationManager]" +
                        "::CreateToastNotifier('$WINDOWS_NOTIFICATION_APP_ID').Setting.ToString()",
                )
                appendLine("[Console]::Out.WriteLine(${p}status)")
            }
        val process =
            runCatching {
                ProcessBuilder(
                    shell,
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    script,
                ).start()
            }.getOrNull() ?: return WindowsToastPermissionStatus.UNKNOWN
        val output =
            runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
        runCatching { process.waitFor(WINDOWS_SETTING_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        return WindowsToastPermissionStatus.fromOutput(output)
    }

    private fun openWindowsNotificationSettings() {
        runCatching {
            ProcessBuilder(
                "cmd",
                "/c",
                "start",
                "",
                "ms-settings:notifications",
            ).start()
        }
    }
}

private enum class WindowsToastPermissionStatus {
    ENABLED,
    DISABLED_FOR_APPLICATION,
    DISABLED_FOR_USER,
    DISABLED_BY_GROUP_POLICY,
    DISABLED_BY_MANIFEST,
    UNKNOWN,
    ;

    companion object {
        fun fromOutput(raw: String): WindowsToastPermissionStatus {
            val value =
                raw
                    .trim()
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            return when {
                value.equals("Enabled", ignoreCase = true) -> ENABLED
                value.equals("DisabledForApplication", ignoreCase = true) -> DISABLED_FOR_APPLICATION
                value.equals("DisabledForUser", ignoreCase = true) -> DISABLED_FOR_USER
                value.equals("DisabledByGroupPolicy", ignoreCase = true) -> DISABLED_BY_GROUP_POLICY
                value.equals("DisabledByManifest", ignoreCase = true) -> DISABLED_BY_MANIFEST
                else -> UNKNOWN
            }
        }
    }
}

private class LinuxNotifySendNotificationCenter(
    private val onAgentRunNotificationActivated: (String) -> Unit,
) : DesktopSystemNotificationCenter {
    private val executor = Executors.newCachedThreadPool()
    private val supported = supportsNotifySendActions()

    override fun requestPermissionProbeIfNeeded(
        title: String,
        body: String,
    ) {
        // Linux desktop notifications do not have a unified runtime permission prompt.
    }

    override fun showAgentRunNotification(
        agentId: String,
        title: String,
        body: String,
    ) {
        if (!supported) {
            return
        }
        executor.execute {
            val process =
                runCatching {
                    ProcessBuilder(
                        "notify-send",
                        "--app-name=Broxy",
                        "--action=$LINUX_NOTIFICATION_DEFAULT_ACTION=Open",
                        "--wait",
                        "--",
                        title,
                        body,
                    ).start()
                }.getOrNull() ?: return@execute
            val output =
                runCatching {
                    process.inputStream.bufferedReader().use { it.readText() }
                }.getOrDefault("")
            runCatching { process.waitFor(70, TimeUnit.SECONDS) }
            if (output.lineSequence().map { it.trim() }.any { it == LINUX_NOTIFICATION_DEFAULT_ACTION }) {
                EventQueue.invokeLater {
                    onAgentRunNotificationActivated(agentId)
                }
            }
        }
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    private fun supportsNotifySendActions(): Boolean =
        runCatching {
            val process = ProcessBuilder("notify-send", "--help").start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor(5, TimeUnit.SECONDS)
            val output = stdout + "\n" + stderr
            output.contains("--action")
        }.getOrDefault(false)
}
