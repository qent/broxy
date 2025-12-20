package io.qent.broxy.cloud.api

import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport

interface CloudProxyRuntime {
    fun isRunning(): Boolean

    suspend fun createSession(transport: AbstractTransport): CloudServerSession
}

interface CloudServerSession {
    fun onClose(handler: () -> Unit)
}
