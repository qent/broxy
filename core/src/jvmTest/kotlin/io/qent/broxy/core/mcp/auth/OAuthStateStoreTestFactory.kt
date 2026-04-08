package io.qent.broxy.core.mcp.auth

import io.qent.broxy.core.utils.ConsoleLogger
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.Paths

internal fun oauthStateStoreForTesting(
    baseDir: Path = Paths.get(System.getProperty("user.home"), ".config", "broxy"),
    logger: Logger = ConsoleLogger,
    secureStorage: SecureStorage = InMemorySecureStorage(),
): OAuthStateStore {
    val constructor =
        OAuthStateStore::class.java.getDeclaredConstructor(
            Path::class.java,
            Logger::class.java,
            Json::class.java,
            SecureStorage::class.java,
        )
    constructor.isAccessible = true
    val json = Json { ignoreUnknownKeys = true }
    return constructor.newInstance(baseDir, logger, json, secureStorage)
}
