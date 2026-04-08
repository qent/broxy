package io.qent.broxy.agents.runtime.filesystem

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal class AgentFileSystemEditHandler(
    private val workspace: AgentFileSystemWorkspace,
    private val textGuard: AgentFileSystemTextGuard,
    private val lineEditEngine: AgentFileSystemLineEditEngine,
) : AgentFileSystemToolHandler {
    override val name: String = AgentFileSystemToolNames.EDIT

    override fun specification(): ToolSpecification =
        ToolSpecification
            .builder()
            .name(name)
            .description("Unified file edit: overwrite/append/prepend/line-edit/create-directory.")
            .parameters(
                JsonObjectSchema
                    .builder()
                    .addEnumProperty(
                        "operation",
                        listOf(
                            "overwrite",
                            "append",
                            "prepend",
                            "insert_before",
                            "insert_after",
                            "replace_range",
                            "ensure_directory",
                        ),
                        "Edit operation",
                    ).addStringProperty("path", "Target file or directory path")
                    .addStringProperty("text", "Text payload")
                    .addIntegerProperty("startLine", "Start line for line edits")
                    .addIntegerProperty("endLine", "End line for replace_range")
                    .addBooleanProperty("createIfMissing", "Create file when missing")
                    .addBooleanProperty("createParentDirectories", "Create missing parent directories")
                    .addBooleanProperty("ensureTrailingNewline", "Ensure final newline")
                    .addBooleanProperty("failIfExists", "Fail when directory already exists")
                    .required("operation", "path")
                    .build(),
            ).build()

    override fun execute(arguments: JsonObject): JsonObject {
        val request = parseRequest(arguments)
        validateEditArguments(request.operation, request.text, request.startLine, request.endLine)
        textGuard.rejectBinaryTextPayload(request.text)

        if (request.operation == "ensure_directory") {
            return ensureDirectory(request.path, request.createParentDirectories, request.failIfExists)
        }

        val target = workspace.resolveForWrite(request.path, request.createParentDirectories)
        val exists = Files.exists(target)
        validateWriteTarget(target, exists, request.createIfMissing, request.operation)

        val requiresExistingTextRead =
            request.operation == "append" ||
                request.operation == "prepend" ||
                request.operation == "insert_before" ||
                request.operation == "insert_after" ||
                request.operation == "replace_range"

        val beforeText =
            if (exists && requiresExistingTextRead) {
                textGuard.ensureTextFile(target)
                textGuard.readUtf8(target)
            } else {
                ""
            }
        val beforeLines = lineEditEngine.lineCount(beforeText)
        val editResult = buildEditResult(request, beforeText, beforeLines)
        return persistEditResult(request.operation, target, editResult)
    }

    private fun parseRequest(arguments: JsonObject): EditRequest =
        EditRequest(
            operation = AgentFileSystemJsonArguments.string(arguments, "operation", required = true).lowercase(),
            path = AgentFileSystemJsonArguments.string(arguments, "path", required = true),
            text = AgentFileSystemJsonArguments.rawStringOrNull(arguments, "text"),
            createIfMissing = AgentFileSystemJsonArguments.boolean(arguments, "createIfMissing", defaultValue = true),
            createParentDirectories =
                AgentFileSystemJsonArguments.boolean(arguments, "createParentDirectories", defaultValue = true),
            ensureTrailingNewline =
                AgentFileSystemJsonArguments.boolean(arguments, "ensureTrailingNewline", defaultValue = false),
            failIfExists = AgentFileSystemJsonArguments.boolean(arguments, "failIfExists", defaultValue = false),
            startLine = AgentFileSystemJsonArguments.intOrNull(arguments, "startLine"),
            endLine = AgentFileSystemJsonArguments.intOrNull(arguments, "endLine"),
        )

    private fun buildEditResult(
        request: EditRequest,
        beforeText: String,
        beforeLines: Int,
    ): AgentFileEditResult =
        when (request.operation) {
            "overwrite" -> overwriteResult(request.text.orEmpty(), request.ensureTrailingNewline)
            "append" ->
                appendResult(
                    beforeText,
                    beforeLines,
                    request.text.orEmpty(),
                    request.ensureTrailingNewline,
                )
            "prepend" -> prependResult(beforeText, request.text.orEmpty(), request.ensureTrailingNewline)
            "insert_before" ->
                lineEditEngine.editByLines(
                    originalText = beforeText,
                    request =
                        AgentLineEditRequest(
                            mode = AgentFileSystemLineEditMode.INSERT_BEFORE,
                            text = request.text.orEmpty(),
                            startLine = checkNotNull(request.startLine),
                            explicitEndLine = request.endLine,
                            ensureTrailingNewline = request.ensureTrailingNewline,
                        ),
                )

            "insert_after" ->
                lineEditEngine.editByLines(
                    originalText = beforeText,
                    request =
                        AgentLineEditRequest(
                            mode = AgentFileSystemLineEditMode.INSERT_AFTER,
                            text = request.text.orEmpty(),
                            startLine = checkNotNull(request.startLine),
                            explicitEndLine = request.endLine,
                            ensureTrailingNewline = request.ensureTrailingNewline,
                        ),
                )

            "replace_range" ->
                lineEditEngine.editByLines(
                    originalText = beforeText,
                    request =
                        AgentLineEditRequest(
                            mode = AgentFileSystemLineEditMode.REPLACE_RANGE,
                            text = request.text.orEmpty(),
                            startLine = checkNotNull(request.startLine),
                            explicitEndLine = request.endLine,
                            ensureTrailingNewline = request.ensureTrailingNewline,
                        ),
                )

            else -> error("Unsupported edit mode")
        }

    private fun persistEditResult(
        operation: String,
        target: Path,
        editResult: AgentFileEditResult,
    ): JsonObject {
        textGuard.writeUtf8(target, editResult.newText)
        val newSize = Files.size(target)
        return buildJsonObject {
            put("operation", JsonPrimitive(operation))
            put(
                "bytesWritten",
                JsonPrimitive(
                    editResult.newText
                        .toByteArray(StandardCharsets.UTF_8)
                        .size
                        .toLong(),
                ),
            )
            put("newFileSizeBytes", JsonPrimitive(newSize))
            put("newTotalLines", JsonPrimitive(lineEditEngine.lineCount(editResult.newText)))
            put("changedStartLine", JsonPrimitive(editResult.changedStartLine))
            put("changedEndLine", JsonPrimitive(editResult.changedEndLine))
            put("preview", JsonPrimitive(editResult.preview))
        }
    }

    private fun validateWriteTarget(
        target: Path,
        exists: Boolean,
        createIfMissing: Boolean,
        operation: String,
    ) {
        if (!exists && !createIfMissing) {
            throw AgentFileSystemException(
                code = "path_not_found",
                message = "Path not found: ${target.toAbsolutePath()}",
            )
        }
        if (exists && !Files.isRegularFile(target)) {
            throw AgentFileSystemException(
                code = "not_a_file",
                message = "Path is not a file: ${target.toAbsolutePath()}",
            )
        }
        if (exists && operation == "overwrite") {
            textGuard.ensureTextFile(target)
        }
    }

    private fun overwriteResult(
        text: String,
        ensureTrailingNewline: Boolean,
    ): AgentFileEditResult {
        val updated = lineEditEngine.applyTrailingNewline(text, ensureTrailingNewline)
        return AgentFileEditResult(
            newText = updated,
            changedStartLine = if (updated.isEmpty()) 0 else 1,
            changedEndLine = lineEditEngine.lineCount(updated),
            preview = lineEditEngine.previewSnippet(updated),
        )
    }

    private fun appendResult(
        beforeText: String,
        beforeLines: Int,
        text: String,
        ensureTrailingNewline: Boolean,
    ): AgentFileEditResult {
        val updated = lineEditEngine.applyTrailingNewline(beforeText + text, ensureTrailingNewline)
        val changedStart = if (beforeLines == 0) 1 else beforeLines
        return AgentFileEditResult(
            newText = updated,
            changedStartLine = changedStart,
            changedEndLine = lineEditEngine.lineCount(updated),
            preview = lineEditEngine.previewSnippet(text),
        )
    }

    private fun prependResult(
        beforeText: String,
        text: String,
        ensureTrailingNewline: Boolean,
    ): AgentFileEditResult {
        val updated = lineEditEngine.applyTrailingNewline(text + beforeText, ensureTrailingNewline)
        val insertedLines = lineEditEngine.lineCount(text)
        return AgentFileEditResult(
            newText = updated,
            changedStartLine = if (insertedLines == 0) 0 else 1,
            changedEndLine = insertedLines,
            preview = lineEditEngine.previewSnippet(text),
        )
    }

    private fun ensureDirectory(
        rawPath: String,
        createParentDirectories: Boolean,
        failIfExists: Boolean,
    ): JsonObject {
        val target = workspace.resolveInsideWorkspace(rawPath, allowMissingLeaf = true)
        if (Files.exists(target)) {
            if (!Files.isDirectory(target)) {
                throw AgentFileSystemException(
                    code = "not_a_directory",
                    message = "Path is not a directory: ${target.toAbsolutePath()}",
                )
            }
            if (failIfExists) {
                throw AgentFileSystemException(
                    code = "invalid_argument",
                    message = "Directory already exists: ${target.toAbsolutePath()}",
                )
            }
        } else if (createParentDirectories) {
            Files.createDirectories(target)
        } else {
            Files.createDirectory(target)
        }
        return buildJsonObject {
            put("operation", JsonPrimitive("ensure_directory"))
            put("bytesWritten", JsonPrimitive(0))
            put("newFileSizeBytes", JsonPrimitive(0))
            put("newTotalLines", JsonPrimitive(0))
            put("changedStartLine", JsonPrimitive(0))
            put("changedEndLine", JsonPrimitive(0))
            put("preview", JsonPrimitive(""))
        }
    }

    private data class EditRequest(
        val operation: String,
        val path: String,
        val text: String?,
        val createIfMissing: Boolean,
        val createParentDirectories: Boolean,
        val ensureTrailingNewline: Boolean,
        val failIfExists: Boolean,
        val startLine: Int?,
        val endLine: Int?,
    )
}

private fun validateEditArguments(
    operation: String,
    text: String?,
    startLine: Int?,
    endLine: Int?,
) {
    when (operation) {
        "overwrite", "append", "prepend" -> require(text != null) { "text is required for $operation" }
        "insert_before", "insert_after" -> {
            require(text != null) { "text is required for $operation" }
            require((startLine ?: 0) >= 1) { "startLine must be >= 1" }
        }

        "replace_range" -> {
            require(text != null) { "text is required for replace_range" }
            require((startLine ?: 0) >= 1) { "startLine must be >= 1" }
            require((endLine ?: startLine ?: 0) >= (startLine ?: 0)) { "endLine must be >= startLine" }
        }

        "ensure_directory" -> Unit
        else ->
            throw AgentFileSystemException(
                code = "invalid_argument",
                message = "Unsupported fsEdit operation: $operation",
            )
    }
}
