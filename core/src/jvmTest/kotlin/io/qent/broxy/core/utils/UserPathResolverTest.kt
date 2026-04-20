package io.qent.broxy.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserPathResolverTest {
    @Test
    fun resolve_path_key_uses_existing_env_key() {
        assertEquals("Path", UserPathResolver.resolvePathKey(mapOf("Path" to "bin")))
        assertEquals("PATH", UserPathResolver.resolvePathKey(mapOf("PATH" to "bin")))
        assertEquals("PaTh", UserPathResolver.resolvePathKey(mapOf("PaTh" to "bin")))
    }

    @Test
    fun resolve_returns_sanitized_path_when_available() {
        val resolved = UserPathResolver.resolve()
        if (resolved != null) {
            assertTrue(resolved.isNotBlank())
            assertFalse(resolved.contains("__BROXY_PATH_START__"))
            assertFalse(resolved.contains("__BROXY_PATH_END__"))
        }
    }
}
