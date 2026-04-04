package io.qent.broxy.ui.adapter.store

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.proxy.runtime.ProxyController
import io.qent.broxy.core.proxy.runtime.ServerConnectionUpdate
import io.qent.broxy.core.repository.ConfigurationRepository
import io.qent.broxy.core.utils.LogEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test

class AppStoreFactoryJvmSmokeTest {
    @Test
    fun createAppStore_can_be_constructed_and_stopped() {
        val store =
            createAppStore(
                scope = CoroutineScope(Dispatchers.Default),
                repository = FactoryConfigRepository(),
                proxyFactory = { FactoryProxyController() },
                capabilityFetcher = { _, _, _, _ -> Result.success(ServerCapabilities()) },
                enableBackgroundRefresh = false,
                ioDispatcher = Dispatchers.IO,
            )

        store.stop()
    }

    private class FactoryConfigRepository : ConfigurationRepository {
        override fun loadMcpConfig(): McpServersConfig = McpServersConfig()

        override fun saveMcpConfig(config: McpServersConfig) = Unit

        override fun loadPreset(id: String): Preset = Preset.empty()

        override fun savePreset(preset: Preset) = Unit

        override fun listPresets(): List<Preset> = listOf(Preset.empty())

        override fun deletePreset(id: String) = Unit
    }

    private class FactoryProxyController : ProxyController {
        override val logs: Flow<LogEvent> = MutableSharedFlow(extraBufferCapacity = 1)
        override val capabilityUpdates: Flow<Map<String, ServerCapabilities>> = emptyFlow()
        override val serverStatusUpdates: Flow<ServerConnectionUpdate> = emptyFlow()

        override fun start(
            servers: List<McpServerConfig>,
            preset: Preset,
            inbound: TransportConfig,
            callTimeoutSeconds: Int,
            capabilitiesTimeoutSeconds: Int,
            authorizationTimeoutSeconds: Int,
            connectionRetryCount: Int,
            ignoreHttpsCertificateErrors: Boolean,
            capabilitiesRefreshIntervalSeconds: Int,
            fallbackPromptsAndResourcesToTools: Boolean,
            adapterMode: Boolean,
        ): Result<Unit> = Result.success(Unit)

        override fun stop(): Result<Unit> = Result.success(Unit)

        override fun applyPreset(preset: Preset): Result<Unit> = Result.success(Unit)

        override fun updateServers(
            servers: List<McpServerConfig>,
            callTimeoutSeconds: Int,
            capabilitiesTimeoutSeconds: Int,
            authorizationTimeoutSeconds: Int,
            connectionRetryCount: Int,
            ignoreHttpsCertificateErrors: Boolean,
            capabilitiesRefreshIntervalSeconds: Int,
            fallbackPromptsAndResourcesToTools: Boolean,
            adapterMode: Boolean,
        ): Result<Unit> = Result.success(Unit)

        override fun updateCallTimeout(seconds: Int) = Unit

        override fun updateCapabilitiesTimeout(seconds: Int) = Unit

        override fun updateConnectionRetryCount(count: Int) = Unit

        override fun updateIgnoreHttpsCertificateErrors(enabled: Boolean) = Unit

        override fun updateFallbackPromptsAndResourcesToTools(enabled: Boolean) = Unit

        override fun updateAdapterMode(enabled: Boolean) = Unit

        override fun refreshServerCapabilities(serverId: String): Result<Unit> = Result.success(Unit)

        override fun refreshFilteredCapabilities(): Result<Unit> = Result.success(Unit)
    }
}
