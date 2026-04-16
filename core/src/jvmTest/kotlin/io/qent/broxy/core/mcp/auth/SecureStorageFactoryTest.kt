package io.qent.broxy.core.mcp.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SecureStorageFactoryTest {
    @Test
    fun unavailable_storage_warns_once() {
        val original = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            val logger = CapturingLogger()
            val storage = SecureStorageFactory.create("broxy", logger)

            assertFalse(storage.isAvailable)
            storage.read("alpha")
            storage.write("alpha", "secret")
            storage.delete("alpha")

            val warnings = logger.messages.filter { it.contains("OAuth secure storage disabled") }
            assertEquals(1, warnings.size)
        } finally {
            if (original != null) {
                System.setProperty("os.name", original)
            }
        }
    }
}
