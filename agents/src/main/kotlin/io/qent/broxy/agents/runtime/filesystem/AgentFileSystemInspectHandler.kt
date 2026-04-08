package io.qent.broxy.agents.runtime.filesystem

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.streams.asSequence

internal class AgentFileSystemInspectHandler(
    private val workspace: AgentFileSystemWorkspace,
    private val pathMetadata: AgentFileSystemPathMetadata,
) : AgentFileSystemToolHandler {
    override val name: String = AgentFileSystemToolNames.INSPECT

    override fun specification(): ToolSpecification =
        ToolSpecification
            .builder()
            .name(name)
            .description("Inspect path metadata, directory listing, or recursive tree.")
            .parameters(
                JsonObjectSchema
                    .builder()
                    .addEnumProperty("operation", listOf("list", "tree", "info"), "Inspect mode")
                    .addStringProperty("path", "Path relative to workspace")
                    .addIntegerProperty("maxDepth", "Depth for tree mode")
                    .addIntegerProperty("limit", "Maximum entries to return")
                    .addBooleanProperty("includeHidden", "Include dot-prefixed paths")
                    .addEnumProperty("sortBy", listOf("name", "size", "modified"), "Sort field")
                    .addBooleanProperty("descending", "Descending order")
                    .addBooleanProperty("includeSha256", "Include SHA-256 for info mode")
                    .required("operation")
                    .build(),
            ).build()

    override fun execute(arguments: JsonObject): JsonObject {
        val operation = AgentFileSystemJsonArguments.string(arguments, "operation", required = true).lowercase()
        val path = AgentFileSystemJsonArguments.string(arguments, "path", defaultValue = ".")
        val limit =
            AgentFileSystemJsonArguments
                .int(arguments, "limit", defaultValue = 500)
                .also { require(it >= 1) { "limit must be >= 1" } }
        val includeHidden = AgentFileSystemJsonArguments.boolean(arguments, "includeHidden", defaultValue = false)
        val sortBy =
            AgentFileSystemJsonArguments
                .string(arguments, "sortBy", defaultValue = "name")
                .lowercase()
                .also { require(it in setOf("name", "size", "modified")) { "Unsupported sortBy: $it" } }
        val descending = AgentFileSystemJsonArguments.boolean(arguments, "descending", defaultValue = false)
        val includeSha256 = AgentFileSystemJsonArguments.boolean(arguments, "includeSha256", defaultValue = false)

        return when (operation) {
            "list" -> inspectList(path, limit, includeHidden, sortBy, descending)
            "tree" -> {
                val maxDepth =
                    AgentFileSystemJsonArguments
                        .int(arguments, "maxDepth", defaultValue = 4)
                        .also { require(it >= 0) { "maxDepth must be >= 0" } }
                inspectTree(
                    path = path,
                    options =
                        TreeOptions(
                            maxDepth = maxDepth,
                            limit = limit,
                            includeHidden = includeHidden,
                            sortBy = sortBy,
                            descending = descending,
                        ),
                )
            }

            "info" -> inspectInfo(path, includeSha256)
            else ->
                throw AgentFileSystemException(
                    code = "invalid_argument",
                    message = "Unsupported fsInspect operation: $operation",
                )
        }
    }

    private fun inspectList(
        path: String,
        limit: Int,
        includeHidden: Boolean,
        sortBy: String,
        descending: Boolean,
    ): JsonObject {
        val target = workspace.resolveExistingDirectory(path)
        val children =
            Files
                .list(target)
                .use { stream -> stream.asSequence().toList() }
                .filter { includeHidden || !pathMetadata.isHiddenPath(it, target) }
                .sortedWith(pathMetadata.comparator(sortBy, descending))
        val limited = children.take(limit)
        return buildJsonObject {
            put("operation", JsonPrimitive("list"))
            put(
                "items",
                buildJsonArray {
                    limited.forEach { child ->
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive(child.fileName.toString()))
                                put("path", JsonPrimitive(workspace.toWorkspaceRelative(child)))
                                put("type", JsonPrimitive(pathMetadata.type(child)))
                                put("sizeBytes", JsonPrimitive(pathMetadata.size(child)))
                                put("modifiedEpochMillis", JsonPrimitive(pathMetadata.modifiedEpochMillis(child)))
                            },
                        )
                    }
                },
            )
            put("truncated", JsonPrimitive(children.size > limited.size))
        }
    }

    private fun inspectTree(
        path: String,
        options: TreeOptions,
    ): JsonObject {
        val target = workspace.resolveExistingDirectory(path)
        val entries = mutableListOf<JsonObject>()
        val queue = ArrayDeque<Pair<Path, Int>>()
        queue.add(target to 0)
        val state =
            TreeTraversalState(
                target = target,
                entries = entries,
                queue = queue,
                options = options,
            )
        var truncated = false
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            val isVisible = options.includeHidden || current == target || !pathMetadata.isHiddenPath(current, target)
            if (isVisible) {
                val limitReached =
                    appendNodeAndQueueChildren(
                        current = current,
                        depth = depth,
                        state = state,
                    )
                if (limitReached) {
                    truncated = true
                    break
                }
            }
        }
        return buildJsonObject {
            put("operation", JsonPrimitive("tree"))
            put("entries", JsonArray(entries))
            put("truncated", JsonPrimitive(truncated))
        }
    }

    private fun inspectInfo(
        path: String,
        includeSha256: Boolean,
    ): JsonObject {
        val target = workspace.resolveInsideWorkspace(path, allowMissingLeaf = true)
        if (!Files.exists(target)) {
            return buildJsonObject {
                put("operation", JsonPrimitive("info"))
                put("exists", JsonPrimitive(false))
                put("type", JsonPrimitive("missing"))
                put("sizeBytes", JsonPrimitive(0L))
                put("createdEpochMillis", JsonNull)
                put("modifiedEpochMillis", JsonNull)
                put("isReadable", JsonPrimitive(false))
                put("isWritable", JsonPrimitive(false))
                put("isExecutable", JsonPrimitive(false))
            }
        }

        val resolved = workspace.resolveInsideWorkspace(path, allowMissingLeaf = false)
        val attributes = Files.readAttributes(resolved, BasicFileAttributes::class.java)
        val sha =
            if (includeSha256 && Files.isRegularFile(resolved)) {
                pathMetadata.sha256(resolved)
            } else {
                null
            }
        return buildJsonObject {
            put("operation", JsonPrimitive("info"))
            put("exists", JsonPrimitive(true))
            put("type", JsonPrimitive(pathMetadata.type(resolved)))
            put("sizeBytes", JsonPrimitive(pathMetadata.size(resolved)))
            put("createdEpochMillis", JsonPrimitive(attributes.creationTime().toMillis()))
            put("modifiedEpochMillis", JsonPrimitive(attributes.lastModifiedTime().toMillis()))
            put("isReadable", JsonPrimitive(Files.isReadable(resolved)))
            put("isWritable", JsonPrimitive(Files.isWritable(resolved)))
            put("isExecutable", JsonPrimitive(Files.isExecutable(resolved)))
            if (sha != null) {
                put("sha256", JsonPrimitive(sha))
            }
        }
    }

    private fun appendNodeAndQueueChildren(
        current: Path,
        depth: Int,
        state: TreeTraversalState,
    ): Boolean {
        state.entries +=
            buildJsonObject {
                put("path", JsonPrimitive(workspace.toWorkspaceRelative(current)))
                put("depth", JsonPrimitive(depth))
                put("type", JsonPrimitive(pathMetadata.type(current)))
                put("sizeBytes", JsonPrimitive(pathMetadata.size(current)))
            }
        if (state.entries.size >= state.options.limit) {
            return state.queue.isNotEmpty()
        }

        val shouldExpand = depth < state.options.maxDepth && Files.isDirectory(current)
        if (shouldExpand) {
            val children =
                Files
                    .list(current)
                    .use { stream -> stream.asSequence().toList() }
                    .filter { state.options.includeHidden || !pathMetadata.isHiddenPath(it, state.target) }
                    .sortedWith(pathMetadata.comparator(state.options.sortBy, state.options.descending))
            children.forEach { child ->
                state.queue.addLast(child to (depth + 1))
            }
        }
        return false
    }

    private data class TreeOptions(
        val maxDepth: Int,
        val limit: Int,
        val includeHidden: Boolean,
        val sortBy: String,
        val descending: Boolean,
    )

    private data class TreeTraversalState(
        val target: Path,
        val entries: MutableList<JsonObject>,
        val queue: ArrayDeque<Pair<Path, Int>>,
        val options: TreeOptions,
    )
}
