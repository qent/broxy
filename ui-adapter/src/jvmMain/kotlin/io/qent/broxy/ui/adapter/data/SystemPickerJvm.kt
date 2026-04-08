package io.qent.broxy.ui.adapter.data

import java.awt.Component
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.io.File
import java.io.FilenameFilter
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

private const val MACOS_DIRECTORIES_DIALOG_PROPERTY = "apple.awt.fileDialogForDirectories"
private const val WORKSPACE_DIRECTORY_DIALOG_TITLE = "Select workspace directory"
private const val MACOS_SILENT_FAILURE_THRESHOLD_MILLIS = 80L
internal val systemPropertyMutationLock = Any()
internal val macOsDirectoriesDialogPropertyLock = Any()

internal interface DirectoryPickerBackend {
    fun pick(initialDirectory: File?): String?
}

internal interface FilePickerBackend {
    fun pick(
        request: FilePickRequest,
        initialDirectory: File?,
    ): String?
}

internal class SystemPickerJvm(
    private val isMacOs: Boolean = isMacOsPlatform(),
    private val macDirectoryBackend: DirectoryPickerBackend = MacOsDirectoryPickerBackend(),
    private val swingDirectoryBackend: DirectoryPickerBackend = SwingDirectoryPickerBackend(),
    private val nativeFileBackend: FilePickerBackend = NativeFilePickerBackend(),
    private val swingFileBackend: FilePickerBackend = SwingFilePickerBackend(),
) : SystemPicker {
    override fun pickDirectory(initialPath: String?): Result<String?> =
        runCatching {
            val initialDirectory = resolveExistingDirectory(initialPath)
            if (!isMacOs) {
                return@runCatching swingDirectoryBackend.pick(initialDirectory)
            }
            try {
                macDirectoryBackend.pick(initialDirectory)
            } catch (nativeError: Throwable) {
                try {
                    swingDirectoryBackend.pick(initialDirectory)
                } catch (swingError: Throwable) {
                    swingError.addSuppressed(nativeError)
                    throw swingError
                }
            }
        }

    override fun pickFile(request: FilePickRequest): Result<String?> =
        runCatching {
            val initialDirectory = resolveExistingDirectory(request.initialPath)
            try {
                nativeFileBackend.pick(request, initialDirectory)
            } catch (nativeError: Throwable) {
                try {
                    swingFileBackend.pick(request, initialDirectory)
                } catch (swingError: Throwable) {
                    swingError.addSuppressed(nativeError)
                    throw swingError
                }
            }
        }
}

internal class SwingDirectoryPickerBackend : DirectoryPickerBackend {
    override fun pick(initialDirectory: File?): String? {
        val chooser =
            JFileChooser().apply {
                dialogTitle = WORKSPACE_DIRECTORY_DIALOG_TITLE
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isMultiSelectionEnabled = false
                isAcceptAllFileFilterUsed = false
                initialDirectory?.let {
                    currentDirectory = it
                    selectedFile = it
                }
            }
        val owner = resolveSwingOwner()
        return when (runOnEdtSync { chooser.showOpenDialog(owner) }) {
            JFileChooser.APPROVE_OPTION -> chooser.selectedFile?.absolutePath
            JFileChooser.CANCEL_OPTION -> null
            else -> error("Directory chooser failed")
        }
    }
}

internal class SwingFilePickerBackend : FilePickerBackend {
    override fun pick(
        request: FilePickRequest,
        initialDirectory: File?,
    ): String? {
        val normalizedExtensions = normalizeExtensions(request.allowedExtensions)
        val chooser =
            JFileChooser().apply {
                dialogTitle = request.title
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = false
                isAcceptAllFileFilterUsed = normalizedExtensions.isEmpty()
                initialDirectory?.let {
                    currentDirectory = it
                    selectedFile = it
                }
                if (normalizedExtensions.isNotEmpty()) {
                    fileFilter = FileNameExtensionFilter("Allowed files", *normalizedExtensions.toTypedArray())
                }
            }
        val owner = resolveSwingOwner()
        return when (runOnEdtSync { chooser.showOpenDialog(owner) }) {
            JFileChooser.APPROVE_OPTION ->
                chooser.selectedFile
                    ?.toPath()
                    ?.toAbsolutePath()
                    ?.normalize()
                    ?.toString()
            JFileChooser.CANCEL_OPTION -> null
            else -> error("File chooser failed")
        }
    }
}

internal class NativeFilePickerBackend : FilePickerBackend {
    override fun pick(
        request: FilePickRequest,
        initialDirectory: File?,
    ): String? =
        runOnEdtSync {
            val normalizedExtensions = normalizeExtensions(request.allowedExtensions)
            val dialog =
                createFileDialog(request.title).apply {
                    initialDirectory?.let { directory = it.absolutePath }
                    isMultipleMode = false
                    if (normalizedExtensions.isNotEmpty()) {
                        filenameFilter =
                            FilenameFilter { _, name ->
                                val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                                extension in normalizedExtensions
                            }
                    }
                }
            dialog.isVisible = true
            val selected =
                dialog.files
                    .firstOrNull()
                    ?.absolutePath
                    ?.takeIf { it.isNotBlank() }
                    ?: fromDirectoryAndFile(dialog)
                    ?: return@runOnEdtSync null
            normalizePath(selected)
        }

    private fun createFileDialog(title: String): FileDialog {
        val owner = resolveDialogOwner()
        return when (owner) {
            is Frame -> FileDialog(owner, title, FileDialog.LOAD)
            is Dialog -> FileDialog(owner, title, FileDialog.LOAD)
            else -> FileDialog(null as Frame?, title, FileDialog.LOAD)
        }
    }
}

