package io.qent.broxy.core.proxy

import io.qent.broxy.core.mcp.ServerCapabilities
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.models.Preset
import io.qent.broxy.core.models.ToolReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilitiesStateStoreTest {
    @Test
    fun applyServerRefresh_updates_only_target_server() =
        runTest {
            val store = CapabilitiesStateStore(DefaultPresetEngine(DefaultToolFilter()))
            val preset =
                Preset(
                    id = "preset",
                    name = "Preset",
                    tools =
                        listOf(
                            ToolReference(serverId = "alpha", toolName = "alpha_tool", enabled = true),
                            ToolReference(serverId = "beta", toolName = "beta_tool", enabled = true),
                        ),
                )
            val initialId = store.nextRefreshSequence()
            val initialCaps =
                mapOf(
                    "alpha" to
                        ServerCapabilities(
                            tools = listOf(ToolDescriptor(name = "alpha_tool", description = "old-alpha")),
                        ),
                    "beta" to
                        ServerCapabilities(
                            tools = listOf(ToolDescriptor(name = "beta_tool", description = "old-beta")),
                        ),
                )
            assertTrue(store.applyFullRefresh(initialId, initialCaps, preset))

            val refreshId = store.nextRefreshSequence()
            val updatedCaps =
                ServerCapabilities(
                    tools = listOf(ToolDescriptor(name = "beta_tool", description = "new-beta")),
                )
            assertTrue(store.applyServerRefresh(refreshId, "beta", updatedCaps, preset))

            val tools = store.currentCapabilities().tools
            val alphaTool = tools.first { it.name == "alpha_alpha_tool" }
            val betaTool = tools.first { it.name == "beta_beta_tool" }
            assertEquals("old-alpha", alphaTool.description)
            assertEquals("new-beta", betaTool.description)
        }

    @Test
    fun applyFullRefresh_preserves_newer_server_refresh() =
        runTest {
            val store = CapabilitiesStateStore(DefaultPresetEngine(DefaultToolFilter()))
            val preset =
                Preset(
                    id = "preset",
                    name = "Preset",
                    tools =
                        listOf(
                            ToolReference(serverId = "alpha", toolName = "alpha_tool", enabled = true),
                            ToolReference(serverId = "beta", toolName = "beta_tool", enabled = true),
                        ),
                )
            val initialCaps =
                mapOf(
                    "alpha" to
                        ServerCapabilities(
                            tools = listOf(ToolDescriptor(name = "alpha_tool", description = "base-alpha")),
                        ),
                    "beta" to
                        ServerCapabilities(
                            tools = listOf(ToolDescriptor(name = "beta_tool", description = "old-beta")),
                        ),
                )
            val initialId = store.nextRefreshSequence()
            assertTrue(store.applyFullRefresh(initialId, initialCaps, preset))

            val fullRefreshId = store.nextRefreshSequence()
            val serverRefreshId = store.nextRefreshSequence()
            val newerCaps =
                ServerCapabilities(
                    tools = listOf(ToolDescriptor(name = "beta_tool", description = "new-beta")),
                )
            assertTrue(store.applyServerRefresh(serverRefreshId, "beta", newerCaps, preset))

            assertTrue(store.applyFullRefresh(fullRefreshId, initialCaps, preset))

            val betaTool = store.currentCapabilities().tools.first { it.name == "beta_beta_tool" }
            assertEquals("new-beta", betaTool.description)
        }
}
