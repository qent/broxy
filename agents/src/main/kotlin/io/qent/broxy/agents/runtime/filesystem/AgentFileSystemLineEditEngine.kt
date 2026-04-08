package io.qent.broxy.agents.runtime.filesystem

internal class AgentFileSystemLineEditEngine(
    private val previewLimit: Int = DEFAULT_PREVIEW_LIMIT,
) {
    fun editByLines(
        originalText: String,
        request: AgentLineEditRequest,
    ): AgentFileEditResult {
        val originalTrailingNewline = originalText.endsWith("\n")
        val originalLines = mutableListOf<String>()
        if (originalText.isNotEmpty()) {
            val split = originalText.split("\n").toMutableList()
            if (originalTrailingNewline && split.isNotEmpty()) {
                split.removeAt(split.lastIndex)
            }
            originalLines += split
        }
        if (originalLines.isEmpty()) {
            throw AgentFileSystemException(
                code = "invalid_argument",
                message = "Line edit requires an existing non-empty text file",
            )
        }
        require(request.startLine in 1..originalLines.size) { "startLine is out of range" }
        val replacementLines = splitTextLines(request.text)
        val startIndex = request.startLine - 1
        val changedStartLine: Int
        val changedEndLine: Int
        when (request.mode) {
            AgentFileSystemLineEditMode.INSERT_BEFORE -> {
                originalLines.addAll(startIndex, replacementLines)
                changedStartLine = request.startLine
                changedEndLine = request.startLine + replacementLines.size - 1
            }

            AgentFileSystemLineEditMode.INSERT_AFTER -> {
                originalLines.addAll(startIndex + 1, replacementLines)
                changedStartLine = request.startLine + 1
                changedEndLine = changedStartLine + replacementLines.size - 1
            }

            AgentFileSystemLineEditMode.REPLACE_RANGE -> {
                val endLine = request.explicitEndLine ?: request.startLine
                require(endLine in request.startLine..originalLines.size) { "endLine is out of range" }
                repeat(endLine - request.startLine + 1) {
                    originalLines.removeAt(startIndex)
                }
                originalLines.addAll(startIndex, replacementLines)
                changedStartLine = request.startLine
                changedEndLine = request.startLine + replacementLines.size - 1
            }
        }

        var rendered = originalLines.joinToString("\n")
        if (request.ensureTrailingNewline) {
            rendered = applyTrailingNewline(rendered, true)
        } else if (originalTrailingNewline && rendered.isNotEmpty()) {
            rendered += "\n"
        }
        return AgentFileEditResult(
            newText = rendered,
            changedStartLine = changedStartLine.coerceAtLeast(0),
            changedEndLine = changedEndLine.coerceAtLeast(0),
            preview = previewSnippet(request.text),
        )
    }

    fun applyTrailingNewline(
        text: String,
        ensureTrailingNewline: Boolean,
    ): String {
        if (!ensureTrailingNewline) {
            return text
        }
        return if (text.endsWith("\n")) text else "$text\n"
    }

    fun lineCount(text: String): Int {
        if (text.isEmpty()) {
            return 0
        }
        val total = text.count { it == '\n' } + 1
        return if (text.endsWith("\n")) total - 1 else total
    }

    fun previewSnippet(text: String): String {
        val normalized =
            text
                .replace("\r\n", "\n")
                .replace('\n', ' ')
                .trim()
        return if (normalized.length <= previewLimit) normalized else normalized.take(previewLimit)
    }

    private fun splitTextLines(text: String): List<String> = text.split("\n")

    private companion object {
        private const val DEFAULT_PREVIEW_LIMIT = 240
    }
}

internal enum class AgentFileSystemLineEditMode {
    INSERT_BEFORE,
    INSERT_AFTER,
    REPLACE_RANGE,
}

internal data class AgentLineEditRequest(
    val mode: AgentFileSystemLineEditMode,
    val text: String,
    val startLine: Int,
    val explicitEndLine: Int?,
    val ensureTrailingNewline: Boolean,
)

internal data class AgentFileEditResult(
    val newText: String,
    val changedStartLine: Int,
    val changedEndLine: Int,
    val preview: String,
)
