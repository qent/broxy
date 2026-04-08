package io.qent.broxy.agents.runtime.filesystem

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.min

internal class AgentFileSystemReadHandler(
    private val workspace: AgentFileSystemWorkspace,
    private val textGuard: AgentFileSystemTextGuard,
) : AgentFileSystemToolHandler {
    override val name: String = AgentFileSystemToolNames.READ

    override fun specification(): ToolSpecification =
        ToolSpecification
            .builder()
            .name(name)
            .description("Read text file by range, head, or tail with line numbers.")
            .parameters(
                JsonObjectSchema
                    .builder()
                    .addStringProperty("filePath", "Target file path")
                    .addEnumProperty("mode", listOf("range", "head", "tail"), "Read mode")
                    .addIntegerProperty("startLine", "Start line for range mode")
                    .addIntegerProperty("lineCount", "Number of lines to return")
                    .addBooleanProperty("includeLineNumbers", "Include line numbers")
                    .addIntegerProperty("maxChars", "Character budget for output")
                    .required("filePath", "mode")
                    .build(),
            ).build()

    override fun execute(arguments: JsonObject): JsonObject {
        val filePath = AgentFileSystemJsonArguments.string(arguments, "filePath", required = true)
        val mode = AgentFileSystemJsonArguments.string(arguments, "mode", required = true).lowercase()
        val startLine = AgentFileSystemJsonArguments.int(arguments, "startLine", defaultValue = 1)
        val lineCount = AgentFileSystemJsonArguments.int(arguments, "lineCount", defaultValue = 120)
        val includeLineNumbers =
            AgentFileSystemJsonArguments.boolean(arguments, "includeLineNumbers", defaultValue = true)
        val maxChars = AgentFileSystemJsonArguments.int(arguments, "maxChars", defaultValue = 40_000)
        require(startLine >= 1) { "startLine must be >= 1" }
        require(lineCount >= 1) { "lineCount must be >= 1" }
        require(maxChars >= 1) { "maxChars must be >= 1" }

        val file = workspace.resolveExistingFile(filePath)
        textGuard.ensureTextFile(file)
        val lines = textGuard.readUtf8Lines(file)
        val totalLines = lines.size
        val requestedRange = resolveRange(mode, startLine, lineCount, totalLines)

        val renderedLines = mutableListOf<JsonElement>()
        var remainingChars = maxChars
        var truncated = false
        var emittedStartLine = 0
        var emittedEndLine = 0
        for (index in requestedRange) {
            val fullLine = lines[index]
            val lineNumber = index + 1
            if (emittedStartLine == 0) {
                emittedStartLine = lineNumber
            }
            if (remainingChars > 0) {
                val truncatedLine = fullLine.length > remainingChars
                val rendered =
                    if (truncatedLine) {
                        fullLine.take(remainingChars)
                    } else {
                        fullLine
                    }
                remainingChars -= rendered.length
                emittedEndLine = lineNumber
                renderedLines += renderLine(lineNumber, rendered, includeLineNumbers)
                if (truncatedLine) {
                    truncated = true
                }
            } else {
                truncated = true
            }
            if (truncated) {
                break
            }
        }

        return buildJsonObject {
            put("lines", JsonArray(renderedLines))
            put("startLine", JsonPrimitive(emittedStartLine))
            put("endLine", JsonPrimitive(emittedEndLine))
            put("totalLines", JsonPrimitive(totalLines))
            put("truncated", JsonPrimitive(truncated))
        }
    }

    private fun resolveRange(
        mode: String,
        startLine: Int,
        lineCount: Int,
        totalLines: Int,
    ): IntRange =
        when (mode) {
            "range" -> {
                val startIndex = (startLine - 1).coerceAtMost(totalLines)
                val endExclusive = (startIndex + lineCount).coerceAtMost(totalLines)
                startIndex until endExclusive
            }

            "head" -> 0 until min(totalLines, lineCount)
            "tail" -> {
                val startIndex = (totalLines - lineCount).coerceAtLeast(0)
                startIndex until totalLines
            }

            else ->
                throw AgentFileSystemException(
                    code = "invalid_argument",
                    message = "Unsupported fsRead mode: $mode",
                )
        }

    private fun renderLine(
        lineNumber: Int,
        content: String,
        includeLineNumbers: Boolean,
    ): JsonElement =
        if (includeLineNumbers) {
            buildJsonObject {
                put("lineNumber", JsonPrimitive(lineNumber))
                put("text", JsonPrimitive(content))
            }
        } else {
            JsonPrimitive(content)
        }
}
