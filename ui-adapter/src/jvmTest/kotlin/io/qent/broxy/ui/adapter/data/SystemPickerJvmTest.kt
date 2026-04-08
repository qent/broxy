package io.qent.broxy.ui.adapter.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemPickerJvmTest {
    @Test
    fun pickDirectory_onMac_nativeSuccess_returnsNative_noFallback() {
        val nativeBackend = RecordingDirectoryBackend { "/tmp/native" }
        val swingBackend = RecordingDirectoryBackend { "/tmp/swing" }
        val picker =
            SystemPickerJvm(
                isMacOs = true,
                macDirectoryBackend = nativeBackend,
                swingDirectoryBackend = swingBackend,
            )

        val result = picker.pickDirectory(null).getOrThrow()

        assertEquals("/tmp/native", result)
        assertEquals(1, nativeBackend.calls)
        assertEquals(0, swingBackend.calls)
    }

    @Test
    fun pickDirectory_onMac_nativeThrows_fallsBackToSwing() {
        val nativeBackend = RecordingDirectoryBackend { error("native_failed") }
        val swingBackend = RecordingDirectoryBackend { "/tmp/swing" }
        val picker =
            SystemPickerJvm(
                isMacOs = true,
                macDirectoryBackend = nativeBackend,
                swingDirectoryBackend = swingBackend,
            )

        val result = picker.pickDirectory(null).getOrThrow()

        assertEquals("/tmp/swing", result)
        assertEquals(1, nativeBackend.calls)
        assertEquals(1, swingBackend.calls)
    }

    @Test
    fun pickDirectory_onMac_nativeCancel_returnsNull_withoutFallback() {
        val nativeBackend = RecordingDirectoryBackend { null }
        val swingBackend = RecordingDirectoryBackend { "/tmp/swing" }
        val picker =
            SystemPickerJvm(
                isMacOs = true,
                macDirectoryBackend = nativeBackend,
                swingDirectoryBackend = swingBackend,
            )

        val result = picker.pickDirectory(null).getOrThrow()

        assertNull(result)
        assertEquals(1, nativeBackend.calls)
        assertEquals(0, swingBackend.calls)
    }

    @Test
    fun pickDirectory_onNonMac_usesSwingOnly() {
        val nativeBackend = RecordingDirectoryBackend { "/tmp/native" }
        val swingBackend = RecordingDirectoryBackend { "/tmp/swing" }
        val picker =
            SystemPickerJvm(
                isMacOs = false,
                macDirectoryBackend = nativeBackend,
                swingDirectoryBackend = swingBackend,
            )

        val result = picker.pickDirectory(null).getOrThrow()

        assertEquals("/tmp/swing", result)
        assertEquals(0, nativeBackend.calls)
        assertEquals(1, swingBackend.calls)
    }

    @Test
    fun pickDirectory_nativeThrows_andSwingThrows_propagatesFailure() {
        val nativeBackend = RecordingDirectoryBackend { throw IllegalStateException("native failed") }
        val swingBackend = RecordingDirectoryBackend { throw IllegalArgumentException("swing failed") }
        val picker =
            SystemPickerJvm(
                isMacOs = true,
                macDirectoryBackend = nativeBackend,
                swingDirectoryBackend = swingBackend,
            )

        val error = picker.pickDirectory(null).exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("swing failed", error.message)
        assertEquals(1, nativeBackend.calls)
        assertEquals(1, swingBackend.calls)
        assertTrue(
            error.suppressed.any { it is IllegalStateException && it.message == "native failed" },
            "Native failure should be attached as suppressed",
        )
    }

    @Test
    fun pickFile_nativeSuccess_returnsNative_noFallback() {
        val nativeBackend = RecordingFileBackend { _, _ -> "/tmp/icon.png" }
        val swingBackend = RecordingFileBackend { _, _ -> "/tmp/icon-from-swing.png" }
        val picker =
            SystemPickerJvm(
                nativeFileBackend = nativeBackend,
                swingFileBackend = swingBackend,
            )

        val request = FilePickRequest(title = "Select server icon", allowedExtensions = setOf("png"))
        val result = picker.pickFile(request).getOrThrow()

        assertEquals("/tmp/icon.png", result)
        assertEquals(1, nativeBackend.calls)
        assertEquals(0, swingBackend.calls)
        assertEquals(request, nativeBackend.lastRequest)
    }

    @Test
    fun pickFile_nativeThrows_fallsBackToSwing() {
        val nativeBackend = RecordingFileBackend { _, _ -> error("native_failed") }
        val swingBackend = RecordingFileBackend { _, _ -> "/tmp/icon-from-swing.png" }
        val picker =
            SystemPickerJvm(
                nativeFileBackend = nativeBackend,
                swingFileBackend = swingBackend,
            )

        val result = picker.pickFile(FilePickRequest(title = "Select server icon")).getOrThrow()

        assertEquals("/tmp/icon-from-swing.png", result)
        assertEquals(1, nativeBackend.calls)
        assertEquals(1, swingBackend.calls)
    }

    @Test
    fun pickFile_nativeThrows_andSwingThrows_propagatesFailure() {
        val nativeBackend = RecordingFileBackend { _, _ -> throw IllegalStateException("native failed") }
        val swingBackend = RecordingFileBackend { _, _ -> throw IllegalArgumentException("swing failed") }
        val picker =
            SystemPickerJvm(
                nativeFileBackend = nativeBackend,
                swingFileBackend = swingBackend,
            )

        val error = picker.pickFile(FilePickRequest(title = "Select server icon")).exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("swing failed", error.message)
        assertTrue(
            error.suppressed.any { it is IllegalStateException && it.message == "native failed" },
            "Native failure should be attached as suppressed",
        )
    }

    @Test
    fun normalizeExtensions_trimsDot_andLowercases() {
        val normalized = normalizeExtensions(setOf(".PNG", " jpg ", ""))

        assertEquals(setOf("png", "jpg"), normalized)
    }

    @Test
    fun resolveExistingDirectory_missingPath_returnsNearestExistingParent() {
        val root = Files.createTempDirectory("broxy-picker-missing").toFile()
        try {
            val missing = root.resolve("nested/a/b/c").absolutePath

            val resolved = resolveExistingDirectory(missing)

            assertEquals(root.canonicalFile, resolved?.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolveExistingDirectory_filePath_returnsParentDirectory() {
        val root = Files.createTempDirectory("broxy-picker-file").toFile()
        try {
            val file = root.resolve("workspace.txt")
            file.writeText("x")

            val resolved = resolveExistingDirectory(file.absolutePath)

            assertEquals(root.canonicalFile, resolved?.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolveExistingDirectory_blankOrNull_returnsNull() {
        assertNull(resolveExistingDirectory(null))
        assertNull(resolveExistingDirectory(""))
        assertNull(resolveExistingDirectory("   "))
    }

    @Test
    fun resolveExistingDirectory_existingDirectory_returnsSameDirectory() {
        val root = Files.createTempDirectory("broxy-picker-dir").toFile()
        try {
            val resolved = resolveExistingDirectory(root.absolutePath)

            assertEquals(root.canonicalFile, resolved?.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun withTemporarySystemProperty_restoresOnException() {
        val key = "broxy.test.systempicker.${System.nanoTime()}"
        System.clearProperty(key)

        assertFailsWith<IllegalStateException> {
            withTemporarySystemProperty(
                propertyName = key,
                newValue = "temporary",
                lock = Any(),
            ) {
                assertEquals("temporary", System.getProperty(key))
                throw IllegalStateException("boom")
            }
        }

        assertNull(System.getProperty(key))
    }

    @Test
    fun resolveExistingDirectory_relativeRootMissing_returnsNull() {
        val resolved = resolveExistingDirectory("broxy-nonexistent-${System.nanoTime()}")

        assertNull(resolved)
    }

    private class RecordingDirectoryBackend(
        private val action: (File?) -> String?,
    ) : DirectoryPickerBackend {
        var calls: Int = 0
            private set

        override fun pick(initialDirectory: File?): String? {
            calls += 1
            return action(initialDirectory)
        }
    }

    private class RecordingFileBackend(
        private val action: (FilePickRequest, File?) -> String?,
    ) : FilePickerBackend {
        var calls: Int = 0
            private set
        var lastRequest: FilePickRequest? = null
            private set

        override fun pick(
            request: FilePickRequest,
            initialDirectory: File?,
        ): String? {
            calls += 1
            lastRequest = request
            return action(request, initialDirectory)
        }
    }
}
