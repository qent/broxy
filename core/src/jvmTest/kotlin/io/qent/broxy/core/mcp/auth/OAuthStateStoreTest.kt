package io.qent.broxy.core.mcp.auth

import io.qent.broxy.core.config.ConfigTestLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OAuthStateStoreTest {
    @Test
    fun save_and_load_roundtrip() {
        val store =
            OAuthStateStore.forTesting(
                logger = ConfigTestLogger,
                secureStorage = InMemorySecureStorage(),
            )
        val snapshot =
            OAuthStateSnapshot(
                resourceUrl = "https://mcp.example.com",
                token = OAuthToken(accessToken = "token123", expiresAtEpochMillis = 10_000L),
                registration = OAuthClientRegistration(clientId = "client"),
                lastRequestedScope = "files:read",
            )

        store.save("server-1", snapshot)

        val loaded = store.load("server-1", "https://mcp.example.com")
        assertEquals(snapshot, loaded)
    }

    @Test
    fun load_ignores_mismatched_resource() {
        val store =
            OAuthStateStore.forTesting(
                logger = ConfigTestLogger,
                secureStorage = InMemorySecureStorage(),
            )
        val snapshot =
            OAuthStateSnapshot(
                resourceUrl = "https://mcp.example.com",
                token = OAuthToken(accessToken = "token123", expiresAtEpochMillis = 10_000L),
            )

        store.save("server-1", snapshot)

        val loaded = store.load("server-1", "https://other.example.com")
        assertNull(loaded)
    }

    @Test
    fun save_removes_empty_entries() {
        val store =
            OAuthStateStore.forTesting(
                logger = ConfigTestLogger,
                secureStorage = InMemorySecureStorage(),
            )
        val snapshot =
            OAuthStateSnapshot(
                resourceUrl = "https://mcp.example.com",
                token = OAuthToken(accessToken = "token123", expiresAtEpochMillis = 10_000L),
            )
        store.save("server-1", snapshot)

        store.save("server-1", OAuthStateSnapshot(resourceUrl = "https://mcp.example.com"))

        val loaded = store.load("server-1", "https://mcp.example.com")
        assertNull(loaded)
    }
}
