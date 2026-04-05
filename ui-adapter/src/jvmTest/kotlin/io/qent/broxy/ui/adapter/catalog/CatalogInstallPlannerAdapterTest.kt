package io.qent.broxy.ui.adapter.catalog

import io.qent.broxy.ui.adapter.models.UiAuthConfig
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CatalogInstallPlannerAdapterTest {
    @Test
    fun buildInstallResult_maps_streamable_transport_to_ui_draft() {
        val detail =
            CatalogServerDetail(
                name = "streamable-server",
                title = "Streamable Server",
                description = "desc",
                version = "1.0.0",
                remotes =
                    listOf(
                        CatalogRemoteTransport(
                            type = "streamable-http",
                            url = "https://example.com/mcp",
                            oauth =
                                CatalogRemoteOAuth(
                                    type = "oauth",
                                    clientId = "{client_id}",
                                    clientSecret = "{client_secret}",
                                    callbackPort = JsonPrimitive("{callback_port}"),
                                    authServerMetadataUrl = "https://mcp.example.com/.well-known/oauth-authorization-server",
                                    redirectUri = "https://localhost:{callback_port}/callback",
                                    tokenEndpointAuthMethod = "client_secret_post",
                                    authorizationServer = "https://issuer.example.com",
                                    allowDynamicRegistration = false,
                                ),
                            variables =
                                mapOf(
                                    "client_id" to CatalogInput(default = "id-1", isRequired = true),
                                    "client_secret" to CatalogInput(default = "secret-1", isRequired = true, isSecret = true),
                                    "callback_port" to CatalogInput(default = "3118"),
                                ),
                        ),
                    ),
            )

        val session = CatalogInstallPlanner.buildInstallSession(detail).getOrThrow()
        val installResult =
            CatalogInstallPlanner
                .buildInstallResult(
                    session = session,
                    displayName = "Streamable Prod",
                    fieldValues = emptyMap(),
                ).getOrThrow()

        assertEquals("streamable-server", installResult.draft.id)
        assertEquals("Streamable Prod", installResult.draft.name)
        assertEquals(true, installResult.draft.enabled)
        assertNull(installResult.draft.originalId)
        assertNull(installResult.draft.iconPath)

        val transport = assertIs<UiStreamableHttpDraft>(installResult.draft.transport)
        assertEquals("https://example.com/mcp", transport.url)
        assertEquals(emptyMap(), transport.headers)
        val auth = assertIs<UiAuthConfig.OAuth>(assertNotNull(installResult.draft.auth))
        assertEquals("id-1", auth.clientId)
        assertEquals("secret-1", auth.clientSecret)
        assertEquals(3118, auth.callbackPort)
        assertEquals("https://mcp.example.com/.well-known/oauth-authorization-server", auth.authServerMetadataUrl)
        assertEquals("https://localhost:3118/callback", auth.redirectUri)
        assertEquals("client_secret_post", auth.tokenEndpointAuthMethod)
        assertEquals("https://issuer.example.com", auth.authorizationServer)
        assertEquals(false, auth.allowDynamicRegistration)
    }

    @Test
    fun buildInstallResult_maps_sse_and_stdio_transports_to_ui_draft() {
        val sseDetail =
            CatalogServerDetail(
                name = "sse-server",
                title = "SSE Server",
                description = "desc",
                version = "1.0.0",
                remotes =
                    listOf(
                        CatalogRemoteTransport(
                            type = "sse",
                            url = "https://example.com/sse",
                        ),
                    ),
            )
        val sseSession = CatalogInstallPlanner.buildInstallSession(sseDetail).getOrThrow()
        val sseInstallResult =
            CatalogInstallPlanner
                .buildInstallResult(
                    session = sseSession,
                    displayName = "SSE Prod",
                    fieldValues = emptyMap(),
                ).getOrThrow()
        val sseTransport = assertIs<UiHttpDraft>(sseInstallResult.draft.transport)
        assertEquals("https://example.com/sse", sseTransport.url)

        val stdioDetail =
            CatalogServerDetail(
                name = "stdio-server",
                title = "STDIO Server",
                description = "desc",
                version = "1.0.0",
                packages =
                    listOf(
                        CatalogPackage(
                            registryType = "npm",
                            identifier = "@example/server",
                            runtimeHint = "npx",
                            transport = CatalogLocalTransport(type = "stdio"),
                            environmentVariables =
                                listOf(
                                    CatalogKeyValueInput(
                                        name = "USER_GOOGLE_EMAIL",
                                        isRequired = true,
                                    ),
                                ),
                            oauth =
                                CatalogRemoteOAuth(
                                    type = "oauth",
                                    redirectUri = "http://localhost:8111/oauth2callback",
                                    stdioBootstrap =
                                        CatalogStdioBootstrap(
                                            tool = "start_google_auth",
                                            args =
                                                mapOf(
                                                    "service_name" to "gmail",
                                                    "user_google_email" to "{USER_GOOGLE_EMAIL}",
                                                ),
                                        ),
                                ),
                        ),
                    ),
            )
        val stdioSession = CatalogInstallPlanner.buildInstallSession(stdioDetail).getOrThrow()
        val stdioInstallResult =
            CatalogInstallPlanner
                .buildInstallResult(
                    session = stdioSession,
                    displayName = "STDIO Prod",
                    fieldValues =
                        mapOf(
                            "package.env.0.user-google-email" to "to.dolfin@gmail.com",
                        ),
                ).getOrThrow()
        val stdioTransport = assertIs<UiStdioDraft>(stdioInstallResult.draft.transport)
        assertEquals("npx", stdioTransport.command)
        assertEquals(listOf("@example/server"), stdioTransport.args)
        val stdioAuth = assertIs<UiAuthConfig.OAuth>(assertNotNull(stdioInstallResult.draft.auth))
        assertEquals("http://localhost:8111/oauth2callback", stdioAuth.redirectUri)
        assertNotNull(stdioAuth.stdioBootstrap)
        assertEquals("start_google_auth", stdioAuth.stdioBootstrap.tool)
        assertEquals(
            mapOf(
                "service_name" to "gmail",
                "user_google_email" to "to.dolfin@gmail.com",
            ),
            stdioAuth.stdioBootstrap.args,
        )
    }
}
