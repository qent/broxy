package io.qent.broxy.agents.runtime.filesystem

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

internal class AgentFileSystemSearchHandler(
    private val workspace: AgentFileSystemWorkspace,
    private val textGuard: AgentFileSystemTextGuard,
    private val pathMetadata: AgentFileSystemPathMetadata,
) : AgentFileSystemToolHandler {
    override val name: String = AgentFileSystemToolNames.SEARCH

    override fun specification(): ToolSpecification =
        ToolSpecification
            .builder()
            .name(name)
            .description("Regex search in paths and/or file content with exact line numbers.")
            .parameters(
                JsonObjectSchema
                    .builder()
                    .addEnumProperty("mode", listOf("paths", "content", "both"), "Search mode")
                    .addStringProperty("rootPath", "Search root path")
                    .addStringProperty("pathRegex", "Regex for paths")
                    .addStringProperty("contentRegex", "Regex for content lines")
                    .addBooleanProperty("caseSensitive", "Case-sensitive matching")
                    .addBooleanProperty("includeHidden", "Include dot-prefixed paths")
                    .addIntegerProperty("maxDepth", "Maximum recursion depth")
                    .addIntegerProperty("maxMatches", "Maximum matches to return")
                    .addIntegerProperty("maxFilesScanned", "Maximum files for content scan")
                    .addIntegerProperty("contextBefore", "Context lines before match")
                    .addIntegerProperty("contextAfter", "Context lines after match")
                    .required("mode")
                    .build(),
            ).build()

    override fun execute(arguments: JsonObject): JsonObject {
        val request = parseRequest(arguments)
        validateMode(request.mode, request.pathRegexRaw, request.contentRegexRaw)
        val pathRegex = request.pathRegexRaw?.let { compileRegex(it, request.caseSensitive) }
        val contentRegex = request.contentRegexRaw?.let { compileRegex(it, request.caseSensitive) }

        val root = workspace.resolveInsideWorkspace(request.rootPath, allowMissingLeaf = false)
        val singleFileRoot = Files.isRegularFile(root)
        if (singleFileRoot && (request.mode == "content" || request.mode == "both")) {
            textGuard.ensureTextFile(root)
        }

        val context =
            SearchContext(
                mode = request.mode,
                root = root,
                singleFileRoot = singleFileRoot,
                includeHidden = request.includeHidden,
                maxMatches = request.maxMatches,
                maxFilesScanned = request.maxFilesScanned,
                contextBefore = request.contextBefore,
                contextAfter = request.contextAfter,
                pathRegex = pathRegex,
                contentRegex = contentRegex,
            )

        val state = SearchState()
        if (singleFileRoot) {
            scanCandidates(sequenceOf(root), context, state)
        } else {
            Files.walk(root, request.maxDepth).use { stream ->
                scanCandidates(stream.asSequence(), context, state)
            }
        }

        return buildJsonObject {
            put("pathMatches", JsonArray(state.pathMatches))
            put("contentMatches", JsonArray(state.contentMatches))
            put("filesScanned", JsonPrimitive(state.filesScanned))
            put("truncated", JsonPrimitive(state.truncated))
        }
    }

    private fun scanCandidates(
        candidates: Sequence<Path>,
        context: SearchContext,
        state: SearchState,
    ) {
        candidates.forEach { candidate ->
            if (state.truncated) {
                return@forEach
            }
            if (
                !context.includeHidden &&
                !context.singleFileRoot &&
                pathMetadata.isHiddenPath(candidate, context.root)
            ) {
                return@forEach
            }
            val relativePath = workspace.toWorkspaceRelative(candidate)
            matchPath(candidate, relativePath, context, state)
            if (state.truncated) {
                return@forEach
            }
            matchContent(candidate, relativePath, context, state)
        }
    }

    private fun matchPath(
        candidate: Path,
        relativePath: String,
        context: SearchContext,
        state: SearchState,
    ) {
        val matchPathMode = context.mode == "paths" || context.mode == "both"
        if (!matchPathMode || context.pathRegex == null) {
            return
        }
        if (!context.pathRegex.containsMatchIn(relativePath)) {
            return
        }
        state.pathMatches +=
            buildJsonObject {
                put("path", JsonPrimitive(relativePath))
                put("type", JsonPrimitive(pathMetadata.type(candidate)))
            }
        if (state.totalMatches() >= context.maxMatches) {
            state.truncated = true
        }
    }

    private fun matchContent(
        candidate: Path,
        relativePath: String,
        context: SearchContext,
        state: SearchState,
    ) {
        val matchContentMode = context.mode == "content" || context.mode == "both"
        if (!matchContentMode || context.contentRegex == null || !Files.isRegularFile(candidate)) {
            return
        }
        if (state.filesScanned >= context.maxFilesScanned) {
            state.truncated = true
        } else {
            state.filesScanned += 1
            val isText = runCatching { textGuard.ensureTextFile(candidate) }.isSuccess
            if (isText) {
                val lines = textGuard.readUtf8Lines(candidate)
                lines.forEachIndexed { index, line ->
                    if (state.truncated) {
                        return@forEachIndexed
                    }
                    if (!context.contentRegex.containsMatchIn(line)) {
                        return@forEachIndexed
                    }
                    state.contentMatches += buildContentMatch(relativePath, lines, index, line, context)
                    if (state.totalMatches() >= context.maxMatches) {
                        state.truncated = true
                    }
                }
            }
        }
    }

    private fun buildContentMatch(
        relativePath: String,
        lines: List<String>,
        index: Int,
        line: String,
        context: SearchContext,
    ): JsonObject {
        val beforeStart = (index - context.contextBefore).coerceAtLeast(0)
        val afterEndExclusive = (index + context.contextAfter + 1).coerceAtMost(lines.size)
        return buildJsonObject {
            put("filePath", JsonPrimitive(relativePath))
            put("lineNumber", JsonPrimitive(index + 1))
            put("lineText", JsonPrimitive(line))
            put(
                "contextBefore",
                buildJsonArray {
                    for (contextIndex in beforeStart until index) {
                        add(JsonPrimitive(lines[contextIndex]))
                    }
                },
            )
            put(
                "contextAfter",
                buildJsonArray {
                    for (contextIndex in (index + 1) until afterEndExclusive) {
                        add(JsonPrimitive(lines[contextIndex]))
                    }
                },
            )
        }
    }

    private fun parseRequest(arguments: JsonObject): SearchRequest =
        SearchRequest(
            mode = AgentFileSystemJsonArguments.string(arguments, "mode", required = true).lowercase(),
            rootPath = AgentFileSystemJsonArguments.string(arguments, "rootPath", defaultValue = "."),
            pathRegexRaw = AgentFileSystemJsonArguments.stringOrNull(arguments, "pathRegex"),
            contentRegexRaw = AgentFileSystemJsonArguments.stringOrNull(arguments, "contentRegex"),
            caseSensitive = AgentFileSystemJsonArguments.boolean(arguments, "caseSensitive", defaultValue = true),
            includeHidden = AgentFileSystemJsonArguments.boolean(arguments, "includeHidden", defaultValue = false),
            maxDepth =
                AgentFileSystemJsonArguments
                    .int(arguments, "maxDepth", defaultValue = 8)
                    .also { require(it >= 0) { "maxDepth must be >= 0" } },
            maxMatches =
                AgentFileSystemJsonArguments
                    .int(arguments, "maxMatches", defaultValue = 200)
                    .also { require(it >= 1) { "maxMatches must be >= 1" } },
            maxFilesScanned =
                AgentFileSystemJsonArguments
                    .int(arguments, "maxFilesScanned", defaultValue = 1_000)
                    .also { require(it >= 1) { "maxFilesScanned must be >= 1" } },
            contextBefore =
                AgentFileSystemJsonArguments
                    .int(arguments, "contextBefore", defaultValue = 0)
                    .also { require(it >= 0) { "contextBefore must be >= 0" } },
            contextAfter =
                AgentFileSystemJsonArguments
                    .int(arguments, "contextAfter", defaultValue = 0)
                    .also { require(it >= 0) { "contextAfter must be >= 0" } },
        )

    private fun validateMode(
        mode: String,
        pathRegex: String?,
        contentRegex: String?,
    ) {
        when (mode) {
            "paths" -> require(!pathRegex.isNullOrBlank()) { "pathRegex is required for mode=paths" }
            "content" -> require(!contentRegex.isNullOrBlank()) { "contentRegex is required for mode=content" }
            "both" ->
                require(!pathRegex.isNullOrBlank() || !contentRegex.isNullOrBlank()) {
                    "At least one regex is required for mode=both"
                }
            else ->
                throw AgentFileSystemException(
                    code = "invalid_argument",
                    message = "Unsupported fsSearch mode: $mode",
                )
        }
    }

    private fun compileRegex(
        pattern: String,
        caseSensitive: Boolean,
    ): Regex =
        runCatching {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(pattern, options)
        }.getOrElse {
            throw AgentFileSystemException(
                code = "invalid_regex",
                message = "Invalid regex: $pattern",
            )
        }

    private data class SearchContext(
        val mode: String,
        val root: Path,
        val singleFileRoot: Boolean,
        val includeHidden: Boolean,
        val maxMatches: Int,
        val maxFilesScanned: Int,
        val contextBefore: Int,
        val contextAfter: Int,
        val pathRegex: Regex?,
        val contentRegex: Regex?,
    )

    private data class SearchRequest(
        val mode: String,
        val rootPath: String,
        val pathRegexRaw: String?,
        val contentRegexRaw: String?,
        val caseSensitive: Boolean,
        val includeHidden: Boolean,
        val maxDepth: Int,
        val maxMatches: Int,
        val maxFilesScanned: Int,
        val contextBefore: Int,
        val contextAfter: Int,
    )

    private class SearchState {
        val pathMatches = mutableListOf<JsonObject>()
        val contentMatches = mutableListOf<JsonObject>()
        var filesScanned = 0
        var truncated = false

        fun totalMatches(): Int = pathMatches.size + contentMatches.size
    }
}
