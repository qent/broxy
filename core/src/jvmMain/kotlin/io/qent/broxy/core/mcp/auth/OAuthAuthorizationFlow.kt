package io.qent.broxy.core.mcp.auth

import io.qent.broxy.core.utils.Logger

internal fun sendAuthorizationRequest(
    presenter: AuthorizationPresenter?,
    request: AuthorizationRequest,
    browserLauncher: BrowserLauncher,
    logger: Logger,
    resourceUrl: String,
) {
    if (presenter != null) {
        runCatching {
            presenter.onAuthorizationRequest(request)
        }.onFailure { ex ->
            logger.warn(
                "OAuth presenter failed to open authorization UI for $resourceUrl",
                ex,
            )
            throw ex
        }
    } else {
        logger.debug("OAuth authorization URL prepared for $resourceUrl; launching browser.")
        browserLauncher
            .open(request.authorizationUrl)
            .onSuccess {
                logger.debug("OAuth browser launch succeeded for $resourceUrl")
            }.onFailure {
                logger.info("Open this URL to authorize access: ${request.authorizationUrl}")
            }
    }
}

@Suppress("LongParameterList")
internal suspend fun awaitAuthorizationCode(
    receiver: AuthorizationCodeReceiver,
    authUrl: String,
    stateValue: String,
    timeoutMillis: Long,
    logger: Logger,
    resourceUrl: String,
): String {
    val timeoutLabel = if (timeoutMillis <= 0L) "none" else "${timeoutMillis}ms"
    logger.debug("OAuth awaiting authorization code for $resourceUrl timeout=$timeoutLabel")
    return try {
        receiver.awaitCode(authUrl, stateValue, timeoutMillis).getOrThrow()
    } finally {
        receiver.close()
    }
}