internal class MacOsDirectoryPickerBackend(
    private val propertyLock: Any = macOsDirectoriesDialogPropertyLock,
) : DirectoryPickerBackend {
    override fun pick(initialDirectory: File?): String? =
        runOnEdtSync {
            withTemporarySystemProperty(
                propertyName = MACOS_DIRECTORIES_DIALOG_PROPERTY,
                newValue = "true",
                lock = propertyLock,
            ) {
                val startedAt = System.nanoTime()
                val dialog =
                    createMacOsDirectoryDialog().apply {
                        initialDirectory?.let { directory = it.absolutePath }
                        isMultipleMode = false
                    }
                dialog.isVisible = true
                val selected = selectedPath(dialog, initialDirectory)
                if (selected != null) {
                    return@withTemporarySystemProperty selected
                }
                if (looksLikeSilentFailure(dialog, initialDirectory, startedAt)) {
                    error("Native macOS directory picker failed to open")
                }
                null
            }
        }

    private fun createMacOsDirectoryDialog(): FileDialog {
        val owner = resolveDialogOwner()
        return when (owner) {
            is Frame -> FileDialog(owner, WORKSPACE_DIRECTORY_DIALOG_TITLE, FileDialog.LOAD)
            is Dialog -> FileDialog(owner, WORKSPACE_DIRECTORY_DIALOG_TITLE, FileDialog.LOAD)
            else -> FileDialog(null as Frame?, WORKSPACE_DIRECTORY_DIALOG_TITLE, FileDialog.LOAD)
        }
    }

    private fun selectedPath(
        dialog: FileDialog,
        initialDirectory: File?,
    ): String? {
        val selectedPath =
            dialog.files
                .firstOrNull()
                ?.absolutePath
                ?.takeIf { it.isNotBlank() }
                ?: fromDirectoryAndFile(dialog)
                ?: fromDirectoryOnly(dialog, initialDirectory)
                ?: return null
        return normalizePath(selectedPath)
    }

    private fun fromDirectoryOnly(
        dialog: FileDialog,
        initialDirectory: File?,
    ): String? {
        val directory = dialog.directory?.takeIf { it.isNotBlank() } ?: return null
        val initial = initialDirectory?.absolutePath?.let(::normalizePath) ?: return null
        val normalizedDirectory = normalizePath(directory)
        if (normalizedDirectory == initial) {
            return null
        }
        return directory
    }

    private fun looksLikeSilentFailure(
        dialog: FileDialog,
        initialDirectory: File?,
        startedAtNanos: Long,
    ): Boolean {
        val elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000
        if (elapsedMillis > MACOS_SILENT_FAILURE_THRESHOLD_MILLIS) {
            return false
        }
        if (dialog.files.isNotEmpty() || !dialog.file.isNullOrBlank()) {
            return false
        }
        val directory = dialog.directory?.takeIf { it.isNotBlank() } ?: return true
        val initial = initialDirectory?.absolutePath?.let(::normalizePath) ?: return false
        return normalizePath(directory) == initial
    }
}

internal fun resolveSwingOwner(): Component? {
    val owner = resolveDialogOwner()
    return when (owner) {
        is Frame -> owner
        is Dialog -> owner
        else -> null
    }
}

internal fun fromDirectoryAndFile(dialog: FileDialog): String? {
    val directory = dialog.directory?.takeIf { it.isNotBlank() } ?: return null
    val file = dialog.file?.takeIf { it.isNotBlank() } ?: return null
    return File(directory, file).absolutePath
}

internal fun resolveDialogOwner(): Window? {
    val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val activeWindow = focusManager.activeWindow
    if ((activeWindow is Frame || activeWindow is Dialog) && activeWindow.isShowing) {
        return activeWindow
    }

    val focusedWindow = focusManager.focusedWindow
    if ((focusedWindow is Frame || focusedWindow is Dialog) && focusedWindow.isShowing) {
        return focusedWindow
    }

    return Frame.getFrames().firstOrNull { it.isShowing } ?: Window.getWindows().firstOrNull { it.isShowing }
}

internal fun resolveExistingDirectory(initialPath: String?): File? {
    val normalized = initialPath?.trim()?.takeIf { it.isNotBlank() } ?: return null
    var candidate = File(normalized)
    if (candidate.exists() && candidate.isFile) {
        candidate = candidate.parentFile ?: return null
    }
    while (!candidate.exists()) {
        candidate = candidate.parentFile ?: return null
    }
    return if (candidate.isDirectory) candidate else candidate.parentFile
}

internal fun normalizeExtensions(extensions: Set<String>): Set<String> =
    extensions
        .mapNotNull { raw ->
            raw
                .trim()
                .trimStart('.')
                .lowercase(Locale.ROOT)
                .takeIf { it.isNotEmpty() }
        }.toSet()

internal fun <T> withTemporarySystemProperty(
    propertyName: String,
    newValue: String,
    lock: Any = systemPropertyMutationLock,
    block: () -> T,
): T =
    synchronized(lock) {
        val previous = System.getProperty(propertyName)
        try {
            System.setProperty(propertyName, newValue)
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(propertyName)
            } else {
                System.setProperty(propertyName, previous)
            }
        }
    }

internal fun <T> runOnEdtSync(block: () -> T): T {
    if (EventQueue.isDispatchThread()) {
        return block()
    }
    val result = AtomicReference<Result<T>>()
    EventQueue.invokeAndWait {
        result.set(runCatching { block() })
    }
    return result.get().getOrThrow()
}

internal fun isMacOsPlatform(): Boolean =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase(Locale.ROOT)
        .contains("mac")

internal fun normalizePath(path: String): String =
    File(path)
        .toPath()
        .toAbsolutePath()
        .normalize()
        .toString()
