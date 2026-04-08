package io.qent.broxy.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderEndpointValidationTest {
    @Test
    fun `blank endpoint is accepted`() {
        assertTrue(isValidProviderEndpoint(""))
    }

    @Test
    fun `http and https endpoints with host are accepted`() {
        assertTrue(isValidProviderEndpoint("http://127.0.0.1:1234/v1"))
        assertTrue(isValidProviderEndpoint("https://api.openai.com/v1"))
    }

    @Test
    fun `invalid scheme is rejected`() {
        assertFalse(isValidProviderEndpoint("ftp://api.openai.com"))
    }

    @Test
    fun `missing host is rejected`() {
        assertFalse(isValidProviderEndpoint("https://"))
        assertFalse(isValidProviderEndpoint("http:///v1"))
    }

    @Test
    fun `host with whitespace is rejected`() {
        assertFalse(isValidProviderEndpoint("https://api openai.com/v1"))
    }
}
