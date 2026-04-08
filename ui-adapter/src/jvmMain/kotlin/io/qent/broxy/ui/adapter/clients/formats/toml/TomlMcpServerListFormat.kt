package io.qent.broxy.ui.adapter.clients.formats.toml

import io.qent.broxy.ui.adapter.clients.common.BroxyServerEntry
import io.qent.broxy.ui.adapter.clients.common.BroxyServerStatus
import io.qent.broxy.ui.adapter.clients.common.McpServerListEntry
import io.qent.broxy.ui.adapter.clients.common.McpServerListFormat

internal class TomlMcpServerListFormat : McpServerListFormat {
    override fun listServerEntries(content: String): List<McpServerListEntry> =
        findMcpServerSections(readConfigLines(content).lines)
            .map { section ->
                McpServerListEntry(
                    sourceServerId = section.serverName,
                    name = section.name,
                    enabled = section.enabled,
                    type = section.type,
                    command = section.command,
                    args = section.args,
                    url = section.url,
                    headers = section.headers,
                    env = section.env,
                )
            }

    override fun listServers(content: String): List<String> = listServerEntries(content).map { it.sourceServerId }

    override fun readBroxyStatus(
        content: String,
        serverName: String,
    ): BroxyServerStatus {
        val config = readConfigLines(content)
        val section = findSection(config.lines, serverName) ?: return BroxyServerStatus(isConfigured = false)
        return BroxyServerStatus(
            isConfigured = true,
            configuredUrl = section.url,
        )
    }

    override fun upsertBroxy(
        content: String,
        serverName: String,
        entry: BroxyServerEntry,
    ): String {
        val url =
            when (entry) {
                is BroxyServerEntry.UrlEntry -> entry.url
                else -> error("TOML format requires BroxyServerEntry.UrlEntry.")
            }
        val config = readConfigLines(content)
        val updatedLines = normalizeBlankLines(upsertSection(config.lines, serverName, url))
        return writeConfigLines(updatedLines, config.lineSeparator)
    }

    override fun removeBroxy(
        content: String,
        serverName: String,
    ): String {
        val config = readConfigLines(content)
        val updatedLines = normalizeBlankLines(removeSection(config.lines, serverName))
        return writeConfigLines(updatedLines, config.lineSeparator)
    }

    private data class ConfigLines(
        val lines: List<String>,
        val lineSeparator: String,
    )

    private data class Section(
        val serverName: String,
        val start: Int,
        val end: Int,
        val name: String?,
        val type: String?,
        val url: String?,
        val command: String?,
        val args: List<String>,
        val headers: Map<String, String>,
        val env: Map<String, String>,
        val enabled: Boolean?,
    )

    private data class ParsedHeader(
        val index: Int,
        val serverName: String?,
        val kind: ParsedHeaderKind,
    )

    private data class Assignment(
        val key: String,
        val value: String,
    )

    private enum class ParsedHeaderKind {
        Main,
        Env,
        Headers,
        EnvHeaders,
        Other,
    }

    private fun readConfigLines(content: String): ConfigLines {
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
        return ConfigLines(lines = lines, lineSeparator = separator)
    }

    private fun writeConfigLines(
        lines: List<String>,
        separator: String,
    ): String =
        if (lines.isEmpty()) {
            ""
        } else {
            lines.joinToString(separator)
        }

    private fun findSection(
        lines: List<String>,
        serverName: String,
    ): Section? = findMcpServerSections(lines).firstOrNull { it.serverName == serverName }

