package io.qent.broxy.ui.adapter.icons

import io.qent.broxy.ui.adapter.data.FilePickRequest
import io.qent.broxy.ui.adapter.data.SystemPicker
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerIconRepositoryJvmTest {
    @Test
    fun pickAndImportIcon_cancel_returnsNull_andCreatesNoFiles() =
        runTest {
            val baseDir = Files.createTempDirectory("broxy-server-icons-cancel")
            try {
                val picker =
                    FakeSystemPicker(
                        pickFileResult = Result.success(null),
                    )
                val repository = ServerIconRepositoryJvm(systemPicker = picker, baseDir = baseDir)

                val result = repository.pickAndImportIcon()

                assertTrue(result.isSuccess)
                assertEquals(null, result.getOrNull())
                val iconsDir = baseDir.resolve("icons")
                assertFalse(Files.exists(iconsDir))
            } finally {
                baseDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun pickAndImportIcon_copiesSelectedFile_andReturnsRelativePath() =
        runTest {
            val baseDir = Files.createTempDirectory("broxy-server-icons-import")
            val sourceDir = Files.createTempDirectory("broxy-server-icons-source")
            try {
                val selected = sourceDir.resolve("logo.PNG")
                val payload = byteArrayOf(1, 2, 3, 4)
                Files.write(selected, payload)

                val picker =
                    FakeSystemPicker(
                        pickFileResult = Result.success(selected.toString()),
                    )
                val repository = ServerIconRepositoryJvm(systemPicker = picker, baseDir = baseDir)

                val result = repository.pickAndImportIcon()

                assertTrue(result.isSuccess)
                val relativePath = result.getOrThrow()
                assertNotNull(relativePath)
                assertTrue(relativePath.startsWith("icons/server-icon-"))
                assertTrue(relativePath.endsWith(".png"))
                val copied = baseDir.resolve(relativePath)
                assertTrue(Files.exists(copied))
                assertTrue(payload.contentEquals(copied.readBytes()))
                assertEquals(
                    FilePickRequest(
                        title = "Select server icon",
                        initialPath = baseDir.resolve("icons").toString(),
                        allowedExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff"),
                    ),
                    picker.lastPickFileRequest,
                )
            } finally {
                baseDir.toFile().deleteRecursively()
                sourceDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun pickAndImportIcon_pickerFailure_isPropagated() =
        runTest {
            val baseDir = Files.createTempDirectory("broxy-server-icons-failure")
            try {
                val picker =
                    FakeSystemPicker(
                        pickFileResult = Result.failure(IllegalStateException("picker_failed")),
                    )
                val repository = ServerIconRepositoryJvm(systemPicker = picker, baseDir = baseDir)

                val result = repository.pickAndImportIcon()

                assertTrue(result.isFailure)
                val error = result.exceptionOrNull()
                assertTrue(error is IllegalStateException)
                assertEquals("picker_failed", error.message)
            } finally {
                baseDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun deleteIcon_deletesOnlyPathsInsideIconsDirectory() =
        runTest {
            val baseDir = Files.createTempDirectory("broxy-server-icons-delete")
            try {
                val picker = FakeSystemPicker()
                val repository = ServerIconRepositoryJvm(systemPicker = picker, baseDir = baseDir)
                val iconsDir = baseDir.resolve("icons")
                Files.createDirectories(iconsDir)
                val insideIcon = iconsDir.resolve("inside.png")
                val outsideIcon = baseDir.resolve("outside.png")
                Files.write(insideIcon, byteArrayOf(1))
                Files.write(outsideIcon, byteArrayOf(2))

                val insideDelete = repository.deleteIcon("icons/inside.png")
                val outsideDelete = repository.deleteIcon("../outside.png")

                assertTrue(insideDelete.isSuccess)
                assertTrue(outsideDelete.isSuccess)
                assertFalse(Files.exists(insideIcon))
                assertTrue(Files.exists(outsideIcon))
            } finally {
                baseDir.toFile().deleteRecursively()
            }
        }

    private class FakeSystemPicker(
        private val pickDirectoryResult: Result<String?> = Result.success(null),
        private val pickFileResult: Result<String?> = Result.success(null),
    ) : SystemPicker {
        var lastPickFileRequest: FilePickRequest? = null
            private set

        override fun pickDirectory(initialPath: String?): Result<String?> = pickDirectoryResult

        override fun pickFile(request: FilePickRequest): Result<String?> {
            lastPickFileRequest = request
            return pickFileResult
        }
    }
}
