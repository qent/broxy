package io.qent.broxy.ui.adapter.catalog

import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
                        ),
                    ),
            )
        val stdioSession = CatalogInstallPlanner.buildInstallSession(stdioDetail).getOrThrow()
        val stdioInstallResult =
            CatalogInstallPlanner
                .buildInstallResult(
                    session = stdioSession,
                    displayName = "STDIO Prod",
                    fieldValues = emptyMap(),
                ).getOrThrow()
        val stdioTransport = assertIs<UiStdioDraft>(stdioInstallResult.draft.transport)
        assertEquals("npx", stdioTransport.command)
        assertEquals(listOf("@example/server"), stdioTransport.args)
    }
}
