package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import io.qent.broxy.ui.adapter.models.UiWebSocketDraft
import io.qent.broxy.ui.adapter.models.UiWebSocketTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreIntentCommonTest {
    @Test
    fun reorderByIds_reorders_when_input_is_valid() {
        data class Item(
            val id: String,
            val value: Int,
        )

        val items = listOf(Item("a", 1), Item("b", 2), Item("c", 3))
        val reordered = reorderByIds(items, listOf("c", "a", "b")) { it.id }

        assertEquals(listOf("c", "a", "b"), reordered?.map { it.id })
    }

    @Test
    fun reorderByIds_returns_null_for_invalid_requests() {
        data class Item(
            val id: String,
            val value: Int,
        )

        val items = listOf(Item("a", 1), Item("b", 2))
        assertNull(reorderByIds(items, listOf("a")) { it.id })
        assertNull(reorderByIds(items, listOf("a", "a")) { it.id })
        assertNull(reorderByIds(items, listOf("a", "z")) { it.id })
    }

    @Test
    fun isSupportedExternalUrl_accepts_http_and_https_only() {
        assertTrue(isSupportedExternalUrl("http://example.com"))
        assertTrue(isSupportedExternalUrl(" https://example.com/path "))
        assertFalse(isSupportedExternalUrl(""))
        assertFalse(isSupportedExternalUrl("   "))
        assertFalse(isSupportedExternalUrl("ftp://example.com"))
        assertFalse(isSupportedExternalUrl("javascript:alert(1)"))
    }

    @Test
    fun removeImportedServer_removes_target_and_drops_empty_groups() {
        val groupA =
            ImportedClientGroup(
                clientId = "a",
                clientName = "A",
                clientIconId = "a",
                servers =
                    listOf(
                        ImportedServerCandidate(
                            sourceServerId = "s1",
                            config = serverConfig("s1"),
                        ),
                    ),
            )
        val groupB =
            ImportedClientGroup(
                clientId = "b",
                clientName = "B",
                clientIconId = "b",
                servers =
                    listOf(
                        ImportedServerCandidate(
                            sourceServerId = "x1",
                            config = serverConfig("x1"),
                        ),
                        ImportedServerCandidate(
                            sourceServerId = "x2",
                            config = serverConfig("x2"),
                        ),
                    ),
            )

        val updated = removeImportedServer(listOf(groupA, groupB), clientId = "b", sourceServerId = "x1")
        assertEquals(2, updated.size)
        assertEquals(listOf("x2"), updated.first { it.clientId == "b" }.servers.map { it.sourceServerId })

        val removedWholeGroup = removeImportedServer(listOf(groupA), clientId = "a", sourceServerId = "s1")
        assertTrue(removedWholeGroup.isEmpty())
    }

    @Test
    fun importedCandidateToDraft_and_transport_toDraft_map_all_transport_types() {
        val stdioDraft =
            importedCandidateToDraft(
                ImportedServerCandidate(
                    sourceServerId = "stdio",
                    config =
                        UiMcpServerConfig(
                            id = "stdio",
                            name = "Stdio",
                            transport = UiStdioTransport(command = "cmd", args = listOf("--x")),
                        ),
                ),
            )
        assertTrue(stdioDraft.transport is UiStdioDraft)

        val httpDraft =
            importedCandidateToDraft(
                ImportedServerCandidate(
                    sourceServerId = "http",
                    config =
                        UiMcpServerConfig(
                            id = "http",
                            name = "Http",
                            transport = UiHttpTransport(url = "http://localhost"),
                        ),
                ),
            )
        assertTrue(httpDraft.transport is UiHttpDraft)

        val streamableDraft =
            importedCandidateToDraft(
                ImportedServerCandidate(
                    sourceServerId = "stream",
                    config =
                        UiMcpServerConfig(
                            id = "stream",
                            name = "Stream",
                            transport = UiStreamableHttpTransport(url = "http://localhost/mcp"),
                        ),
                ),
            )
        assertTrue(streamableDraft.transport is UiStreamableHttpDraft)

        val wsDraft =
            importedCandidateToDraft(
                ImportedServerCandidate(
                    sourceServerId = "ws",
                    config =
                        UiMcpServerConfig(
                            id = "ws",
                            name = "Ws",
                            transport = UiWebSocketTransport(url = "wss://localhost/mcp"),
                        ),
                ),
            )
        assertTrue(wsDraft.transport is UiWebSocketDraft)
    }

    private fun serverConfig(id: String): UiMcpServerConfig =
        UiMcpServerConfig(
            id = id,
            name = id,
            transport = UiStdioTransport(command = "cmd"),
        )
}
