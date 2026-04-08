package io.qent.broxy.ui.adapter.agents

import io.qent.broxy.agents.application.DefaultAgentService
import io.qent.broxy.agents.application.HybridAgentExecutor
import io.qent.broxy.agents.application.scheduler.CronAgentScheduler
import io.qent.broxy.agents.codex.CodexCliExecutor
import io.qent.broxy.agents.infrastructure.persistence.JsonAgentProviderSettingsRepository
import io.qent.broxy.agents.infrastructure.persistence.JsonAgentRepository
import io.qent.broxy.agents.infrastructure.persistence.JsonAgentRunRepository
import io.qent.broxy.agents.infrastructure.secrets.defaultAgentSecretsStore
import io.qent.broxy.agents.runtime.langchain.LangChain4jAgentExecutor
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.ui.adapter.data.defaultConfigDir

internal fun provideAgentGateway(
    repository: ConfigurationRepository,
    logger: CollectingLogger,
): AgentGateway {
    val baseDir = defaultConfigDir()
    val langChainExecutor = LangChain4jAgentExecutor(logger = logger, oauthStateStoreBaseDir = baseDir)
    val codexExecutor = CodexCliExecutor(logger = logger, oauthStateStoreBaseDir = baseDir)
    val service =
        DefaultAgentService(
            agentRepository = JsonAgentRepository(baseDir = baseDir),
            runRepository = JsonAgentRunRepository(baseDir = baseDir),
            settingsRepository = JsonAgentProviderSettingsRepository(baseDir = baseDir),
            secretsStore = defaultAgentSecretsStore(baseDir = baseDir, logger = logger),
            configurationProvider = { repository.loadMcpConfig() },
            executor = HybridAgentExecutor(langChainExecutor = langChainExecutor, codexExecutor = codexExecutor),
            scheduler = CronAgentScheduler(logger),
            logger = logger,
        )
    return AgentGatewayJvm(service = service, configurationRepository = repository)
}
