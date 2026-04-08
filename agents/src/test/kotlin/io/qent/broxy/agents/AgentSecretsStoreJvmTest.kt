package io.qent.broxy.agents

import io.qent.broxy.agents.infrastructure.secrets.defaultAgentSecretsStore
import io.qent.broxy.core.utils.Logger
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSecretsStoreJvmTest {
    @Test
    fun defaultStore_usesFileFallbackWhenSecureStorageUnavailable() {
        val tempDir = Files.createTempDirectory("broxy-agent-secrets")
        val previousOsName = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            val store = defaultAgentSecretsStore(baseDir = tempDir, logger = SecretsTestLogger)

            store.saveApiKey(LlmProvider.OPENAI, "openai-key")
            assertEquals("openai-key", store.loadApiKey(LlmProvider.OPENAI))
            assertTrue(tempDir.resolve("agents").resolve("agents_secrets.json").exists())

            store.clearApiKey(LlmProvider.OPENAI)
            assertEquals(null, store.loadApiKey(LlmProvider.OPENAI))
        } finally {
            if (previousOsName == null) {
                System.clearProperty("os.name")
            } else {
                System.setProperty("os.name", previousOsName)
            }
            tempDir.toFile().walkBottomUp().forEach { file ->
                if (file.exists()) {
                    file.delete()
                }
            }
            tempDir.deleteIfExists()
        }
    }
}

private object SecretsTestLogger : Logger {
    override fun debug(message: String) = Unit

    override fun info(message: String) = Unit

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) = Unit

    override fun error(
        message: String,
        throwable: Throwable?,
    ) = Unit
}
