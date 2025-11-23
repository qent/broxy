package io.qent.broxy.ui.adapter.remote.storage

import java.nio.file.Path

/**
 * Provides a best-effort secure storage. Tries to use a platform keychain in the future,
 * currently relies on a filesystem-backed store with 600 permissions as a fallback.
 */
class SecureStorageProvider(
    private val fallbackDir: Path
) {
    fun provide(): SecureStore = FileSecureStore(fallbackDir)
}
