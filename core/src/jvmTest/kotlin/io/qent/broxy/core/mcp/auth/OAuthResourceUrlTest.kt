package io.qent.broxy.core.mcp.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthResourceUrlTest {
    @Test
    fun resolve_oauth_resource_url_rewrites_ws_schemes() {
        assertEquals("http://example.com/mcp", resolveOAuthResourceUrl("ws://example.com/mcp"))
        assertEquals("https://example.com/mcp", resolveOAuthResourceUrl("wss://example.com/mcp"))
        assertEquals("https://example.com/mcp", resolveOAuthResourceUrl("https://example.com/mcp"))
    }
}
