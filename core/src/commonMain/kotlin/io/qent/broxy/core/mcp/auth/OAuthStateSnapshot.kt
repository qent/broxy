package io.qent.broxy.core.mcp.auth

import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class OAuthStateSnapshot(
    val resourceUrl: String? = null,
    val token: OAuthToken? = null,
    val registration: OAuthClientRegistration? = null,
    val registeredRedirectUri: String? = null,
    val resourceMetadata: ProtectedResourceMetadata? = null,
    val resourceMetadataUrl: String? = null,
    val authorizationMetadata: AuthorizationServerMetadata? = null,
    val authorizationServer: String? = null,
    val lastRequestedScope: String? = null,
)

/**
 * Callers must hold [OAuthState.mutex] before invoking this helper.
 */
fun OAuthState.toSnapshot(resourceUrl: String?): OAuthStateSnapshot =
    OAuthStateSnapshot(
        resourceUrl = resourceUrl,
        token = token,
        registration = registration,
        registeredRedirectUri = registeredRedirectUri,
        resourceMetadata = resourceMetadata,
        resourceMetadataUrl = resourceMetadataUrl,
        authorizationMetadata = authorizationMetadata,
        authorizationServer = authorizationServer,
        lastRequestedScope = lastRequestedScope,
    )

/**
 * Callers must hold [OAuthState.mutex] before invoking this helper.
 */
fun OAuthState.restoreFrom(snapshot: OAuthStateSnapshot) {
    token = snapshot.token
    registration = snapshot.registration
    registeredRedirectUri = snapshot.registeredRedirectUri
    resourceMetadata = snapshot.resourceMetadata
    resourceMetadataUrl = snapshot.resourceMetadataUrl
    authorizationMetadata = snapshot.authorizationMetadata
    authorizationServer = snapshot.authorizationServer
    lastRequestedScope = snapshot.lastRequestedScope
}

/**
 * Captures a snapshot while holding [OAuthState.mutex].
 */
suspend fun OAuthState.toSnapshotLocked(resourceUrl: String?): OAuthStateSnapshot =
    mutex.withLock {
        toSnapshot(resourceUrl)
    }

/**
 * Restores state while holding [OAuthState.mutex].
 */
suspend fun OAuthState.restoreFromLocked(snapshot: OAuthStateSnapshot) {
    mutex.withLock { restoreFrom(snapshot) }
}
