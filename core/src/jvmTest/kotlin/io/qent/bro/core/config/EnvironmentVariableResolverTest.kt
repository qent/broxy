package io.qent.bro.core.config

import io.qent.bro.core.utils.ConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvironmentVariableResolverTest {
    @Test
    fun resolves_placeholders_and_validates() {
        val env = mapOf("GITHUB_TOKEN" to "abc123", "USER" to "u")
        val r = EnvironmentVariableResolver(envProvider = { env })
        assertEquals("abc123", r.resolveString("${'$'}{GITHUB_TOKEN}"))
        assertEquals("Bearer abc123", r.resolveString("Bearer ${'$'}{GITHUB_TOKEN}"))
        assertEquals("http://x/u", r.resolveString("http://x/${'$'}{USER}"))
        assertEquals("abc123", r.resolveString("{GITHUB_TOKEN}"))
        assertEquals("Bearer abc123", r.resolveString("Bearer {GITHUB_TOKEN}"))
        assertEquals("http://x/u", r.resolveString("http://x/{USER}"))
        assertFailsWith<ConfigurationException> { r.resolveString("${'$'}{MISSING}") }
        assertFailsWith<ConfigurationException> { r.resolveString("{MISSING}") }
    }
}
