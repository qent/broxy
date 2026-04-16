package io.qent.broxy.ui.adapter.icons

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerIconRepositoryJvmTest {
    @Test
    fun deleteIcon_removes_file_only_inside_icons_directory() =
        runTest {
            val baseDir = Files.createTempDirectory("broxy-icons")
            val iconsDir = baseDir.resolve("icons")
            Files.createDirectories(iconsDir)
            val iconFile = iconsDir.resolve("a.png")
            Files.writeString(iconFile, "x")
            val outside = baseDir.resolve("outside.png")
            Files.writeString(outside, "x")

            val repository = ServerIconRepositoryJvm(baseDir = baseDir)

            val deleteInside = repository.deleteIcon("icons/a.png")
            val deleteOutside = repository.deleteIcon("../outside.png")

            assertTrue(deleteInside.isSuccess)
            assertFalse(Files.exists(iconFile))
            assertTrue(deleteOutside.isSuccess)
            assertTrue(Files.exists(outside))
        }
}
