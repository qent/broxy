package io.qent.broxy.core.config

import java.nio.file.Paths

internal data class ConfigDefaults(
    val mcpFilePath: String = DEFAULT_MCP_FILE_PATH,
    val requestTimeoutSeconds: Int = 60,
    val capabilitiesTimeoutSeconds: Int = 30,
    val authorizationTimeoutSeconds: Int = 120,
    val connectionRetryCount: Int = 3,
    val ignoreHttpsCertificateErrors: Boolean = false,
    val capabilitiesRefreshIntervalSeconds: Int = 300,
    val inboundHttpPort: Int = 3335,
)

private val DEFAULT_MCP_FILE_PATH: String =
    Paths.get(System.getProperty("user.home"), ".config", "broxy", "mcp.json").toString()
