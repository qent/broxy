package io.qent.broxy.agents.codex.runtime

import io.qent.broxy.agents.AgentCodexConfig
import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.agents.DEFAULT_CODEX_COMMAND
import io.qent.broxy.agents.DEFAULT_CODEX_MODEL
import io.qent.broxy.agents.DEFAULT_OPENAI_BASE_URL
import io.qent.broxy.core.utils.CommandLocator
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.core.utils.UserPathResolver
import java.io.File

internal data class CodexCommandEnvironment(
    val resolvedCommand: String,
    val execCommand: List<String>,
    val launchPrompt: String,
    val environment: MutableMap<String, String>,
    val resolvedUserPath: String?,
    val openAiBaseUrlOverride: String?,
    val hadOpenAiApiKey: Boolean,
    val hadCodexApiKey: Boolean,
    val hadInheritedOpenAiBaseUrl: Boolean,
    val codexHome: String,
)

internal data class CodexCommandEnvironmentRequest(
    val configuredCommand: String,
    val codex: AgentCodexConfig,
    val fileSystemAccess: AgentFileSystemAccess,
    val workingDirectory: File,
    val endpointUrl: String,
    val systemPrompt: String,
    val userPrompt: String,
    val openAiBaseUrl: String?,
)

internal class CodexCommandEnvironmentBuilder(
    private val logger: Logger,
) {
    fun build(request: CodexCommandEnvironmentRequest): CodexCommandEnvironment {
        val resolvedUserPath = UserPathResolver.resolve(logger)
        val resolvedCommand = resolveCommand(request.configuredCommand, resolvedUserPath)
        val environment = buildCodexEnvironment(resolvedUserPath)

        val hadOpenAiApiKey = environment.hasNonBlankKeyIgnoreCase("OPENAI_API_KEY")
        val hadCodexApiKey = environment.hasNonBlankKeyIgnoreCase("CODEX_API_KEY")
        val hadInheritedOpenAiBaseUrl = environment.hasNonBlankKeyIgnoreCase("OPENAI_BASE_URL")

        environment.removeKeyIgnoreCase("OPENAI_API_KEY")
        environment.removeKeyIgnoreCase("CODEX_API_KEY")
        environment.removeKeyIgnoreCase("OPENAI_BASE_URL")
        environment.putKeyIgnoreCase("CODEX_INTERNAL_ORIGINATOR_OVERRIDE", "broxy_agent")

        val openAiBaseUrlOverride = resolveOpenAiBaseUrlOverride(request.openAiBaseUrl)
        openAiBaseUrlOverride?.let { baseUrl ->
            environment.putKeyIgnoreCase("OPENAI_BASE_URL", baseUrl)
        }

        val codexHome = resolveCodexHome(environment)
        val launchPrompt =
            buildLaunchPrompt(
                systemPrompt = request.systemPrompt,
                userPrompt = request.userPrompt,
            )
        val execCommand =
            buildExecCommandArgs(
                command = resolvedCommand,
                config = request.codex,
                fileSystemAccess = request.fileSystemAccess,
                workingDirectory = request.workingDirectory.path,
                endpointUrl = request.endpointUrl,
            ) + launchPrompt

        return CodexCommandEnvironment(
            resolvedCommand = resolvedCommand,
            execCommand = execCommand,
            launchPrompt = launchPrompt,
            environment = environment,
            resolvedUserPath = resolvedUserPath,
            openAiBaseUrlOverride = openAiBaseUrlOverride,
            hadOpenAiApiKey = hadOpenAiApiKey,
            hadCodexApiKey = hadCodexApiKey,
            hadInheritedOpenAiBaseUrl = hadInheritedOpenAiBaseUrl,
            codexHome = codexHome,
        )
    }

    private fun resolveCommand(
        configuredCommand: String,
        resolvedUserPath: String?,
    ): String {
        val normalized = configuredCommand.trim().ifBlank { DEFAULT_CODEX_COMMAND }
        return CommandLocator
            .resolveCommand(
                command = normalized,
                pathOverride = resolvedUserPath,
                logger = logger,
            ) ?: error(
            "Codex command '$normalized' was not found in PATH. " +
                "Set an absolute path in Agent Settings -> Codex command.",
        )
    }

    private fun buildExecCommandArgs(
        command: String,
        config: AgentCodexConfig,
        fileSystemAccess: AgentFileSystemAccess,
        workingDirectory: String,
        endpointUrl: String,
    ): List<String> {
        val sandboxMode = fileSystemAccess.toCodexSandboxMode()
        val reasoningEffort = config.reasoningEffort.name.lowercase()
        val args =
            mutableListOf(
                command,
                "exec",
                "--json",
                "--model",
                config.model.trim().ifBlank { DEFAULT_CODEX_MODEL },
                "--sandbox",
                sandboxMode,
                "--cd",
                workingDirectory,
                "--config",
                "approval_policy=${toTomlString(CODEX_APPROVAL_POLICY_NEVER)}",
                "--config",
                "model_reasoning_effort=${toTomlString(reasoningEffort)}",
                "--config",
                "plan_mode_reasoning_effort=${toTomlString(reasoningEffort)}",
                "--config",
                "web_search=${
                    toTomlString(
                        if (config.webSearch) CODEX_WEB_SEARCH_LIVE else CODEX_WEB_SEARCH_DISABLED,
                    )
                }",
                "--config",
                "mcp_servers.broxy.url=${toTomlString(endpointUrl)}",
                "--skip-git-repo-check",
            )
        if (sandboxMode == CODEX_SANDBOX_WORKSPACE_WRITE) {
            args += "--config"
            args += "sandbox_workspace_write.network_access=false"
        }
        return args
    }

    private fun buildCodexEnvironment(resolvedUserPath: String?): MutableMap<String, String> {
        val environment = ProcessBuilder().environment()
        resolvedUserPath?.takeIf { it.isNotBlank() }?.let { resolvedPath ->
            val pathKey = UserPathResolver.resolvePathKey(environment)
            environment[pathKey] = resolvedPath
        }
        val userHome = System.getProperty("user.home")?.trim().orEmpty()
        if (userHome.isNotBlank()) {
            environment.putKeyIgnoreCase("HOME", userHome)
            environment.putKeyIgnoreCase("CODEX_HOME", File(userHome, ".codex").absolutePath)
        }
        return environment
    }

    private fun resolveCodexHome(environment: Map<String, String>): String {
        val configuredCodexHome = environment.valueForKeyIgnoreCase("CODEX_HOME")?.trim().orEmpty()
        val userHome = environment.valueForKeyIgnoreCase("HOME")?.trim().orEmpty()
        return when {
            configuredCodexHome.isNotBlank() -> configuredCodexHome
            userHome.isNotBlank() -> File(userHome, ".codex").absolutePath
            else -> File(System.getProperty("user.home"), ".codex").absolutePath
        }
    }

    private fun resolveOpenAiBaseUrlOverride(configuredBaseUrl: String?): String? =
        configuredBaseUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != DEFAULT_OPENAI_BASE_URL }

    private fun buildLaunchPrompt(
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val normalizedSystemPrompt = systemPrompt.trim()
        return if (normalizedSystemPrompt.isBlank()) {
            userPrompt
        } else {
            buildString {
                append("System prompt:\n")
                append(normalizedSystemPrompt)
                append("\n\nUser prompt:\n")
                append(userPrompt)
            }
        }
    }
}

