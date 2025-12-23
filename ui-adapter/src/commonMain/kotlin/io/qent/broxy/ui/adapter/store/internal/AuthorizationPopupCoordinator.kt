package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.core.mcp.auth.AuthorizationPresenter
import io.qent.broxy.core.mcp.auth.AuthorizationRequest
import io.qent.broxy.core.mcp.auth.AuthorizationResult
import io.qent.broxy.core.utils.Logger
import io.qent.broxy.ui.adapter.models.UiAuthorizationPopup
import io.qent.broxy.ui.adapter.models.UiAuthorizationPopupStatus
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
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
            logger.info("[AppStore] Authorization request ignored for unknown resource '${request.resourceUrl}'")
            return
        }
        state.updateSnapshot {
            copy(
                authorizationPopup =
                    UiAuthorizationPopup(
                        serverId = server.id,
                        serverName = server.name,
                        resourceUrl = request.resourceUrl,
                        authorizationUrl = request.authorizationUrl,
                        redirectUri = request.redirectUri,
                        status = UiAuthorizationPopupStatus.Pending,
                    ),
            )
        }
        publishReady()
    }

    override fun onAuthorizationResult(result: AuthorizationResult) {
        val server = resolveServer(result.resourceUrl)
        if (server == null) {
            logger.info("[AppStore] Authorization result ignored for unknown resource '${result.resourceUrl}'")
            return
        }
        when (result) {
            is AuthorizationResult.Success -> {
                state.updateSnapshot {
                    val popup = authorizationPopup
                    if (popup != null && popup.serverId == server.id) {
                        copy(authorizationPopup = popup.copy(status = UiAuthorizationPopupStatus.Success))
                    } else {
                        this
                    }
                }
                publishReady()
            }
            is AuthorizationResult.Cancelled,
            is AuthorizationResult.Failure,
            -> {
                state.updateSnapshot {
                    val popup = authorizationPopup
                    if (popup != null && popup.serverId == server.id) {
                        copy(authorizationPopup = null)
                    } else {
                        this
                    }
                }
                publishReady()
                if (server.enabled) {
                    intents.toggleServer(server.id, enabled = false)
                }
            }
        }
    }

    private fun resolveServer(resourceUrl: String): UiMcpServerConfig? =
        state.snapshot.servers.firstOrNull { resolveAuthResourceUrl(it) == resourceUrl }
}
