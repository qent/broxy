package io.qent.broxy.core.capabilities

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.auth.AuthorizationStatusListener
import io.qent.broxy.core.models.McpServerConfig

typealias CapabilityFetcher =
    suspend (McpServerConfig, Int, Int, AuthorizationStatusListener?) -> Result<ServerCapabilities>
