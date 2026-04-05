package io.qent.broxy.registry.catalog

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CatalogSchemaDecodingTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    @Test
    fun decode_server_detail_with_stdio_oauth_bootstrap_in_package() {
        val payload =
            """
            {
              "name": "io.qent.broxy/google-workspace",
              "title": "Google Workspace",
              "description": "desc",
              "version": "latest",
              "packages": [
                {
                  "registryType": "pypi",
                  "identifier": "workspace-mcp",
                  "runtimeHint": "uvx",
                  "transport": {
                    "type": "stdio"
                  },
                  "oauth": {
                    "type": "oauth",
                    "redirectUri": "http://localhost:8000/oauth2callback",
                    "stdioBootstrap": {
                      "tool": "start_google_auth",
                      "args": {
                        "service_name": "gmail"
                      }
                    }
                  }
                }
              ]
            }
            """.trimIndent()

        val detail = json.decodeFromString<CatalogServerDetail>(payload)
        val oauth = assertNotNull(detail.packages.single().oauth)
        assertEquals("http://localhost:8000/oauth2callback", oauth.redirectUri)
        val bootstrap = assertNotNull(oauth.stdioBootstrap)
        assertEquals("start_google_auth", bootstrap.tool)
        assertEquals(mapOf("service_name" to "gmail"), bootstrap.args)
    }
}
