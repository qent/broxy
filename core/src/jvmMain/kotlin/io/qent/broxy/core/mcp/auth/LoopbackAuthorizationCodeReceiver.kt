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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI

class LoopbackAuthorizationCodeReceiver(
    redirectUriOverride: String?,
    private val logger: Logger,
) : AuthorizationCodeReceiver {
    private data class CallbackParams(
        val code: String?,
        val state: String?,
        val error: String?,
        val errorDescription: String?,
    )

    private val deferred = CompletableDeferred<CallbackParams>()
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
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
                        call.respondText(
                            """
                            <!doctype html>
                            <html lang="en">
                              <head>
                                <meta charset="utf-8">
                                <meta name="viewport" content="width=device-width, initial-scale=1">
                                <title>Authorization complete</title>
                                <style>
                                  :root { color-scheme: light; }
                                  body {
                                    margin: 0;
                                    padding: 32px;
                                    font-family: "Inter", "Segoe UI", "Helvetica Neue", Arial, sans-serif;
                                    background: #f6f7fb;
                                    color: #1c1c1f;
                                  }
                                  .card {
                                    max-width: 520px;
                                    margin: 10vh auto 0;
                                    background: #ffffff;
                                    border-radius: 16px;
                                    border: 1px solid #e6e7ec;
                                    box-shadow: 0 16px 40px rgba(24, 29, 40, 0.12);
                                    padding: 28px 32px;
                                  }
                                  .badge {
                                    display: inline-flex;
                                    align-items: center;
                                    justify-content: center;
                                    padding: 4px 10px;
                                    font-size: 12px;
                                    letter-spacing: 0.08em;
                                    text-transform: uppercase;
                                    border-radius: 999px;
                                    background: #e7f5ec;
                                    color: #1c6b3c;
                                    margin-bottom: 16px;
                                  }
                                  h1 {
                                    font-size: 22px;
                                    margin: 0 0 12px;
                                    font-weight: 600;
                                  }
                                  p {
                                    margin: 0;
                                    font-size: 15px;
                                    line-height: 1.6;
                                    color: #52545b;
                                  }
                                </style>
                              </head>
                              <body>
                                <div class="card">
                                  <div class="badge">Authorized</div>
                                  <h1>Authorization complete</h1>
                                  <p>You can return to Broxy. This window will close automatically.</p>
                                </div>
                              </body>
                            </html>
                            """.trimIndent(),
                            ContentType.Text.Html,
                        )
                    }
                }
            }
        server.start(wait = false)
        val actualPort = runBlocking { server.engine.resolvedConnectors().firstOrNull()?.port } ?: port
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
                    throw IllegalStateException("Timed out waiting for OAuth authorization response.")
                }
            if (!params.error.isNullOrBlank()) {
                val desc = params.errorDescription?.let { ": $it" } ?: ""
                throw IllegalStateException("OAuth authorization failed (${params.error})$desc")
            }
            if (params.state != expectedState) {
                throw IllegalStateException("OAuth state mismatch; discarding authorization response.")
            }
            params.code ?: throw IllegalStateException("OAuth authorization response missing code.")
        }.also {
            close()
        }

    override fun close() {
        runCatching { server.stop(500, 1_000) }
    }

    private fun parseUri(value: String): URI {
        return runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("Invalid redirect URI '$value'") }
    }
}
