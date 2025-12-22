package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.capabilities.CapabilityCache
import io.qent.broxy.core.capabilities.CapabilityRefresher
import io.qent.broxy.core.capabilities.ServerStatusTracker
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiServerCapabilities
import io.qent.broxy.ui.adapter.models.UiServerConnStatus
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.toUiStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityRefresherTest {
    private val noopLogger =
        object : Logger {
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
    private val logger = CollectingLogger(delegate = noopLogger)
    private val testScope = TestScope()
    private val cache = CapabilityCache { 0L }
    private val tracker = ServerStatusTracker()
    private val publishes = mutableListOf<Unit>()
    private var storeSnapshot =
        StoreSnapshot(
            servers =
                listOf(
                    UiMcpServerConfig(
                        id = "s1",
                        name = "Server 1",
                        transport = UiStdioTransport(command = "cmd"),
                        enabled = true,
                    ),
                ),
            capabilitiesTimeoutSeconds = 15,
            capabilitiesRefreshIntervalSeconds = 60,
        )

    @org.junit.Test
    fun refreshServersByIdCachesSnapshotAndStatus() =
        runTest {
            val refresher = createRefresher(Result.success(UiServerCapabilities()))

            refresher.refreshServersById(setOf("s1"), force = true)

            val snapshot = cache.snapshot("s1")
            assertNotNull(snapshot)
            assertEquals("Server 1", snapshot.name)
            assertEquals(UiServerConnStatus.Available, tracker.statusFor("s1")?.toUiStatus())
            assertEquals(2, publishes.size)
        }

    @org.junit.Test
    fun refreshServersWithFailureMarksError() =
        runTest {
            val refresher = createRefresher(Result.failure(IllegalStateException("boom")))

            refresher.refreshServersById(setOf("s1"), force = true)

            assertEquals(UiServerConnStatus.Error, tracker.statusFor("s1")?.toUiStatus())
            assertEquals("boom", tracker.errorMessageFor("s1"))
            assertTrue(cache.snapshot("s1") == null)
            assertEquals(2, publishes.size)
        }

    private fun createRefresher(result: Result<UiServerCapabilities>): CapabilityRefresher {
        publishes.clear()
        cache.retain(emptySet())
        tracker.retain(emptySet())
        return CapabilityRefresher(
            scope = testScope,
            capabilityFetcher = { _, _, _, _ -> result },
            capabilityCache = cache,
            statusTracker = tracker,
            logger = logger,
            serversProvider = { storeSnapshot.servers },
            capabilitiesTimeoutProvider = { storeSnapshot.capabilitiesTimeoutSeconds },
            connectionRetryCountProvider = { storeSnapshot.connectionRetryCount },
            publishUpdate = { publishes += Unit },
            refreshIntervalMillis = { 1_000L },
        )
    }
}
