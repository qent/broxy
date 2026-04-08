package io.qent.broxy.ui

import java.io.File
import java.io.FileOutputStream

internal interface MacOsNotificationBridge {
    fun isAvailable(): Boolean

    fun isSupportedContext(): Boolean

    fun getAuthorizationStatus(): MacOsAuthorizationStatus

    fun requestAuthorization(optionsMask: Long): MacOsRequestAuthorizationResult

    fun postNotification(
        agentId: String,
        title: String,
        body: String,
    ): MacOsPostNotificationResult
}

internal enum class MacOsAuthorizationStatus {
    NOT_DETERMINED,
    DENIED,
    AUTHORIZED,
    PROVISIONAL,
    UNSUPPORTED_CONTEXT,
    ERROR,
}

internal enum class MacOsRequestAuthorizationResult {
    STARTED,
    UNSUPPORTED_CONTEXT,
    ERROR,
}

internal enum class MacOsPostNotificationResult {
    POSTED,
    UNSUPPORTED_CONTEXT,
    NOT_AUTHORIZED,
    INVALID_INPUT,
    ERROR,
}

internal object MacOsNotificationNativeBridge : MacOsNotificationBridge {
    private const val LIBRARY_FILE_NAME = "libbroxy_notifications.dylib"
    private const val RESOURCE_ROOT = "/native/macos"

    private const val AUTH_STATUS_NOT_DETERMINED = 0
    private const val AUTH_STATUS_DENIED = 1
    private const val AUTH_STATUS_AUTHORIZED = 2
    private const val AUTH_STATUS_PROVISIONAL = 3
    private const val AUTH_STATUS_UNSUPPORTED_CONTEXT = 4
    private const val AUTH_STATUS_ERROR = 5

    private const val REQUEST_RESULT_STARTED = 0
    private const val REQUEST_RESULT_UNSUPPORTED_CONTEXT = 1
    private const val REQUEST_RESULT_ERROR = 2

    private const val POST_RESULT_POSTED = 0
    private const val POST_RESULT_UNSUPPORTED_CONTEXT = 1
    private const val POST_RESULT_NOT_AUTHORIZED = 2
    private const val POST_RESULT_INVALID_INPUT = 3
    private const val POST_RESULT_ERROR = 4

    private val loadLock = Any()

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var loaded = false

    override fun isAvailable(): Boolean = ensureLoaded()

    override fun isSupportedContext(): Boolean {
        if (!ensureLoaded()) {
            return false
        }
        return runCatching { nativeIsSupportedContext() }.getOrDefault(false)
    }

    override fun getAuthorizationStatus(): MacOsAuthorizationStatus {
        if (!ensureLoaded()) {
            return MacOsAuthorizationStatus.UNSUPPORTED_CONTEXT
        }
        val nativeStatus = runCatching { nativeGetAuthorizationStatus() }.getOrDefault(AUTH_STATUS_ERROR)
        return nativeStatus.toAuthorizationStatus()
    }

    override fun requestAuthorization(optionsMask: Long): MacOsRequestAuthorizationResult {
        if (!ensureLoaded()) {
            return MacOsRequestAuthorizationResult.UNSUPPORTED_CONTEXT
        }
        val nativeResult = runCatching { nativeRequestAuthorization(optionsMask) }.getOrDefault(REQUEST_RESULT_ERROR)
        return nativeResult.toRequestAuthorizationResult()
    }

    override fun postNotification(
        agentId: String,
        title: String,
        body: String,
    ): MacOsPostNotificationResult {
        if (!ensureLoaded()) {
            return MacOsPostNotificationResult.UNSUPPORTED_CONTEXT
        }
        val nativeResult =
            runCatching {
                nativePostNotification(agentId, title, body)
            }.getOrDefault(POST_RESULT_ERROR)
        return nativeResult.toPostNotificationResult()
    }

    internal fun resolveLibraryResourcePath(
        osName: String = System.getProperty("os.name").orEmpty(),
        archName: String = System.getProperty("os.arch").orEmpty(),
    ): String? {
        if (!osName.contains("Mac", ignoreCase = true)) {
            return null
        }
        val arch = normalizeMacArch(archName) ?: return null
        return "$RESOURCE_ROOT/$arch/$LIBRARY_FILE_NAME"
    }

    private fun ensureLoaded(): Boolean {
        if (loaded) {
            return true
        }
        if (loadAttempted) {
            return false
        }
        synchronized(loadLock) {
            if (loaded) {
                return true
            }
            if (loadAttempted) {
                return false
            }
            loadAttempted = true

            val arch = normalizeMacArch(System.getProperty("os.arch").orEmpty()) ?: return false
            val resourcePath = resolveLibraryResourcePath() ?: return false
            val resourceStream = MacOsNotificationNativeBridge::class.java.getResourceAsStream(resourcePath) ?: return false
            val targetFile = File(nativeLibraryDirectory(arch), LIBRARY_FILE_NAME)

            return runCatching {
                resourceStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                targetFile.setReadable(true, true)
                targetFile.setExecutable(true, true)
                System.load(targetFile.absolutePath)
                loaded = true
                true
            }.getOrDefault(false)
        }
    }

    private fun nativeLibraryDirectory(arch: String): File {
        val root = File(System.getProperty("java.io.tmpdir"), "broxy-notifications-native/$arch")
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    private fun normalizeMacArch(archName: String): String? {
        val normalized = archName.trim().lowercase()
        return when (normalized) {
            "arm64", "aarch64" -> "arm64"
            "x86_64", "amd64" -> "x86_64"
            else -> null
        }
    }

    private fun Int.toAuthorizationStatus(): MacOsAuthorizationStatus =
        when (this) {
            AUTH_STATUS_NOT_DETERMINED -> MacOsAuthorizationStatus.NOT_DETERMINED
            AUTH_STATUS_DENIED -> MacOsAuthorizationStatus.DENIED
            AUTH_STATUS_AUTHORIZED -> MacOsAuthorizationStatus.AUTHORIZED
            AUTH_STATUS_PROVISIONAL -> MacOsAuthorizationStatus.PROVISIONAL
            AUTH_STATUS_UNSUPPORTED_CONTEXT -> MacOsAuthorizationStatus.UNSUPPORTED_CONTEXT
            else -> MacOsAuthorizationStatus.ERROR
        }

    private fun Int.toRequestAuthorizationResult(): MacOsRequestAuthorizationResult =
        when (this) {
            REQUEST_RESULT_STARTED -> MacOsRequestAuthorizationResult.STARTED
            REQUEST_RESULT_UNSUPPORTED_CONTEXT -> MacOsRequestAuthorizationResult.UNSUPPORTED_CONTEXT
            else -> MacOsRequestAuthorizationResult.ERROR
        }

    private fun Int.toPostNotificationResult(): MacOsPostNotificationResult =
        when (this) {
            POST_RESULT_POSTED -> MacOsPostNotificationResult.POSTED
            POST_RESULT_UNSUPPORTED_CONTEXT -> MacOsPostNotificationResult.UNSUPPORTED_CONTEXT
            POST_RESULT_NOT_AUTHORIZED -> MacOsPostNotificationResult.NOT_AUTHORIZED
            POST_RESULT_INVALID_INPUT -> MacOsPostNotificationResult.INVALID_INPUT
            else -> MacOsPostNotificationResult.ERROR
        }

    private external fun nativeIsSupportedContext(): Boolean

    private external fun nativeGetAuthorizationStatus(): Int

    private external fun nativeRequestAuthorization(optionsMask: Long): Int

    private external fun nativePostNotification(
        agentId: String,
        title: String,
        body: String,
    ): Int
}
