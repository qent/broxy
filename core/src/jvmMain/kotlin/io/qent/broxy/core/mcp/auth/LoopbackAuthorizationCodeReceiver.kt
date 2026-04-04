package io.qent.broxy.core.mcp.auth

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.util.Locale

class LoopbackAuthorizationCodeReceiver(
    redirectUriOverride: String?,
    private val logger: Logger,
    private val resourceUrl: String? = null,
) : AuthorizationCodeReceiver {
    private companion object {
        private const val STOP_GRACE_MILLIS = 500L
        private const val STOP_TIMEOUT_MILLIS = 1_000L

        private const val TITLE_PLACEHOLDER = "__TITLE__"
        private const val STATUS_VISUAL_PLACEHOLDER = "__STATUS_VISUAL__"

        private val completionPageStyles =
            """
            :root {
              color-scheme: dark;
              --primary: #818cf8;
              --surface: #1e293b;
              --surface-variant: #334155;
              --background: #0f172a;
              --text-primary: #dfdfdf;
              --text-secondary: #94a3b8;
              --success: #4ade80;
              --radius-md: 12px;
              --radius-lg: 16px;
              --font-sans: "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            }
            * {
              box-sizing: border-box;
            }
            body {
              margin: 0;
              min-height: 100vh;
              font-family: var(--font-sans);
              background: var(--background);
              color: var(--text-primary);
              display: flex;
              align-items: center;
              justify-content: center;
              padding: 32px 18px;
            }
            .card {
              width: min(560px, 100%);
              background: var(--surface);
              border-radius: var(--radius-lg);
              border: 1px solid var(--surface-variant);
              padding: 28px 30px;
              box-shadow: 0 24px 50px rgba(15, 23, 42, 0.35);
            }
            .status {
              display: flex;
              align-items: flex-start;
              gap: 16px;
              margin-bottom: 16px;
            }
            .status-icon {
              width: 48px;
              height: 48px;
              border-radius: var(--radius-md);
              display: grid;
              place-items: center;
              padding: 6px;
              background: #ffffff;
              border: 1px solid rgba(148, 163, 184, 0.45);
              overflow: hidden;
              flex-shrink: 0;
            }
            .status-icon svg {
              width: 100%;
              height: 100%;
              stroke: var(--success);
              stroke-width: 2.2;
              fill: none;
              stroke-linecap: round;
              stroke-linejoin: round;
            }
            .status-image {
              width: 100%;
              height: 100%;
              object-fit: contain;
              display: block;
            }
            h1 {
              font-size: 1.45rem;
              margin: 10px 0 0;
              font-weight: 700;
            }
            p {
              margin: 0;
              font-size: 0.95rem;
              line-height: 1.6;
              color: var(--text-secondary);
            }
            .divider {
              height: 1px;
              background: var(--surface-variant);
              margin: 20px 0 18px;
            }
            .meta {
              display: grid;
              gap: 12px;
            }
            .meta-row {
              display: flex;
              align-items: baseline;
              justify-content: space-between;
              gap: 16px;
              font-size: 0.85rem;
            }
            .meta-label {
              color: var(--text-secondary);
              text-transform: uppercase;
              letter-spacing: 0.08em;
              font-size: 0.7rem;
            }
            .meta-value {
              color: var(--text-primary);
              font-weight: 600;
            }
            .meta-value.success {
              color: var(--success);
            }
            .footer-note {
              margin-top: 18px;
              font-size: 0.85rem;
              color: var(--text-secondary);
            }
            @media (max-width: 560px) {
              .card {
                padding: 22px;
              }
              .status {
                flex-direction: column;
                align-items: flex-start;
              }
              .meta-row {
                flex-direction: column;
                align-items: flex-start;
              }
            }
            """.trimIndent()

        private val completionPageTemplate =
            """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>$TITLE_PLACEHOLDER</title>
                <style>
                  $completionPageStyles
                </style>
              </head>
              <body>
                <div class="card">
                  <div class="status">
                    <span class="status-icon" aria-hidden="true">
                      $STATUS_VISUAL_PLACEHOLDER
                    </span>
                    <div>
                      <h1>$TITLE_PLACEHOLDER</h1>
                    </div>
                  </div>
                  <p>OAuth client authorization succeeded. You can return to Broxy and continue setup.</p>
                  <div class="divider"></div>
                  <div class="meta">
                    <div class="meta-row">
                      <span class="meta-label">Status</span>
                      <span class="meta-value success">Success</span>
                    </div>
                    <div class="meta-row">
                      <span class="meta-label">Next step</span>
                      <span class="meta-value">Close this tab</span>
                    </div>
                  </div>
                  <div class="footer-note">This window can be closed safely.</div>
                </div>
              </body>
            </html>
            """.trimIndent()

        private val fallbackStatusVisual =
            """
            <svg viewBox="0 0 24 24" role="img">
              <path d="M5 13l4 4L19 7"></path>
            </svg>
            """.trimIndent()
    }

    private data class CallbackParams(
        val code: String?,
        val state: String?,
        val error: String?,
        val errorDescription: String?,
    )

    private val deferred = CompletableDeferred<CallbackParams>()
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    override val redirectUri: String

    init {
        val overrideUri = redirectUriOverride?.let(::parseUri)
        if (overrideUri != null) {
            val scheme = overrideUri.scheme?.lowercase()
            require(scheme == "http") { "Loopback redirect URI must use http scheme." }
            require(overrideUri.host == "localhost" || overrideUri.host == "127.0.0.1") {
                "Loopback redirect URI must target localhost."
            }
            require(overrideUri.port != -1) { "Loopback redirect URI must include an explicit port." }
        }
        val host = overrideUri?.host ?: "127.0.0.1"
        val path = overrideUri?.path?.takeIf { it.isNotBlank() } ?: "/oauth/callback"
        val port = overrideUri?.port ?: 0

        server =
            embeddedServer(Netty, host = host, port = port) {
                routing {
                    get(path) {
                        logger.debug("OAuth callback received on $path")
                        if (!deferred.isCompleted) {
                            deferred.complete(
                                CallbackParams(
                                    code = call.request.queryParameters["code"],
                                    state = call.request.queryParameters["state"],
                                    error = call.request.queryParameters["error"],
                                    errorDescription = call.request.queryParameters["error_description"],
                                ),
                            )
                        }
                        val completionContext = resolveCompletionPageContext()
                        call.respondText(renderCompletionPage(completionContext), ContentType.Text.Html)
                    }
                }
            }
        server.start(wait = false)
        val actualPort =
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .firstOrNull()
                    ?.port
            } ?: port
        redirectUri = URI("http", null, host, actualPort, path, null, null).toString()
        logger.info("OAuth callback listening at $redirectUri")
    }

    override suspend fun awaitCode(
        authorizationUrl: String,
        expectedState: String,
        timeoutMillis: Long,
    ): Result<String> =
        runCatching {
            val params =
                try {
                    if (timeoutMillis <= 0L) {
                        deferred.await()
                    } else {
                        withTimeout(timeoutMillis) { deferred.await() }
                    }
                } catch (ex: TimeoutCancellationException) {
                    throw IllegalStateException(
                        "Timed out waiting for OAuth authorization response.",
                        ex,
                    )
                }
            if (!params.error.isNullOrBlank()) {
                if (params.error == "access_denied" && params.errorDescription == "cancelled_by_user") {
                    throw CancellationException("OAuth authorization cancelled by user.")
                }
                val desc = params.errorDescription?.let { ": $it" } ?: ""
                error("OAuth authorization failed (${params.error})$desc")
            }
            if (params.state != expectedState) {
                error("OAuth state mismatch; discarding authorization response.")
            }
            params.code ?: error("OAuth authorization response missing code.")
        }.also {
            close()
        }

    override fun close() {
        runCatching { server.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS) }
            .onFailure { error ->
                logger.warn("Failed to stop OAuth callback server: ${error.message}", error)
            }
    }

    private fun parseUri(value: String): URI =
        runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("Invalid redirect URI '$value'") }

    private fun resolveCompletionPageContext(): AuthorizationCompletionPageContext? {
        val authResourceUrl = resourceUrl?.trim()?.takeIf { it.isNotEmpty() }
        val presenter = AuthorizationPresenterRegistry.current()
        if (authResourceUrl == null || presenter == null) {
            return null
        }
        return runCatching { presenter.resolveCompletionPageContext(authResourceUrl) }
            .onFailure { ex ->
                logger.warn("Failed to resolve completion page context for $authResourceUrl", ex)
            }.getOrNull()
    }

    private fun renderCompletionPage(context: AuthorizationCompletionPageContext?): String {
        val titleText = resolvePageTitle(context)
        val statusVisual = renderStatusVisual(context)
        return completionPageTemplate
            .replace(TITLE_PLACEHOLDER, titleText)
            .replace(STATUS_VISUAL_PLACEHOLDER, statusVisual)
    }

    private fun resolvePageTitle(context: AuthorizationCompletionPageContext?): String {
        val serverName = context?.serverName?.trim()?.takeIf { it.isNotEmpty() }
        val title = if (serverName != null) "$serverName Authorized" else "Authorization complete"
        return escapeHtml(title)
    }

    private fun renderStatusVisual(context: AuthorizationCompletionPageContext?): String {
        val iconUrl = sanitizeIconUrl(context?.iconUrl)
        return if (iconUrl != null) {
            """
            <img class="status-image" src="${escapeHtml(iconUrl)}" alt="">
            """.trimIndent()
        } else {
            fallbackStatusVisual
        }
    }

    private fun sanitizeIconUrl(url: String?): String? {
        val trimmed = url?.trim()?.takeIf { it.isNotEmpty() }
        val parsed = trimmed?.let { runCatching { URI(it) }.getOrNull() }
        val scheme = parsed?.scheme?.lowercase(Locale.ROOT)
        val isHttp = scheme == "http" || scheme == "https"
        return if (isHttp) trimmed else null
    }

    private fun escapeHtml(value: String): String =
        buildString(value.length) {
            value.forEach { ch ->
                append(
                    when (ch) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&#39;"
                        else -> ch
                    },
                )
            }
        }
}
