package io.qent.broxy.core.config

import io.qent.broxy.core.utils.ConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentVariableResolverTest {
    @Test
    fun resolveString_supports_multiple_placeholder_styles() {
        val resolver = EnvironmentVariableResolver(envProvider = { mapOf("TOKEN" to "abc", "USER" to "dolf") })

        val resolved = resolver.resolveString("t=\${TOKEN}-u={USER}")

        assertEquals("t=abc-u=dolf", resolved)
    }

    @Test
    fun resolveString_throws_for_missing_env_var() {
        val resolver = EnvironmentVariableResolver(envProvider = { emptyMap() })

        assertFailsWith<ConfigurationException> {
            resolver.resolveString("x=\${MISSING}")
        }
    }

    @Test
    fun missingVars_deduplicates_missing_placeholders() {
        val resolver = EnvironmentVariableResolver(envProvider = { mapOf("PRESENT" to "ok") })

        val missing = resolver.missingVars("\${MISSING}:\${MISSING}:{PRESENT}")

        assertEquals(listOf("MISSING"), missing)
    }

    @Test
    fun hasPlaceholders_detects_placeholders() {
        val resolver = EnvironmentVariableResolver(envProvider = { emptyMap() })

        assertTrue(resolver.hasPlaceholders("\${A}"))
        assertTrue(resolver.hasPlaceholders("{B}"))
        assertFalse(resolver.hasPlaceholders("plain"))
    }

    @Test
    fun sanitizeForLogging_masks_sensitive_keys() {
        val resolver = EnvironmentVariableResolver(envProvider = { emptyMap() })
        val sanitized =
            resolver.sanitizeForLogging(
                mapOf(
                    "API_TOKEN" to "secret",
                    "password" to "secret",
                    "client_key" to "secret",
                    "safe" to "ok",
                ),
            )

        assertEquals("***", sanitized["API_TOKEN"])
        assertEquals("***", sanitized["password"])
        assertEquals("***", sanitized["client_key"])
        assertEquals("ok", sanitized["safe"])
    }
}
