package io.qent.broxy.core.proxy.inbound

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.qent.broxy.core.proxy.ProxyMcpServer
import kotlin.test.Test
import kotlin.test.assertEquals

class SseInboundRouteTest {
    @Test
    fun sse_post_requires_session_id() =
        testApplication {
            val proxy = ProxyMcpServer(emptyList(), logger = NoopLogger)
            val registry = InboundSseRegistry(NoopLogger)

            application {
                install(SSE)
                routing {
                    route("/sse") {
                        mountSseRoute(
                            sessions = registry,
                            bindingProvider = { InboundSessionBinding("/sse") },
                            sessionFactory = { binding, transport -> createSseSession(proxy, binding, transport) },
                        )
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
            val registry = InboundSseRegistry(NoopLogger)

            application {
                install(SSE)
                routing {
                    route("/sse") {
                        mountSseRoute(
                            sessions = registry,
                            bindingProvider = { InboundSessionBinding("/sse") },
                            sessionFactory = { binding, transport -> createSseSession(proxy, binding, transport) },
                        )
                    }
                }
            }

            val response = client.post("/sse?sessionId=missing")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun sse_get_forced_preset_returns_404_when_preset_missing() =
        testApplication {
            val proxy = ProxyMcpServer(emptyList(), logger = NoopLogger)
            val registry = InboundSseRegistry(NoopLogger)

            application {
                install(SSE)
                routing {
                    route("/sse") {
                        route("/{presetId}") {
                            mountSseRoute(
                                sessions = registry,
                                bindingProvider = { call ->
                                    throw InboundPresetNotFoundException(requireNotNull(call.parameters["presetId"]))
                                },
                                sessionFactory = { binding, transport -> createSseSession(proxy, binding, transport) },
                            )
                        }
                    }
                }
            }

            val response = client.get("/sse/missing")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}

private suspend fun createSseSession(
    proxy: ProxyMcpServer,
    binding: InboundSessionBinding,
    transport: io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport,
): SseSession {
    val sdkServer = buildSdkServer(proxy, NoopLogger)
    val serverSession = sdkServer.createSession(transport)
    return SseSession(
        binding = binding,
        proxy = proxy,
        sdkServer = sdkServer,
        transport = transport,
        serverSession = serverSession,
        ownsProxy = false,
    )
}
