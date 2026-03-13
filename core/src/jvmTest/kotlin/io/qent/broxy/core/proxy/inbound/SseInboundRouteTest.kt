package io.qent.broxy.core.proxy.inbound

import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.qent.broxy.core.proxy.ProxyMcpServer
import io.qent.broxy.core.utils.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

class SseInboundRouteTest {
    @Test
    fun sse_post_requires_session_id() =
        testApplication {
            val proxy = ProxyMcpServer(emptyList(), logger = NoopLogger)
            val server = buildSdkServer(proxy, NoopLogger)
            val registry = InboundSseRegistry(NoopLogger)

            application {
                install(SSE)
                routing {
                    route("/sse") {
                        mountSseRoute(server, registry)
                    }
                }
            }

            val response = client.post("/sse")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun sse_post_rejects_unknown_session() =
        testApplication {
            val proxy = ProxyMcpServer(emptyList(), logger = NoopLogger)
            val server = buildSdkServer(proxy, NoopLogger)
            val registry = InboundSseRegistry(NoopLogger)

            application {
                install(SSE)
                routing {
                    route("/sse") {
                        mountSseRoute(server, registry)
                    }
                }
            }

            val response = client.post("/sse?sessionId=missing")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    private object NoopLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
}
