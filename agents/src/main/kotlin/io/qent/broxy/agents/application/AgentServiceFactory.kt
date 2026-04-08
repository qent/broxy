package io.qent.broxy.agents.application

import io.qent.broxy.agents.AgentExecutionRequest
import io.qent.broxy.agents.AgentExecutionResult
import io.qent.broxy.agents.AgentExecutor
import io.qent.broxy.agents.AgentService
import io.qent.broxy.agents.application.scheduler.CronAgentScheduler
import io.qent.broxy.agents.infrastructure.persistence.JsonAgentProviderSettingsRepository
import io.qent.broxy.agents.infrastructure.persistence.JsonAgentRepository
import io.qent.broxy.agents.infrastructure.persistence.JsonAgentRunRepository
import io.qent.broxy.agents.infrastructure.secrets.defaultAgentSecretsStore
import io.qent.broxy.agents.runtime.langchain.LangChain4jAgentExecutor
import io.qent.broxy.core.config.JsonConfigurationRepository
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.CompositeLogger
import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.DailyFileLogger
import io.qent.broxy.core.utils.Logger
import java.nio.file.Path
import java.nio.file.Paths

fun defaultAgentService(
    baseDir: Path = Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    logger: Logger = CompositeLogger(ConsoleLogger, DailyFileLogger(baseDir)),
    configurationRepository: ConfigurationRepository = JsonConfigurationRepository(baseDir = baseDir, logger = logger),
    executor: AgentExecutor? = null,
): AgentService {
    val collecting = if (logger is CollectingLogger) logger else CollectingLogger(logger)
    val resolvedExecutor = executor ?: defaultAgentExecutor(baseDir, collecting)
    return DefaultAgentService(
        agentRepository = JsonAgentRepository(baseDir = baseDir),
        runRepository = JsonAgentRunRepository(baseDir = baseDir),
        settingsRepository = JsonAgentProviderSettingsRepository(baseDir = baseDir),
        secretsStore = defaultAgentSecretsStore(baseDir = baseDir, logger = collecting),
        configurationProvider = { configurationRepository.loadMcpConfig() },
        executor = resolvedExecutor,
        scheduler = CronAgentScheduler(collecting),
        logger = collecting,
    )
}

fun emptyMcpConfigProvider(): () -> McpServersConfig = { McpServersConfig() }

private fun defaultAgentExecutor(
    baseDir: Path,
    logger: Logger,
): AgentExecutor {
    val langChainExecutor = LangChain4jAgentExecutor(logger = logger, oauthStateStoreBaseDir = baseDir)
    return HybridAgentExecutor(
        langChainExecutor = langChainExecutor,
        codexExecutor = DisabledCodexAgentExecutor,
    )
}

private object DisabledCodexAgentExecutor : AgentExecutor {
    override suspend fun execute(request: AgentExecutionRequest): Result<AgentExecutionResult> =
        Result.failure(IllegalStateException("Codex runtime module is not configured"))
}
