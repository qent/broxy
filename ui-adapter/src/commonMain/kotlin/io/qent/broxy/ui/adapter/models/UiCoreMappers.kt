package io.qent.broxy.ui.adapter.models

import io.qent.broxy.core.models.AgentToolReference
import io.qent.broxy.core.models.AuthConfig
import io.qent.broxy.core.models.McpServerConfig
import io.qent.broxy.core.models.McpServersConfig
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.PromptReference
import io.qent.broxy.core.models.ResourceReference
import io.qent.broxy.core.models.ToolReference
import io.qent.broxy.core.models.TransportConfig

fun McpServersConfig.toUi(): UiMcpServersConfig =
    UiMcpServersConfig(
        servers = servers.map { it.toUi() },
        mcpFilePath = mcpFilePath,
        defaultPresetId = defaultPresetId,
        inboundHttpPort = inboundHttpPort,
        requestTimeoutSeconds = requestTimeoutSeconds,
        capabilitiesTimeoutSeconds = capabilitiesTimeoutSeconds,
        authorizationTimeoutSeconds = authorizationTimeoutSeconds,
        connectionRetryCount = connectionRetryCount,
        ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors,
        capabilitiesRefreshIntervalSeconds = capabilitiesRefreshIntervalSeconds,
        fallbackPromptsAndResourcesToTools = fallbackPromptsAndResourcesToTools,
        adapterMode = adapterMode,
    )

fun UiMcpServersConfig.toCore(): McpServersConfig =
    McpServersConfig(
        servers = servers.map { it.toCore() },
        mcpFilePath = mcpFilePath,
        defaultPresetId = defaultPresetId,
        inboundHttpPort = inboundHttpPort,
        requestTimeoutSeconds = requestTimeoutSeconds,
        capabilitiesTimeoutSeconds = capabilitiesTimeoutSeconds,
        authorizationTimeoutSeconds = authorizationTimeoutSeconds,
        connectionRetryCount = connectionRetryCount,
        ignoreHttpsCertificateErrors = ignoreHttpsCertificateErrors,
        capabilitiesRefreshIntervalSeconds = capabilitiesRefreshIntervalSeconds,
        fallbackPromptsAndResourcesToTools = fallbackPromptsAndResourcesToTools,
        adapterMode = adapterMode,
    )

fun McpServerConfig.toUi(): UiMcpServerConfig =
    UiMcpServerConfig(
        id = id,
        name = name,
        transport = transport.toUi(),
        env = env,
        enabled = enabled,
        auth = auth?.toUi(),
        envFile = envFile,
        iconPath = iconPath,
    )

fun UiMcpServerConfig.toCore(): McpServerConfig =
    McpServerConfig(
        id = id,
        name = name,
        transport = transport.toCore(),
        env = env,
        enabled = enabled,
        auth = auth?.toCore(),
        envFile = envFile,
        iconPath = iconPath,
    )

fun TransportConfig.toUi(): UiTransportConfig =
    when (this) {
        is TransportConfig.StdioTransport -> UiStdioTransport(command = command, args = args)
        is TransportConfig.HttpTransport -> UiHttpTransport(url = url, headers = headers)
        is TransportConfig.StreamableHttpTransport -> UiStreamableHttpTransport(url = url, headers = headers)
        is TransportConfig.WebSocketTransport -> UiWebSocketTransport(url = url, headers = headers)
    }

fun UiTransportConfig.toCore(): TransportConfig =
    when (this) {
        is UiStdioTransport -> TransportConfig.StdioTransport(command = command, args = args)
        is UiHttpTransport -> TransportConfig.HttpTransport(url = url, headers = headers)
        is UiStreamableHttpTransport -> TransportConfig.StreamableHttpTransport(url = url, headers = headers)
        is UiWebSocketTransport -> TransportConfig.WebSocketTransport(url = url, headers = headers)
    }

fun AuthConfig.toUi(): UiAuthConfig =
    when (this) {
        is AuthConfig.OAuth ->
            UiAuthConfig.OAuth(
                clientId = clientId,
                clientSecret = clientSecret,
                callbackPort = callbackPort,
                clientIdMetadataUrl = clientIdMetadataUrl,
                authServerMetadataUrl = authServerMetadataUrl,
                redirectUri = redirectUri,
                clientName = clientName,
                tokenEndpointAuthMethod = tokenEndpointAuthMethod,
                authorizationServer = authorizationServer,
                scopes = scopes,
                allowDynamicRegistration = allowDynamicRegistration,
            )
    }

fun UiAuthConfig.toCore(): AuthConfig =
    when (this) {
        is UiAuthConfig.OAuth ->
            AuthConfig.OAuth(
                clientId = clientId,
                clientSecret = clientSecret,
                callbackPort = callbackPort,
                clientIdMetadataUrl = clientIdMetadataUrl,
                authServerMetadataUrl = authServerMetadataUrl,
                redirectUri = redirectUri,
                clientName = clientName,
                tokenEndpointAuthMethod = tokenEndpointAuthMethod,
                authorizationServer = authorizationServer,
                scopes = scopes,
                allowDynamicRegistration = allowDynamicRegistration,
            )
    }

fun Preset.toUi(): UiPresetCore =
    UiPresetCore(
        id = id,
        name = name,
        tools = tools.map { it.toUi() },
        agentTools = agentTools.map { it.toUi() },
        prompts = prompts?.map { it.toUi() },
        resources = resources?.map { it.toUi() },
        orderIndex = orderIndex,
    )

fun UiPresetCore.toCore(): Preset =
    Preset(
        id = id,
        name = name,
        tools = tools.map { it.toCore() },
        agentTools = agentTools.map { it.toCore() },
        prompts = prompts?.map { it.toCore() },
        resources = resources?.map { it.toCore() },
        orderIndex = orderIndex,
    )

fun ToolReference.toUi(): UiToolRef =
    UiToolRef(
        serverId = serverId,
        toolName = toolName,
        enabled = enabled,
    )

fun UiToolRef.toCore(): ToolReference =
    ToolReference(
        serverId = serverId,
        toolName = toolName,
        enabled = enabled,
    )

fun AgentToolReference.toUi(): UiAgentToolRef =
    UiAgentToolRef(
        agentId = agentId,
        enabled = enabled,
    )

fun UiAgentToolRef.toCore(): AgentToolReference =
    AgentToolReference(
        agentId = agentId,
        enabled = enabled,
    )

fun PromptReference.toUi(): UiPromptRef =
    UiPromptRef(
        serverId = serverId,
        promptName = promptName,
        enabled = enabled,
    )

fun UiPromptRef.toCore(): PromptReference =
    PromptReference(
        serverId = serverId,
        promptName = promptName,
        enabled = enabled,
    )

fun ResourceReference.toUi(): UiResourceRef =
    UiResourceRef(
        serverId = serverId,
        resourceKey = resourceKey,
        enabled = enabled,
    )

fun UiResourceRef.toCore(): ResourceReference =
    ResourceReference(
        serverId = serverId,
        resourceKey = resourceKey,
        enabled = enabled,
    )

fun List<UiMcpServerConfig>.toCore(): List<McpServerConfig> = map { it.toCore() }
