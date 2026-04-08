package io.qent.broxy.core.config

import io.qent.broxy.core.utils.ConfigurationException
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentVariableResolverTest {
    @Test
    fun resolveString_supports_multiple_placeholder_styles() {
        val resolver =
            EnvironmentVariableResolver(
                envProvider = { mapOf("TOKEN" to "abc", "USER" to "dolf", "SCOPED" to "scoped") },
            )

        val resolved = resolver.resolveString("t=\${TOKEN}-u={USER}-s=\${env:SCOPED}")

        assertEquals("t=abc-u=dolf-s=scoped", resolved)
    }

    @Test
    fun resolveString_supports_claude_default_syntax() {
        val resolver =
            EnvironmentVariableResolver(
                envProvider = { mapOf("TOKEN" to "", "PRESENT" to "value") },
            )

        val resolved = resolver.resolveString("a=\${MISSING:-fallback};b=\${TOKEN:-default};c=\${PRESENT:-x}")

        assertEquals("a=fallback;b=default;c=value", resolved)
    }

    @Test
    fun resolveString_resolves_workspace_placeholders_from_context() {
        val resolver = EnvironmentVariableResolver(envProvider = { emptyMap() })
        val context =
            EnvironmentVariableResolver.ResolutionContext(
                workspaceFolder = Paths.get("/tmp/project-root"),
            )

        val resolved =
            resolver.resolveString(
                "root=\${workspaceFolder};base=\${workspaceFolderBasename}",
                context = context,
            )

        assertEquals("root=/tmp/project-root;base=project-root", resolved)
    }

    @Test
    fun resolveString_resolves_user_home_and_path_separator() {
        val resolver = EnvironmentVariableResolver(envProvider = { emptyMap() })
        val context =
            EnvironmentVariableResolver.ResolutionContext(
                userHome = "/home/dolf",
                pathSeparator = "/",
            )

        val resolved =
            resolver.resolveString(
                "home=\${userHome};sep=\${pathSeparator};slash=\${/}",
                context = context,
            )

        assertEquals("home=/home/dolf;sep=/;slash=/", resolved)
    }

    @Test
    fun resolveString_resolves_input_placeholder_with_exact_name_then_fallback() {
        val resolver =
            EnvironmentVariableResolver(
                envProvider = {
                    mapOf(
                        "api-key" to "exact",
                        "API_KEY" to "fallback",
                    )
                },
            )

        val exact = resolver.resolveString("k=\${input:api-key}")
        val fallbackResolver =
            EnvironmentVariableResolver(
                envProvider = { mapOf("API_KEY" to "fallback") },
            )
        val fallback = fallbackResolver.resolveString("k=\${input:api-key}")

        assertEquals("k=exact", exact)
        assertEquals("k=fallback", fallback)
    }

    @Test
    fun resolveString_throws_for_missing_input_placeholder_env() {
        val resolver = EnvironmentVariableResolver(envProvider = { emptyMap() })

        assertFailsWith<ConfigurationException> {
            resolver.resolveString("k=\${input:api-key}")
        }
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

        val missing = resolver.missingVars("\${MISSING}:\${MISSING}:{PRESENT}:\${workspaceFolder}")

        assertEquals(listOf("MISSING"), missing)
    }

    @Test
    fun missingVars_ignores_claude_default_syntax() {
        val resolver = EnvironmentVariableResolver(envProvider = { emptyMap() })

        val missing = resolver.missingVars("\${MISSING:-fallback}")

        assertTrue(missing.isEmpty())
    }

    @Test
    fun resolveString_keeps_cursor_placeholders_unresolved() {
        val resolver = EnvironmentVariableResolver(envProvider = { mapOf("TOKEN" to "abc") })

        val resolved = resolver.resolveString("root=\${workspaceFolder};token=\${TOKEN}")

        assertEquals("root=\${workspaceFolder};token=abc", resolved)
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
