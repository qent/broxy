package io.qent.broxy.core.proxy.runtime

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.qent.broxy.core.proxy.inbound.buildSdkServer
import io.qent.broxy.core.utils.CollectingLogger
import io.qent.broxy.core.proxy.inbound.syncSdkServer as syncInboundSdkServer

/**
 * JVM-only adapter that exposes SDK server wiring without leaking ProxyMcpServer.
 */
class ProxyRuntimeSdkFacade private constructor(
    private val lifecycle: ProxyLifecycle,
) {
    companion object {
        fun from(runtime: ProxyRuntimeFacade): ProxyRuntimeSdkFacade {
            require(runtime is ProxyLifecycle) { "ProxyRuntimeFacade must be ProxyLifecycle" }
            return ProxyRuntimeSdkFacade(runtime)
        }
    }

    fun createSdkServer(logger: CollectingLogger): Server {
        val proxy = lifecycle.currentProxy() ?: error("Proxy is not running; cannot attach remote client")
        return buildSdkServer(proxy, logger)
    }

    fun syncSdkServer(
        server: Server,
        logger: CollectingLogger,
    ) {
        val proxy = lifecycle.currentProxy() ?: return
        syncInboundSdkServer(server, proxy, logger)
    }
}
