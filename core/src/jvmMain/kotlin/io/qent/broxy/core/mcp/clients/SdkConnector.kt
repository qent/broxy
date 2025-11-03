package io.qent.broxy.core.mcp.clients

/**
 * Strategy for creating/connecting an SDK facade.
 * Used to override real network/process connections in tests.
 */
fun interface SdkConnector {
    suspend fun connect(): SdkClientFacade
}