private fun AgentFileSystemAccess.toCodexSandboxMode(): String =
    when (this) {
        AgentFileSystemAccess.READ_WRITE -> CODEX_SANDBOX_WORKSPACE_WRITE
        AgentFileSystemAccess.NONE,
        AgentFileSystemAccess.READ_ONLY,
        -> CODEX_SANDBOX_READ_ONLY
    }

private fun toTomlString(value: String): String = "\"${value.replace("\"", "\\\"")}\""

private fun MutableMap<String, String>.hasNonBlankKeyIgnoreCase(key: String): Boolean =
    entries.any { (entryKey, value) -> entryKey.equals(key, ignoreCase = true) && value.isNotBlank() }

private fun MutableMap<String, String>.removeKeyIgnoreCase(key: String) {
    val found = keys.firstOrNull { it.equals(key, ignoreCase = true) } ?: return
    remove(found)
}

private fun MutableMap<String, String>.putKeyIgnoreCase(
    key: String,
    value: String,
) {
    val existing = keys.firstOrNull { it.equals(key, ignoreCase = true) }
    if (existing != null) {
        put(existing, value)
    } else {
        put(key, value)
    }
}

private fun Map<String, String>.valueForKeyIgnoreCase(key: String): String? =
    entries.firstOrNull { (entryKey, _) -> entryKey.equals(key, ignoreCase = true) }?.value
