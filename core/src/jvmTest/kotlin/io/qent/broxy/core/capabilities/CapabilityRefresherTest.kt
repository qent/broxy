package io.qent.broxy.core.capabilities

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.TransportConfig
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityRefresherTest {
    @Test
    fun refreshEnabledServers_updates_cache_and_statuses() =
        runTest {
            val calls = mutableListOf<String>()
            val configs =
                listOf(
                    McpServerConfig(
                        id = "s1",
                        name = "Server 1",
                        transport = TransportConfig.StdioTransport(command = "noop"),
                        enabled = true,
                    ),
                    McpServerConfig(
                        id = "s2",
                        name = "Server 2",
                        transport = TransportConfig.StdioTransport(command = "noop"),
                        enabled = true,
                    ),
                    McpServerConfig(
                        id = "s3",
                        name = "Server 3",
                        transport = TransportConfig.StdioTransport(command = "noop"),
                        enabled = false,
                    ),
                )
            val cache = CapabilityCache({ 0L })
            val statusTracker = ServerStatusTracker { 0L }
            val published = mutableListOf<Unit>()
            val refresher =
                CapabilityRefresher(
                    scope = this,
                    capabilityFetcher = { cfg, _, _, _ ->
                        calls += cfg.id
                        if (cfg.id == "s1") {
                            Result.success(ServerCapabilities())
                        } else {
                            Result.failure(IllegalStateException("boom"))
                        }
                    },
                    capabilityCache = cache,
                    statusTracker = statusTracker,
                    logger = NoopLogger,
                    serversProvider = { configs },
                    capabilitiesTimeoutProvider = { 5 },
                    connectionRetryCountProvider = { 3 },
                    publishUpdate = { published += Unit },
                    refreshIntervalMillis = { 0L },
                )

            refresher.refreshEnabledServers(force = true)

            assertEquals(listOf("s1", "s2"), calls)
            assertEquals(ServerConnectionStatus.Available, statusTracker.statusFor("s1"))
            assertEquals(ServerConnectionStatus.Error, statusTracker.statusFor("s2"))
            assertNull(statusTracker.statusFor("s3"))
            assertTrue(cache.has("s1"))
            assertTrue(published.size >= 2)
        }

    @Test
    fun refreshEnabledServers_skips_fresh_cached_entries() =
        runTest {
            var nowMillis = 0L
            val calls = mutableListOf<String>()
            val config =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "noop"),
                    enabled = true,
                )
            val cache = CapabilityCache({ nowMillis })
            cache.put("s1", ServerCapsSnapshot(serverId = "s1", name = "Server 1"))

            val refresher =
                CapabilityRefresher(
                    scope = this,
                    capabilityFetcher = { cfg, _, _, _ ->
                        calls += cfg.id
                        Result.success(ServerCapabilities())
                    },
                    capabilityCache = cache,
                    statusTracker = ServerStatusTracker { nowMillis },
                    logger = NoopLogger,
                    serversProvider = { listOf(config) },
                    capabilitiesTimeoutProvider = { 5 },
                    connectionRetryCountProvider = { 3 },
                    publishUpdate = {},
                    refreshIntervalMillis = { 10_000L },
                )

            refresher.refreshEnabledServers(force = false)
            assertTrue(calls.isEmpty())

            nowMillis = 20_000L
            refresher.refreshEnabledServers(force = false)
            assertEquals(listOf("s1"), calls)
        }

    @Test
    fun markServerDisabled_cancels_inflight_refresh() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val config =
                McpServerConfig(
                    id = "s1",
                    name = "Server 1",
                    transport = TransportConfig.StdioTransport(command = "noop"),
                    enabled = true,
                )
            val cache = CapabilityCache({ 0L })
            val statusTracker = ServerStatusTracker { 0L }
            val refresher =
                CapabilityRefresher(
                    scope = this,
                    capabilityFetcher = { _, _, _, _ ->
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (e: CancellationException) {
                            cancelled.complete(Unit)
                            throw e
                        }
                        Result.success(ServerCapabilities())
                    },
                    capabilityCache = cache,
                    statusTracker = statusTracker,
                    logger = NoopLogger,
                    serversProvider = { listOf(config) },
                    capabilitiesTimeoutProvider = { 5 },
                    connectionRetryCountProvider = { 3 },
                    publishUpdate = {},
                    refreshIntervalMillis = { 0L },
                )

            val refreshJob = launch { refresher.refreshEnabledServers(force = true) }
            withTimeout(1_000) { started.await() }

            refresher.markServerDisabled("s1")

            withTimeout(1_000) { cancelled.await() }
            refreshJob.join()
            assertEquals(ServerConnectionStatus.Disabled, statusTracker.statusFor("s1"))
        }

    @Test
    fun refreshEnabledServers_does_not_cancel_other_servers_on_single_cancel() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val secondCompleted = CompletableDeferred<Unit>()
            val configs =
                listOf(
                    McpServerConfig(
                        id = "s1",
                        name = "Server 1",
                        transport = TransportConfig.StdioTransport(command = "noop"),
                        enabled = true,
                    ),
                    McpServerConfig(
                        id = "s2",
                        name = "Server 2",
                        transport = TransportConfig.StdioTransport(command = "noop"),
                        enabled = true,
                    ),
                )
            val cache = CapabilityCache({ 0L })
            val statusTracker = ServerStatusTracker { 0L }
            val refresher =
                CapabilityRefresher(
                    scope = this,
                    capabilityFetcher = { cfg, _, _, _ ->
                        when (cfg.id) {
                            "s1" -> {
                                started.complete(Unit)
                                awaitCancellation()
                            }
                            "s2" -> {
                                secondCompleted.complete(Unit)
                                Result.success(ServerCapabilities())
                            }
                            else -> Result.failure(IllegalStateException("Unexpected server"))
                        }
                    },
                    capabilityCache = cache,
                    statusTracker = statusTracker,
                    logger = NoopLogger,
                    serversProvider = { configs },
                    capabilitiesTimeoutProvider = { 5 },
                    connectionRetryCountProvider = { 3 },
                    publishUpdate = {},
                    refreshIntervalMillis = { 0L },
                )

            val refreshJob = launch { refresher.refreshEnabledServers(force = true) }
            withTimeout(1_000) { started.await() }

            refresher.markServerDisabled("s1")

            withTimeout(1_000) { secondCompleted.await() }
            refreshJob.join()
            assertEquals(ServerConnectionStatus.Disabled, statusTracker.statusFor("s1"))
            assertEquals(ServerConnectionStatus.Available, statusTracker.statusFor("s2"))
        }

    @Test
    fun applyProxySnapshots_keeps_existing_entries_for_disabled_servers() =
        runTest {
            val configs =
                listOf(
                    McpServerConfig(
                        id = "s1",
                        name = "Server 1",
                        transport = TransportConfig.StdioTransport(command = "noop"),
                        enabled = true,
                    ),
                    McpServerConfig(
                        id = "s2",
                        name = "Server 2",
                        transport = TransportConfig.StdioTransport(command = "noop"),
                        enabled = true,
                    ),
                    McpServerConfig(
                        id = "s3",
                        name = "Server 3",
                        transport = TransportConfig.StdioTransport(command = "noop"),
                        enabled = false,
                    ),
                )
            val cache = CapabilityCache({ 0L })
            val statusTracker = ServerStatusTracker { 0L }
            cache.put("s2", ServerCapsSnapshot(serverId = "s2", name = "Server 2"))
            cache.put("s3", ServerCapsSnapshot(serverId = "s3", name = "Server 3"))
            statusTracker.set("s2", ServerConnectionStatus.Available)
            statusTracker.set("s3", ServerConnectionStatus.Available)
            val published = mutableListOf<Unit>()
            val refresher =
                CapabilityRefresher(
                    scope = this,
                    capabilityFetcher = { _, _, _, _ -> Result.success(ServerCapabilities()) },
                    capabilityCache = cache,
                    statusTracker = statusTracker,
                    logger = NoopLogger,
                    serversProvider = { configs },
                    capabilitiesTimeoutProvider = { 5 },
                    connectionRetryCountProvider = { 3 },
                    publishUpdate = { published += Unit },
                    refreshIntervalMillis = { 0L },
                )

            refresher.applyProxySnapshots(
                listOf(ServerCapsSnapshot(serverId = "s1", name = "Server 1")),
            )

            assertNotNull(cache.snapshot("s2"))
            assertNotNull(cache.snapshot("s3"))
            assertEquals(ServerConnectionStatus.Available, statusTracker.statusFor("s1"))
            assertEquals(ServerConnectionStatus.Available, statusTracker.statusFor("s2"))
            assertEquals(ServerConnectionStatus.Disabled, statusTracker.statusFor("s3"))
            assertEquals(1, published.size)
        }
}

private object NoopLogger : Logger {
    override fun debug(message: String) {}

    override fun info(message: String) {}

    override fun warn(
        message: String,
        throwable: Throwable?,
    ) {}

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {}
}
