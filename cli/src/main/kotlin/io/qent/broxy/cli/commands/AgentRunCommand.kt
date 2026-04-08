package io.qent.broxy.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import io.qent.broxy.agents.AgentCodexReasoningEffort
import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.agents.AgentRuntime
import io.qent.broxy.agents.LlmProvider
import java.io.File
import java.nio.file.Paths

open class AgentRunCommand : CliktCommand {
    private var runner: AgentRunCommandRunner

    constructor() : super(name = "run", help = "Run a Broxy agent once without UI") {
        runner = AgentRunCommandRunner()
    }

    internal constructor(
        runner: AgentRunCommandRunner,
    ) : super(
        name = "run",
        help = "Run a Broxy agent once without UI",
    ) {
        this.runner = runner
    }

    private val defaultStateDir =
        Paths.get(System.getProperty("user.home"), ".config", "broxy").toFile()

    private val mcpConfig: File by option(
        "--mcp-config",
        help = "Path to mcp.json (or compatible file).",
    ).file(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val agentConfig: File by option(
        "--agent-config",
        help = "Path to Claude subagent markdown file (<id>.md).",
    ).file(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val agentSettings: File? by option(
        "--agent-settings",
        help = "Optional path to agents_settings.json.",
    ).file(mustExist = true, canBeFile = true, canBeDir = false)

    private val agentsSecrets: File? by option(
        "--agents-secrets",
        help = "Optional path to agents_secrets.json.",
    ).file(mustExist = true, canBeFile = true, canBeDir = false)

    private val stateDir: File by option(
        "--state-dir",
        help = "State directory for logs, OAuth cache, and runtime state.",
    ).file(mustExist = false, canBeFile = false, canBeDir = true)
        .default(defaultStateDir)

    private val prompt: String? by option("--prompt", help = "Prompt override for this run.")

    private val runtime: AgentRuntime? by option(
        "--runtime",
        help = "Runtime override: langchain|codex.",
    ).choice(runtimeChoices)

    private val provider: LlmProvider? by option(
        "--provider",
        help = "LLM provider override: openai|anthropic|lm-studio.",
    ).choice(providerChoices)

    private val model: String? by option("--model", help = "LLM model override.")

    private val temperature: Double? by
        option("--temperature", help = "LLM temperature override.")
            .double()
            .check("must be a finite number") { it.isFinite() }

    private val workspace: String? by option("--workspace", help = "Workspace path override.")

    private val fsAccess: AgentFileSystemAccess? by option(
        "--fs-access",
        help = "Filesystem access override: none|read-only|read-write.",
    ).choice(fsAccessChoices)

    private val codexModel: String? by option("--codex-model", help = "Codex model override.")

    private val codexReasoning: AgentCodexReasoningEffort? by option(
        "--codex-reasoning",
        help = "Codex reasoning effort override: low|medium|high.",
    ).choice(codexReasoningChoices)

    private val codexWebSearch: Boolean? by option(
        "--codex-web-search",
        help = "Codex web search override: true|false.",
    ).choice(codexWebSearchChoices)

    private val output: AgentOutputFormat by option(
        "--output",
        help = "Output format: text|json.",
    ).choice(outputChoices)
        .default(AgentOutputFormat.TEXT)

    private val timeoutSeconds: Long by
        option(
            "--timeout-seconds",
            help = "Run timeout in seconds.",
        ).long()
            .default(DEFAULT_AGENT_RUN_TIMEOUT_SECONDS)
            .check("must be at least 1") { it >= 1L }

    private val logLevel: LogLevelOption by
        option("--log-level", help = "Log level: debug|info|warn|error")
            .enum<LogLevelOption>()
            .default(LogLevelOption.INFO)

    override fun run() {
        val options =
            AgentRunCliOptions(
                mcpConfigFile = mcpConfig,
                agentConfigFile = agentConfig,
                agentSettingsFile = agentSettings,
                agentsSecretsFile = agentsSecrets,
                stateDir = stateDir,
                prompt = prompt,
                runtime = runtime,
                provider = provider,
                model = model,
                temperature = temperature,
                workspace = workspace,
                fileSystemAccess = fsAccess,
                codexModel = codexModel,
                codexReasoningEffort = codexReasoning,
                codexWebSearch = codexWebSearch,
                output = output,
                timeoutSeconds = timeoutSeconds,
                logLevel = logLevel,
            )

        val result = runner.run(options)
        result.standardOutput?.let { echo(it) }
        result.errorOutput?.let { echo(it, err = true) }
        if (result.exitCode != 0) {
            throw ProgramResult(result.exitCode)
        }
    }

    private companion object {
        private val runtimeChoices =
            linkedMapOf(
                "langchain" to AgentRuntime.LANGCHAIN,
                "codex" to AgentRuntime.CODEX_CLI,
                "codex-cli" to AgentRuntime.CODEX_CLI,
            )

        private val providerChoices =
            linkedMapOf(
                "openai" to LlmProvider.OPENAI,
                "anthropic" to LlmProvider.ANTHROPIC,
                "lm-studio" to LlmProvider.LM_STUDIO,
                "lmstudio" to LlmProvider.LM_STUDIO,
            )

        private val fsAccessChoices =
            linkedMapOf(
                "none" to AgentFileSystemAccess.NONE,
                "read-only" to AgentFileSystemAccess.READ_ONLY,
                "read-write" to AgentFileSystemAccess.READ_WRITE,
            )

        private val codexReasoningChoices =
            linkedMapOf(
                "low" to AgentCodexReasoningEffort.LOW,
                "medium" to AgentCodexReasoningEffort.MEDIUM,
                "high" to AgentCodexReasoningEffort.HIGH,
            )

        private val codexWebSearchChoices =
            linkedMapOf(
                "true" to true,
                "false" to false,
            )

        private val outputChoices =
            linkedMapOf(
                "text" to AgentOutputFormat.TEXT,
                "json" to AgentOutputFormat.JSON,
            )
    }
}