    private fun findMcpServerSections(lines: List<String>): List<Section> {
        val headers =
            lines.mapIndexedNotNull { index, line ->
                val mainMatch = mcpMainHeaderRegex.matchEntire(line)
                if (mainMatch != null) {
                    return@mapIndexedNotNull ParsedHeader(
                        index = index,
                        serverName = mainMatch.groupValues[1],
                        kind = ParsedHeaderKind.Main,
                    )
                }
                val envMatch = mcpEnvHeaderRegex.matchEntire(line)
                if (envMatch != null) {
                    return@mapIndexedNotNull ParsedHeader(
                        index = index,
                        serverName = envMatch.groupValues[1],
                        kind = ParsedHeaderKind.Env,
                    )
                }
                val headersMatch = mcpHeadersHeaderRegex.matchEntire(line)
                if (headersMatch != null) {
                    return@mapIndexedNotNull ParsedHeader(
                        index = index,
                        serverName = headersMatch.groupValues[1],
                        kind = ParsedHeaderKind.Headers,
                    )
                }
                val envHeadersMatch = mcpEnvHeadersHeaderRegex.matchEntire(line)
                if (envHeadersMatch != null) {
                    return@mapIndexedNotNull ParsedHeader(
                        index = index,
                        serverName = envHeadersMatch.groupValues[1],
                        kind = ParsedHeaderKind.EnvHeaders,
                    )
                }
                if (sectionHeaderRegex.matches(line)) {
                    ParsedHeader(index = index, serverName = null, kind = ParsedHeaderKind.Other)
                } else {
                    null
                }
            }

        val sections = mutableListOf<Section>()
        for ((headerIndex, header) in headers.withIndex()) {
            if (header.kind != ParsedHeaderKind.Main) continue
            val serverName = header.serverName ?: continue
            val end =
                run {
                    var nextIndex = headerIndex + 1
                    while (nextIndex < headers.size) {
                        val nextHeader = headers[nextIndex]
                        val sameServerSubtable =
                            nextHeader.serverName == serverName &&
                                nextHeader.kind in
                                setOf(
                                    ParsedHeaderKind.Env,
                                    ParsedHeaderKind.Headers,
                                    ParsedHeaderKind.EnvHeaders,
                                )
                        if (sameServerSubtable) {
                            nextIndex += 1
                            continue
                        }
                        return@run nextHeader.index
                    }
                    lines.size
                }

            sections +=
                parseSection(
                    lines = lines,
                    serverName = serverName,
                    start = header.index,
                    end = end,
                )
        }
        return sections
    }

    private fun parseSection(
        lines: List<String>,
        serverName: String,
        start: Int,
        end: Int,
    ): Section {
        var table = ParsedHeaderKind.Main
        var name: String? = null
        var type: String? = null
        var url: String? = null
        var command: String? = null
        var args: List<String> = emptyList()
        var enabled: Boolean? = null
        val env = LinkedHashMap<String, String>()
        val headers = LinkedHashMap<String, String>()

        for (lineIndex in (start + 1) until end) {
            val line = lines[lineIndex]
            val mainMatch = mcpMainHeaderRegex.matchEntire(line)
            if (mainMatch != null && mainMatch.groupValues[1] == serverName) {
                table = ParsedHeaderKind.Main
                continue
            }
            val envMatch = mcpEnvHeaderRegex.matchEntire(line)
            if (envMatch != null && envMatch.groupValues[1] == serverName) {
                table = ParsedHeaderKind.Env
                continue
            }
            val headersMatch = mcpHeadersHeaderRegex.matchEntire(line)
            if (headersMatch != null && headersMatch.groupValues[1] == serverName) {
                table = ParsedHeaderKind.Headers
                continue
            }
            val envHeadersMatch = mcpEnvHeadersHeaderRegex.matchEntire(line)
            if (envHeadersMatch != null && envHeadersMatch.groupValues[1] == serverName) {
                table = ParsedHeaderKind.EnvHeaders
                continue
            }
            if (sectionHeaderRegex.matches(line)) {
                table = ParsedHeaderKind.Other
                continue
            }

            val assignment = parseAssignment(line) ?: continue

            when (table) {
                ParsedHeaderKind.Main -> {
                    when (assignment.key.lowercase()) {
                        "name" -> name = parseTomlString(assignment.value) ?: name
                        "type" -> type = parseTomlString(assignment.value) ?: type
                        "url" -> url = parseTomlString(assignment.value) ?: url
                        "command" -> command = parseTomlString(assignment.value) ?: command
                        "args" -> args = parseTomlStringArray(assignment.value)
                        "enabled" -> enabled = parseTomlBoolean(assignment.value) ?: enabled
                        "env" -> env.putAll(parseInlineStringMap(assignment.value))
                        "headers", "http_headers", "env_http_headers" -> headers.putAll(parseInlineStringMap(assignment.value))
                    }
                }

                ParsedHeaderKind.Env -> {
                    val envValue = parseTomlString(assignment.value) ?: continue
                    env[assignment.key] = envValue
                }

                ParsedHeaderKind.Headers,
                ParsedHeaderKind.EnvHeaders,
                -> {
                    val headerValue = parseTomlString(assignment.value) ?: continue
                    headers[assignment.key] = headerValue
                }

                ParsedHeaderKind.Other -> Unit
            }
        }

        return Section(
            serverName = serverName,
            start = start,
            end = end,
            name = name,
            type = type,
            url = url,
            command = command,
            args = args,
            headers = headers,
            env = env,
            enabled = enabled,
        )
    }

