package io.qent.broxy.ui

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacOsNotificationCenterTest {
    @Test
    fun `requestPermissionProbeIfNeeded requests authorization up to three times per session`() {
        val bridge =
            FakeMacOsNotificationBridge(
                authorizationStatus = MacOsAuthorizationStatus.NOT_DETERMINED,
            )
        val center = createTestCenter(bridge)

        center.requestPermissionProbeIfNeeded("permission", "probe")
        center.requestPermissionProbeIfNeeded("permission", "probe")
        center.requestPermissionProbeIfNeeded("permission", "probe")
        center.requestPermissionProbeIfNeeded("permission", "probe")
        center.requestPermissionProbeIfNeeded("permission", "probe")

        assertEquals(3, bridge.requestAuthorizationCalls)
        center.dispose()
    }

    @Test
    fun `showAgentRunNotification posts notification when authorized`() {
        val bridge =
            FakeMacOsNotificationBridge(
                authorizationStatus = MacOsAuthorizationStatus.AUTHORIZED,
            )
        val center = createTestCenter(bridge)

        center.showAgentRunNotification(
            agentId = "agent-1",
            title = "title",
            body = "body",
        )

        assertEquals(0, bridge.requestAuthorizationCalls)
        assertEquals(1, bridge.postCalls)
        assertEquals(PostedNotification("agent-1", "title", "body"), bridge.lastPosted)
        center.dispose()
    }

    @Test
    fun `showAgentRunNotification does not post when authorization denied`() {
        val bridge =
            FakeMacOsNotificationBridge(
                authorizationStatus = MacOsAuthorizationStatus.DENIED,
            )
        val center = createTestCenter(bridge)

        center.showAgentRunNotification(
            agentId = "agent-1",
            title = "title",
            body = "body",
        )

        assertEquals(1, bridge.requestAuthorizationCalls)
        assertEquals(0, bridge.postCalls)
        assertNull(bridge.lastPosted)
        center.dispose()
    }

    @Test
    fun `showAgentRunNotification requests authorization but does not post when status not determined`() {
        val bridge =
            FakeMacOsNotificationBridge(
                authorizationStatus = MacOsAuthorizationStatus.NOT_DETERMINED,
            )
        val center = createTestCenter(bridge)

        center.showAgentRunNotification(
            agentId = "agent-1",
            title = "title",
            body = "body",
        )

        assertEquals(1, bridge.requestAuthorizationCalls)
        assertEquals(0, bridge.postCalls)
        assertNull(bridge.lastPosted)
        center.dispose()
    }

    @Test
    fun `showAgentRunNotification skips bridge calls when context unsupported`() {
        val bridge =
            FakeMacOsNotificationBridge(
                supportedContext = false,
                authorizationStatus = MacOsAuthorizationStatus.AUTHORIZED,
            )
        val center = createTestCenter(bridge)

        center.showAgentRunNotification(
            agentId = "agent-1",
            title = "title",
            body = "body",
        )

        assertEquals(0, bridge.requestAuthorizationCalls)
        assertEquals(0, bridge.postCalls)
        assertNull(bridge.lastPosted)
        center.dispose()
    }

    private fun createTestCenter(bridge: FakeMacOsNotificationBridge): MacOsNotificationCenter =
        MacOsNotificationCenter(
            onAgentRunNotificationActivated = {},
            onPermissionProbeActivated = {},
            bridge = bridge,
            notificationExecutor = DirectExecutorService(),
            isMacOs = true,
        )
}

class MacOsNotificationNativeBridgeTest {
    @Test
    fun `resolveLibraryResourcePath returns null on non-macos`() {
        val path = MacOsNotificationNativeBridge.resolveLibraryResourcePath(osName = "Linux", archName = "x86_64")

        assertNull(path)
    }

    @Test
    fun `resolveLibraryResourcePath returns null for unsupported arch`() {
        val path = MacOsNotificationNativeBridge.resolveLibraryResourcePath(osName = "Mac OS X", archName = "ppc")

        assertNull(path)
    }

    @Test
    fun `resolveLibraryResourcePath maps supported mac arch`() {
        val path = MacOsNotificationNativeBridge.resolveLibraryResourcePath(osName = "Mac OS X", archName = "aarch64")

        assertEquals("/native/macos/arm64/libbroxy_notifications.dylib", path)
    }
}

private data class PostedNotification(
    val agentId: String,
    val title: String,
    val body: String,
)

private class FakeMacOsNotificationBridge(
    private val available: Boolean = true,
    private val supportedContext: Boolean = true,
    private val authorizationStatus: MacOsAuthorizationStatus = MacOsAuthorizationStatus.AUTHORIZED,
    private val requestAuthorizationResult: MacOsRequestAuthorizationResult = MacOsRequestAuthorizationResult.STARTED,
    private val postNotificationResult: MacOsPostNotificationResult = MacOsPostNotificationResult.POSTED,
) : MacOsNotificationBridge {
    var requestAuthorizationCalls: Int = 0
        private set

    var postCalls: Int = 0
        private set

    var lastPosted: PostedNotification? = null
        private set

    override fun isAvailable(): Boolean = available

    override fun isSupportedContext(): Boolean = supportedContext

    override fun getAuthorizationStatus(): MacOsAuthorizationStatus = authorizationStatus

    override fun requestAuthorization(optionsMask: Long): MacOsRequestAuthorizationResult {
        requestAuthorizationCalls += 1
        return requestAuthorizationResult
    }

    override fun postNotification(
        agentId: String,
        title: String,
        body: String,
    ): MacOsPostNotificationResult {
        postCalls += 1
        lastPosted = PostedNotification(agentId, title, body)
        return postNotificationResult
    }
}

private class DirectExecutorService : AbstractExecutorService() {
    @Volatile
    private var shutdown = false

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        return mutableListOf()
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = shutdown

    override fun execute(command: Runnable) {
        if (shutdown) {
            throw RejectedExecutionException("Executor is shut down")
        }
        command.run()
    }
}
