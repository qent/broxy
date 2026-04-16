package io.qent.broxy.core.mcp.auth

import io.qent.broxy.core.config.ConfigTestLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthStateStoreTest {
    @Test
    fun save_and_load_roundtrip() {
        val store =
            oauthStateStoreForTesting(
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
            oauthStateStoreForTesting(
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
            oauthStateStoreForTesting(
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

    @Test
    fun load_removes_invalid_entry() {
        val storage = InMemorySecureStorage()
        val store =
            oauthStateStoreForTesting(
                logger = ConfigTestLogger,
                secureStorage = storage,
            )
        storage.write("server-1", "7b0a2020202022not-json")

        val loaded = store.load("server-1", "https://mcp.example.com")

        assertNull(loaded)
        assertNull(storage.read("server-1"))
    }

    @Test
    fun save_writes_compact_json() {
        val storage = InMemorySecureStorage()
        val store =
            oauthStateStoreForTesting(
                logger = ConfigTestLogger,
                secureStorage = storage,
            )
        val snapshot =
            OAuthStateSnapshot(
                resourceUrl = "https://mcp.example.com",
                token = OAuthToken(accessToken = "token123", expiresAtEpochMillis = 10_000L),
                lastRequestedScope = "files:read",
            )

        store.save("server-1", snapshot)

        val stored = storage.read("server-1")
        assertNotNull(stored)
        assertFalse(stored.contains('\n'))
        assertFalse(stored.contains('\r'))
    }

    @Test
    fun load_does_not_log_secret_payload_on_decode_failure() {
        val logger = CapturingLogger()
        val storage = InMemorySecureStorage()
        val store =
            oauthStateStoreForTesting(
                logger = logger,
                secureStorage = storage,
            )
        val secret = "secret-token"
        storage.write("server-1", "{\"token\":\"$secret\"")

        val loaded = store.load("server-1", "https://mcp.example.com")

        assertNull(loaded)
        assertTrue(logger.messages.none { it.contains(secret) })
    }

    @Test
    fun save_overwrites_existing_snapshot_on_update() {
        val storage = InMemorySecureStorage()
        val store =
            oauthStateStoreForTesting(
                logger = ConfigTestLogger,
                secureStorage = storage,
            )
        val initial =
            OAuthStateSnapshot(
                resourceUrl = "https://mcp.example.com",
                token = OAuthToken(accessToken = "token1", expiresAtEpochMillis = 10_000L),
                lastRequestedScope = "files:read",
            )
        val updated =
            initial.copy(
                token = OAuthToken(accessToken = "token2", expiresAtEpochMillis = 20_000L),
                lastRequestedScope = "files:write",
            )

        store.save("server-1", initial)
        store.save("server-1", updated)

        val loaded = store.load("server-1", "https://mcp.example.com")
        assertEquals(updated, loaded)
    }

    @Test
    fun remove_clears_saved_snapshot() {
        val storage = InMemorySecureStorage()
        val store =
            oauthStateStoreForTesting(
                logger = ConfigTestLogger,
                secureStorage = storage,
            )
        val snapshot =
            OAuthStateSnapshot(
                resourceUrl = "https://mcp.example.com",
                token = OAuthToken(accessToken = "token123", expiresAtEpochMillis = 10_000L),
            )

        store.save("server-1", snapshot)
        store.remove("server-1")

        assertNull(store.load("server-1", "https://mcp.example.com"))
        assertNull(storage.read("server-1"))
    }
}
