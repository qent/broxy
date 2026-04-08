package io.qent.broxy.ui.adapter.clients

import io.qent.broxy.ui.adapter.clients.common.BroxyServerEntry
import io.qent.broxy.ui.adapter.clients.common.DEFAULT_BROXY_SERVER_NAME
import io.qent.broxy.ui.adapter.clients.formats.toml.TomlMcpServerListFormat
import io.qent.broxy.ui.adapter.models.UiAiClientBroxyConfigMismatchNotice
import io.qent.broxy.ui.adapter.models.UiAiClientMissingConfigNotice
import io.qent.broxy.ui.adapter.models.UiAiClientNoticeSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class CodexClientConnector(
    private val configPath: Path = defaultConfigPath(),
    private val displayPath: String = DEFAULT_DISPLAY_PATH,
) : AiClientConnector {
    private val format = TomlMcpServerListFormat()

    override val descriptor =
        AiClientDescriptor(
            id = "codex",
            name = "Codex",
            description = "One agent for everywhere you code",
            iconId = "codex",
            infoUrl = "https://openai.com/codex/",
        )

    override suspend fun loadStatus(request: AiClientConnectionRequest): Result<AiClientStatus> =
        withContext(Dispatchers.IO) {
            runCatching { loadStatusInternal(request) }
        }

    override suspend fun loadImportableServers(): Result<List<AiClientImportServer>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!Files.exists(configPath)) return@runCatching emptyList()
                val content = Files.readString(configPath)
                format
                    .listServerEntries(content)
                    .mapNotNull { it.toImportServerOrNull() }
            }
        }

    override suspend fun connect(request: AiClientConnectionRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConfigExists()
                val content = Files.readString(configPath)
                val updated =
                    format.upsertBroxy(
                        content = content,
                        serverName = DEFAULT_BROXY_SERVER_NAME,
                        entry = BroxyServerEntry.UrlEntry(request.httpEndpoint),
                    )
                if (updated != content) {
                    Files.writeString(configPath, updated)
                }
            }
        }

    override suspend fun disconnect(request: AiClientConnectionRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConfigExists()
                val content = Files.readString(configPath)
                val updated = format.removeBroxy(content = content, serverName = DEFAULT_BROXY_SERVER_NAME)
                if (updated != content) {
                    Files.writeString(configPath, updated)
                }
            }
        }

    private fun ensureConfigExists() {
        if (!Files.exists(configPath)) {
            error("$displayPath was not found.")
        }
    }

    private fun loadStatusInternal(request: AiClientConnectionRequest): AiClientStatus {
        if (!Files.exists(configPath)) {
            return AiClientStatus(
                isConnected = false,
                canConnect = false,
                notice =
                    UiAiClientMissingConfigNotice(
                        clientName = descriptor.name,
                        severity = UiAiClientNoticeSeverity.Error,
                    ),
            )
        }

        val content = Files.readString(configPath)
        val status = format.readBroxyStatus(content = content, serverName = DEFAULT_BROXY_SERVER_NAME)
        if (!status.isConfigured) {
            return AiClientStatus(isConnected = false, canConnect = true)
        }
        if (status.configuredUrl == request.httpEndpoint) {
            return AiClientStatus(isConnected = true, canConnect = true)
        }

        return AiClientStatus(
            isConnected = false,
            canConnect = true,
            notice =
                UiAiClientBroxyConfigMismatchNotice(
                    configuredUrl = status.configuredUrl,
                    severity = UiAiClientNoticeSeverity.Warning,
                ),
        )
    }

    private companion object {
        private const val DEFAULT_DISPLAY_PATH = "~/.codex/config.toml"

        private fun defaultConfigPath(): Path = Paths.get(System.getProperty("user.home"), ".codex", "config.toml")
    }
}
