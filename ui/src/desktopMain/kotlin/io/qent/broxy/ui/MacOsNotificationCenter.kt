package io.qent.broxy.ui

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val UN_AUTH_OPTIONS_ALERT_AND_SOUND = 0x6L
private const val MAX_PERMISSION_REQUEST_ATTEMPTS = 3

internal class MacOsNotificationCenter(
    @Suppress("unused")
    private val onAgentRunNotificationActivated: (String) -> Unit,
    @Suppress("unused")
    private val onPermissionProbeActivated: () -> Unit,
    private val bridge: MacOsNotificationBridge = MacOsNotificationNativeBridge,
    private val notificationExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val isMacOs: Boolean = isMacOsDesktop(),
) : DesktopSystemNotificationCenter {
    private val lock = Any()
    private val bridgeAvailable = isMacOs && bridge.isAvailable()
    private val supported = bridgeAvailable

    @Volatile
    private var permissionRequestAttempts = 0

    @Volatile
    private var contextChecked = false

    @Volatile
    private var contextSupported = false

    override fun requestPermissionProbeIfNeeded(
        title: String,
        body: String,
    ) {
        if (!supported) {
            return
        }
        notificationExecutor.execute {
            requestAuthorizationIfNeeded()
        }
    }

    override fun showAgentRunNotification(
        agentId: String,
        title: String,
        body: String,
    ) {
        if (!supported) {
            return
        }

        notificationExecutor.execute {
            if (!ensureSupportedContext()) {
                return@execute
            }

            requestAuthorizationIfNeeded()
            val authorizationStatus = bridge.getAuthorizationStatus()
            if (!shouldPostForStatus(authorizationStatus)) {
                return@execute
            }

            bridge.postNotification(
                agentId = agentId,
                title = title,
                body = body,
            )
        }
    }

    override fun dispose() {
        notificationExecutor.shutdownNow()
        synchronized(lock) {
            permissionRequestAttempts = 0
            contextChecked = false
            contextSupported = false
        }
    }

    private fun requestAuthorizationIfNeeded() {
        if (!ensureSupportedContext()) {
            return
        }

        val status = bridge.getAuthorizationStatus()
        if (shouldPostForStatus(status)) {
            return
        }

        val shouldRequest =
            synchronized(lock) {
                if (permissionRequestAttempts >= MAX_PERMISSION_REQUEST_ATTEMPTS) {
                    false
                } else {
                    permissionRequestAttempts += 1
                    true
                }
            }
        if (!shouldRequest) {
            return
        }

        bridge.requestAuthorization(UN_AUTH_OPTIONS_ALERT_AND_SOUND)
    }

    private fun ensureSupportedContext(): Boolean {
        if (!supported) {
            return false
        }

        if (contextChecked) {
            return contextSupported
        }

        synchronized(lock) {
            if (contextChecked) {
                return contextSupported
            }
            contextSupported = bridge.isSupportedContext()
            contextChecked = true
            return contextSupported
        }
    }

    private fun shouldPostForStatus(status: MacOsAuthorizationStatus): Boolean =
        when (status) {
            MacOsAuthorizationStatus.AUTHORIZED,
            MacOsAuthorizationStatus.PROVISIONAL,
            -> true

            else -> false
        }
}
