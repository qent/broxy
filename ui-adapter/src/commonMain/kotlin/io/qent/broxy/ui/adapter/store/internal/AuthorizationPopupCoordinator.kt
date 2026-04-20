package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.mcp.auth.AuthorizationCompletionPageContext
import io.qent.broxy.core.mcp.auth.AuthorizationPresenter
import io.qent.broxy.core.mcp.auth.AuthorizationRequest
import io.qent.broxy.core.mcp.auth.AuthorizationResult
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.icons.ServerIconResolver
import io.qent.broxy.ui.adapter.models.UiAuthorizationPopup
import io.qent.broxy.ui.adapter.models.UiAuthorizationPopupStatus
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiServerIcon
import io.qent.broxy.ui.adapter.services.resolveAuthResourceUrl
import io.qent.broxy.ui.adapter.store.Intents

internal class AuthorizationPopupCoordinator(
    private val state: StoreStateAccess,
    private val intents: Intents,
    private val publishReady: () -> Unit,
    private val logger: Logger,
) : AuthorizationPresenter {
    override fun onAuthorizationRequest(request: AuthorizationRequest) {
        val server = resolveServer(request.resourceUrl)
        if (server == null) {
            logInfo(
                logger,
                "authorizationRequest",
                "ignored for unknown resource '${request.resourceUrl}'",
            )
            return
        }
        val popup =
            UiAuthorizationPopup(
                serverId = server.id,
                serverName = server.name,
                resourceUrl = request.resourceUrl,
                authorizationUrl = request.authorizationUrl,
                redirectUri = request.redirectUri,
                status = UiAuthorizationPopupStatus.AwaitingBrowserPermission,
            )
        state.updateSnapshot {
            val activePopup = authorizationPopup
            val deduplicatedQueue = authorizationPopupQueue.filterNot { it.serverId == server.id }
            when {
                activePopup == null || activePopup.serverId == server.id ->
                    copy(
                        authorizationPopup = popup,
                        authorizationPopupQueue = deduplicatedQueue,
                    )

                else ->
                    copy(
                        authorizationPopupQueue = deduplicatedQueue + popup,
                    )
            }
        }
        publishReady()
    }

    override fun onAuthorizationResult(result: AuthorizationResult) {
        val server = resolveServer(result.resourceUrl)
        if (server == null) {
            logInfo(
                logger,
                "authorizationResult",
                "ignored for unknown resource '${result.resourceUrl}'",
            )
            return
        }
        val shouldDisableServer =
            result is AuthorizationResult.Cancelled ||
                result is AuthorizationResult.Failure
        when (result) {
            is AuthorizationResult.Success -> {
                state.updateSnapshot {
                    val activePopup = authorizationPopup
                    val deduplicatedQueue = authorizationPopupQueue.filterNot { it.serverId == server.id }
                    if (activePopup?.serverId == server.id) {
                        copy(
                            authorizationPopup = activePopup.copy(status = UiAuthorizationPopupStatus.Success),
                            authorizationPopupQueue = deduplicatedQueue,
                        )
                    } else if (deduplicatedQueue != authorizationPopupQueue) {
                        copy(authorizationPopupQueue = deduplicatedQueue)
                    } else {
                        this
                    }
                }
                publishReady()
                intents.refreshServerCapabilities(server.id)
            }
            is AuthorizationResult.Cancelled,
            is AuthorizationResult.Failure,
            -> {
                state.updateSnapshot {
                    val activePopup = authorizationPopup
                    val deduplicatedQueue = authorizationPopupQueue.filterNot { it.serverId == server.id }
                    if (activePopup?.serverId == server.id) {
                        val (nextPopup, remainingQueue) = dequeueNextPopup(deduplicatedQueue)
                        copy(
                            authorizationPopup = nextPopup,
                            authorizationPopupQueue = remainingQueue,
                        )
                    } else if (deduplicatedQueue != authorizationPopupQueue) {
                        copy(authorizationPopupQueue = deduplicatedQueue)
                    } else {
                        this
                    }
                }
                publishReady()
                if (shouldDisableServer && server.enabled) {
                    intents.toggleServer(server.id, enabled = false)
                }
            }
        }
    }

    override fun resolveCompletionPageContext(resourceUrl: String): AuthorizationCompletionPageContext? {
        val server = resolveServer(resourceUrl) ?: return null
        val registryIconUrls = ServerIconResolver.registryIconUrls(state.snapshot.catalogServerEntries)
        val icon = ServerIconResolver.resolve(server, registryIconUrls)
        val iconUrl = (icon as? UiServerIcon.Remote)?.url?.trim()?.takeIf { it.isNotEmpty() }
        return AuthorizationCompletionPageContext(
            serverName = server.name,
            iconUrl = iconUrl,
        )
    }

    private fun resolveServer(resourceUrl: String): UiMcpServerConfig? =
        state.snapshot.servers.firstOrNull { resolveAuthResourceUrl(it) == resourceUrl }

    private fun dequeueNextPopup(queue: List<UiAuthorizationPopup>): Pair<UiAuthorizationPopup?, List<UiAuthorizationPopup>> {
        val next = queue.firstOrNull()
        return if (next == null) {
            null to emptyList()
        } else {
            next to queue.drop(1)
        }
    }
}
