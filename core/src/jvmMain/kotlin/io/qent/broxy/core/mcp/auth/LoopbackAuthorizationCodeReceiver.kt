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
                            "Authorization received. You can close this window.",
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
                    withTimeout(timeoutMillis) { deferred.await() }
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