    private fun parseAssignment(line: String): Assignment? {
        val stripped = line.substringBefore("#").trim()
        if (stripped.isEmpty()) return null
        val separatorIndex = stripped.indexOf('=')
        if (separatorIndex <= 0 || separatorIndex >= stripped.lastIndex) return null
        val key =
            stripped
                .substring(0, separatorIndex)
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
        if (key.isBlank()) return null
        val value = stripped.substring(separatorIndex + 1).trim()
        if (value.isEmpty()) return null
        return Assignment(key = key, value = value)
    }

    private fun parseTomlBoolean(rawValue: String): Boolean? =
        when (rawValue.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    private fun parseTomlString(rawValue: String): String? {
        val trimmed = rawValue.trim()
        if (trimmed.length < 2) return null
        return when {
            trimmed.first() == '"' && trimmed.last() == '"' -> decodeDoubleQuotedTomlString(trimmed)
            trimmed.first() == '\'' && trimmed.last() == '\'' -> trimmed.substring(1, trimmed.lastIndex)
            else -> null
        }
    }

    private fun decodeDoubleQuotedTomlString(value: String): String {
        val content = value.substring(1, value.lastIndex)
        val decoded = StringBuilder(content.length)
        var index = 0
        while (index < content.length) {
            val current = content[index]
            if (current == '\\' && index + 1 < content.length) {
                val escaped = content[index + 1]
                decoded.append(
                    when (escaped) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        '\\' -> '\\'
                        '"' -> '"'
                        else -> escaped
                    },
                )
                index += 2
            } else {
                decoded.append(current)
                index += 1
            }
        }
        return decoded.toString()
    }

    private fun parseTomlStringArray(rawValue: String): List<String> {
        val trimmed = rawValue.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        val body = trimmed.substring(1, trimmed.lastIndex).trim()
        if (body.isEmpty()) return emptyList()
        return quotedStringRegex
            .findAll(body)
            .mapNotNull { parseTomlString(it.value) }
            .toList()
    }

    private fun parseInlineStringMap(rawValue: String): Map<String, String> {
        val trimmed = rawValue.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyMap()
        val body = trimmed.substring(1, trimmed.lastIndex).trim()
        if (body.isEmpty()) return emptyMap()
        val parsed = LinkedHashMap<String, String>()
        splitTopLevelByComma(body).forEach { part ->
            val assignment = parseAssignment(part) ?: return@forEach
            val value = parseTomlString(assignment.value) ?: return@forEach
            parsed[assignment.key] = value
        }
        return parsed
    }

    private fun splitTopLevelByComma(value: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaped = false

        value.forEach { char ->
            if (escaped) {
                current.append(char)
                escaped = false
                return@forEach
            }
            if (inDoubleQuote && char == '\\') {
                current.append(char)
                escaped = true
                return@forEach
            }
            when {
                char == '\'' && !inDoubleQuote -> {
                    inSingleQuote = !inSingleQuote
                    current.append(char)
                }

                char == '"' && !inSingleQuote -> {
                    inDoubleQuote = !inDoubleQuote
                    current.append(char)
                }

                char == ',' && !inSingleQuote && !inDoubleQuote -> {
                    val token = current.toString().trim()
                    if (token.isNotEmpty()) {
                        parts += token
                    }
                    current.clear()
                }

                else -> current.append(char)
            }
        }

        val tail = current.toString().trim()
        if (tail.isNotEmpty()) {
            parts += tail
        }
        return parts
    }

    private fun upsertSection(
        lines: List<String>,
        serverName: String,
        url: String,
    ): List<String> {
        val block = listOf("[mcp_servers.$serverName]", "url = \"$url\"")
        val section = findSection(lines, serverName)
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

    private fun removeSection(
        lines: List<String>,
        serverName: String,
    ): List<String> {
        val section = findSection(lines, serverName) ?: return lines
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
        private val sectionHeaderRegex = Regex("^\\s*\\[[^]]+]\\s*$")
        private val mcpMainHeaderRegex = Regex("^\\s*\\[mcp_servers\\.([^\\.\\]]+)]\\s*$")
        private val mcpEnvHeaderRegex = Regex("^\\s*\\[mcp_servers\\.([^\\.\\]]+)\\.env]\\s*$")
        private val mcpHeadersHeaderRegex = Regex("^\\s*\\[mcp_servers\\.([^\\.\\]]+)\\.(headers|http_headers)]\\s*$")
        private val mcpEnvHeadersHeaderRegex = Regex("^\\s*\\[mcp_servers\\.([^\\.\\]]+)\\.env_http_headers]\\s*$")
        private val quotedStringRegex = Regex("\"(?:\\\\.|[^\"])*\"|'[^']*'")
    }
}
