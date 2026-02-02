package io.qent.broxy.ui.adapter.clients

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

    override suspend fun connect(request: AiClientConnectionRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConfigExists()
                val config = readConfigLines(configPath)
                val updatedLines = normalizeBlankLines(upsertBroxySection(config.lines, request.httpEndpoint))
                if (updatedLines != config.lines) {
                    writeConfigLines(configPath, updatedLines, config.lineSeparator)
                }
            }
        }

    override suspend fun disconnect(request: AiClientConnectionRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConfigExists()
                val config = readConfigLines(configPath)
                val updatedLines = normalizeBlankLines(removeBroxySection(config.lines))
                if (updatedLines != config.lines) {
                    writeConfigLines(configPath, updatedLines, config.lineSeparator)
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
        val config = readConfigLines(configPath)
        val section = findBroxySection(config.lines) ?: return AiClientStatus(isConnected = false, canConnect = true)
        val targetUrl = request.httpEndpoint
        val configuredUrl = section.url
        if (configuredUrl == targetUrl) {
            return AiClientStatus(isConnected = true, canConnect = true)
        }
        return AiClientStatus(
            isConnected = false,
            canConnect = true,
            notice =
                UiAiClientBroxyConfigMismatchNotice(
                    configuredUrl = configuredUrl,
                    severity = UiAiClientNoticeSeverity.Warning,
                ),
        )
    }

    private data class ConfigLines(
        val lines: List<String>,
        val lineSeparator: String,
    )

    private data class Section(
        val start: Int,
        val end: Int,
        val url: String?,
    )

    private fun readConfigLines(path: Path): ConfigLines {
        val content = Files.readString(path)
        val separator = if (content.contains("\r\n")) "\r\n" else "\n"
        val baseLines =
            if (content.isEmpty()) {
                emptyList()
            } else {
                content.split(separator)
            }
        val lines =
            if (content.endsWith(separator)) {
                baseLines + ""
            } else {
                baseLines
            }
        return ConfigLines(lines, separator)
    }

    private fun writeConfigLines(
        path: Path,
        lines: List<String>,
        separator: String,
    ) {
        val content =
            if (lines.isEmpty()) {
                ""
            } else {
                lines.joinToString(separator)
            }
        Files.writeString(path, content)
    }

    private fun findBroxySection(lines: List<String>): Section? {
        val headerRegex = Regex("^\\s*\\[mcp_servers\\.$SERVER_NAME]\\s*$")
        val sectionHeaderRegex = Regex("^\\s*\\[[^]]+\\]\\s*$")
        val urlRegex = Regex("^\\s*url\\s*=\\s*\"([^\"]+)\"\\s*$")

        val start = lines.indexOfFirst { headerRegex.matches(it) }
        if (start < 0) return null
        var end = start + 1
        while (end < lines.size && !sectionHeaderRegex.matches(lines[end])) {
            end++
        }
        var url: String? = null
        for (index in (start + 1) until end) {
            val match = urlRegex.matchEntire(lines[index])
            if (match != null) {
                url = match.groupValues[1]
                break
            }
        }
        return Section(start = start, end = end, url = url)
    }

    private fun upsertBroxySection(
        lines: List<String>,
        url: String,
    ): List<String> {
        val block = listOf("[mcp_servers.$SERVER_NAME]", "url = \"$url\"")
        val section = findBroxySection(lines)
        val updated = lines.toMutableList()
        var insertAt =
            if (section == null) {
                updated.size
            } else {
                updated.subList(section.start, section.end).clear()
                section.start
            }
        if (insertAt > updated.size) {
            insertAt = updated.size
        }
        if (insertAt == updated.size && updated.isNotEmpty() && updated.last().isBlank()) {
            insertAt -= 1
        }
        if (insertAt > 0 && updated[insertAt - 1].isNotBlank()) {
            updated.add(insertAt, "")
            insertAt += 1
        } else if (insertAt > 0 && insertAt < updated.size && updated[insertAt - 1].isBlank() && updated[insertAt].isBlank()) {
            updated.removeAt(insertAt)
        }
        updated.addAll(insertAt, block)
        val afterIndex = insertAt + block.size
        if (afterIndex >= updated.size) {
            updated.add("")
        } else if (updated[afterIndex].isNotBlank()) {
            updated.add(afterIndex, "")
        } else if (afterIndex + 1 < updated.size && updated[afterIndex + 1].isBlank()) {
            updated.removeAt(afterIndex + 1)
        }
        return updated
    }

    private fun removeBroxySection(lines: List<String>): List<String> {
        val section = findBroxySection(lines) ?: return lines
        val updated = lines.toMutableList()
        updated.subList(section.start, section.end).clear()
        val mergeIndex = section.start.coerceAtMost(updated.size - 1)
        if (mergeIndex > 0 && mergeIndex < updated.size && updated[mergeIndex - 1].isBlank() && updated[mergeIndex].isBlank()) {
            updated.removeAt(mergeIndex)
        }
        while (updated.size >= 2 && updated[updated.size - 1].isBlank() && updated[updated.size - 2].isBlank()) {
            updated.removeAt(updated.size - 1)
        }
        return updated
    }

    private fun normalizeBlankLines(lines: List<String>): List<String> {
        if (lines.isEmpty()) return lines
        val normalized = ArrayList<String>(lines.size)
        var previousBlank = false
        for (line in lines) {
            val isBlank = line.isBlank()
            if (isBlank) {
                if (!previousBlank) {
                    normalized.add("")
                }
            } else {
                normalized.add(line)
            }
            previousBlank = isBlank
        }
        return normalized
    }

    private companion object {
        private const val SERVER_NAME = "broxy"
        private const val DEFAULT_DISPLAY_PATH = "~/.codex/config.toml"

        private fun defaultConfigPath(): Path = Paths.get(System.getProperty("user.home"), ".codex", "config.toml")
    }
}
