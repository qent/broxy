package io.qent.broxy.core.mcp.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WwwAuthenticateChallengeTest {
    @Test
    fun parse_www_authenticate_header_extracts_bearer_params() {
        val header = "Bearer realm=\"example\""
        val challenges = parseWwwAuthenticateHeader(header)

        assertEquals(1, challenges.size)
        val challenge = challenges.first()
        assertEquals("Bearer", challenge.scheme)
        assertEquals("example", challenge.params["realm"])
    }

    @Test
    fun parse_www_authenticate_header_ignores_non_bearer() {
        val header = "Basic realm=\"test\""
        val challenges = parseWwwAuthenticateHeader(header)
        assertTrue(challenges.isEmpty())
    }
}
