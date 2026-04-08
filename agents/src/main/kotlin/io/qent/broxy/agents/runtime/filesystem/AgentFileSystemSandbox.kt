package io.qent.broxy.agents.runtime.filesystem

import io.qent.broxy.agents.AgentFileSystemAccess
import io.qent.broxy.agents.AgentFileSystemSettings
import io.qent.broxy.agents.DEFAULT_AGENT_WORKSPACE_PATH
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal class AgentFileSystemException(
    val code: String,
    override val message: String,
    val hint: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal data class AgentFileSystemWorkspace(
    val rootPath: Path,
    val rootRealPath: Path,
    val access: AgentFileSystemAccess,
) {
    private val pathResolver = WorkspacePathResolver(rootPath = rootPath, rootRealPath = rootRealPath)
    private val writeResolver = WorkspaceWriteResolver(rootPath = rootPath, pathResolver = pathResolver)

    fun resolveInsideWorkspace(
        rawPath: String,
        defaultPath: String = ".",
        allowMissingLeaf: Boolean = false,
    ): Path =
        pathResolver.resolveInsideWorkspace(
            rawPath = rawPath,
            defaultPath = defaultPath,
            allowMissingLeaf = allowMissingLeaf,
        )

    fun resolveExistingDirectory(
        rawPath: String,
        defaultPath: String = ".",
    ): Path {
        val resolved = resolveInsideWorkspace(rawPath, defaultPath = defaultPath, allowMissingLeaf = false)
        if (!Files.isDirectory(resolved)) {
            fsFailure(
                code = "not_a_directory",
                message = "Path is not a directory: ${resolved.toAbsolutePath()}",
            )
        }
        return resolved
    }

    fun resolveExistingFile(rawPath: String): Path {
        val resolved = resolveInsideWorkspace(rawPath, defaultPath = ".", allowMissingLeaf = false)
        if (!Files.isRegularFile(resolved)) {
            fsFailure(
                code = "not_a_file",
                message = "Path is not a file: ${resolved.toAbsolutePath()}",
            )
        }
        return resolved
    }

    fun resolveForWrite(
        rawPath: String,
        createParentDirectories: Boolean,
    ): Path = writeResolver.resolveForWrite(rawPath, createParentDirectories)

    fun toWorkspaceRelative(path: Path): String {
        val absolute = path.toAbsolutePath().normalize()
        if (absolute == rootPath || absolute == rootRealPath) {
            return "."
        }
        val relative =
            when {
                absolute.startsWith(rootPath) -> rootPath.relativize(absolute)
                absolute.startsWith(rootRealPath) -> rootRealPath.relativize(absolute)
                else -> absolute
            }
        return relative.toString().ifBlank { "." }.replace('\\', '/')
    }
}

internal object AgentFileSystemSandbox {
    fun prepare(settings: AgentFileSystemSettings): AgentFileSystemWorkspace {
        val absoluteWorkspace = resolveWorkspacePath(settings.path)
        WorkspaceRootPreparer.ensureExists(absoluteWorkspace)
        val realWorkspace =
            runCatching { absoluteWorkspace.toRealPath() }
                .getOrElse { error ->
                    fsFailure(
                        code = "io_error",
                        message = "Failed to resolve workspace path: ${absoluteWorkspace.toAbsolutePath()}",
                        cause = error,
                    )
                }
        return AgentFileSystemWorkspace(
            rootPath = absoluteWorkspace,
            rootRealPath = realWorkspace,
            access = settings.access,
        )
    }

    private fun resolveWorkspacePath(rawPath: String): Path {
        val normalizedPath = rawPath.trim().ifBlank { DEFAULT_AGENT_WORKSPACE_PATH }
        return runCatching { Paths.get(normalizedPath).toAbsolutePath().normalize() }
            .getOrElse {
                fsFailure(
                    code = "invalid_argument",
                    message = "Invalid workspace path: $rawPath",
                )
            }
    }
}

private class WorkspacePathResolver(
    private val rootPath: Path,
    private val rootRealPath: Path,
) {
    fun resolveInsideWorkspace(
        rawPath: String,
        defaultPath: String,
        allowMissingLeaf: Boolean,
    ): Path {
        val normalizedInput = rawPath.trim().ifBlank { defaultPath }
        val parsed = parsePath(normalizedInput, rawPath)
        val absolute = if (parsed.isAbsolute) parsed.normalize() else rootPath.resolve(parsed).normalize()
        if (allowMissingLeaf) {
            ensureMissingPathInside(absolute)
        } else {
            ensureExistingPathInside(absolute)
        }
        return absolute
    }

    fun ensureExistingPathInside(path: Path) {
        if (!Files.exists(path)) {
            fsFailure(code = "path_not_found", message = "Path not found: ${path.toAbsolutePath()}")
        }
        val realPath = resolveRealPath(path)
        if (!realPath.startsWith(rootRealPath)) {
            fsFailure(
                code = "path_outside_workspace",
                message = "Path is outside workspace: ${path.toAbsolutePath()}",
            )
        }
    }

    fun ensureMissingPathInside(path: Path) {
        val existingAncestor = findExistingAncestor(path) ?: rootPath
        val ancestorReal = resolveRealPath(existingAncestor)
        if (!ancestorReal.startsWith(rootRealPath)) {
            fsFailure(
                code = "path_outside_workspace",
                message = "Path is outside workspace: ${path.toAbsolutePath()}",
            )
        }
    }

    private fun parsePath(
        normalizedInput: String,
        rawPath: String,
    ): Path =
        runCatching { Paths.get(normalizedInput) }
            .getOrElse {
                fsFailure(code = "invalid_argument", message = "Invalid path: $rawPath")
            }

    private fun resolveRealPath(path: Path): Path =
        runCatching { path.toRealPath() }
            .getOrElse { error ->
                fsFailure(
                    code = "io_error",
                    message = "Failed to resolve path: ${path.toAbsolutePath()}",
                    cause = error,
                )
            }

    private fun findExistingAncestor(path: Path): Path? {
        var cursor: Path? = path
        while (cursor != null && !Files.exists(cursor)) {
            cursor = cursor.parent
        }
        return cursor
    }
}

private class WorkspaceWriteResolver(
    private val rootPath: Path,
    private val pathResolver: WorkspacePathResolver,
) {
    fun resolveForWrite(
        rawPath: String,
        createParentDirectories: Boolean,
    ): Path {
        val resolved =
            pathResolver.resolveInsideWorkspace(
                rawPath = rawPath,
                defaultPath = ".",
                allowMissingLeaf = true,
            )
        if (Files.exists(resolved)) {
            pathResolver.ensureExistingPathInside(resolved)
        } else {
            val parent = resolved.parent ?: rootPath
            if (Files.exists(parent)) {
                pathResolver.ensureExistingPathInside(parent)
                if (!Files.isDirectory(parent)) {
                    fsFailure(
                        code = "not_a_directory",
                        message = "Parent path is not a directory: ${parent.toAbsolutePath()}",
                    )
                }
            } else {
                if (!createParentDirectories) {
                    fsFailure(
                        code = "path_not_found",
                        message = "Parent directory does not exist: ${parent.toAbsolutePath()}",
                    )
                }
                pathResolver.ensureMissingPathInside(parent)
                runCatching { Files.createDirectories(parent) }
                    .getOrElse { error ->
                        fsFailure(
                            code = "io_error",
                            message = "Failed to create parent directories for ${resolved.toAbsolutePath()}",
                            cause = error,
                        )
                    }
            }
        }
        return resolved
    }
}

private object WorkspaceRootPreparer {
    private val defaultWorkspacePath = Paths.get(DEFAULT_AGENT_WORKSPACE_PATH).toAbsolutePath().normalize()

    fun ensureExists(absoluteWorkspace: Path) {
        if (Files.exists(absoluteWorkspace)) {
            ensureDirectory(absoluteWorkspace)
            return
        }

        if (absoluteWorkspace == defaultWorkspacePath) {
            runCatching { Files.createDirectories(absoluteWorkspace) }
                .getOrElse { error ->
                    fsFailure(
                        code = "io_error",
                        message = "Failed to create workspace directory: ${absoluteWorkspace.toAbsolutePath()}",
                        cause = error,
                    )
                }
            return
        }

        fsFailure(
            code = "workspace_missing_non_default",
            message = "Workspace directory does not exist: ${absoluteWorkspace.toAbsolutePath()}",
        )
    }

    private fun ensureDirectory(path: Path) {
        if (!Files.isDirectory(path)) {
            fsFailure(
                code = "not_a_directory",
                message = "Workspace path is not a directory: ${path.toAbsolutePath()}",
            )
        }
    }
}

private fun fsFailure(
    code: String,
    message: String,
    cause: Throwable? = null,
): Nothing =
    throw AgentFileSystemException(
        code = code,
        message = message,
        cause = cause,
    )
