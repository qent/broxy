package io.qent.bro.ui.viewmodels

import io.qent.bro.core.mcp.McpServerConnection
import io.qent.bro.core.mcp.ServerCapabilities
import io.qent.bro.core.mcp.ServerStatus
import io.qent.bro.core.mcp.ToolDescriptor
import io.qent.bro.ui.adapter.models.UiMcpServerConfig as McpServerConfig
import io.qent.bro.ui.adapter.models.UiTransportConfig as TransportConfig
import io.qent.bro.ui.adapter.viewmodels.ServersViewModel
import io.qent.bro.ui.adapter.models.UiHttpTransport as HttpTransport
import io.qent.bro.ui.adapter.models.UiWebSocketTransport as WebSocketTransport
import io.qent.bro.ui.adapter.models.UiStdioTransport as StdioTransport
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServersViewModelTest {

    @Test
    fun testConnection_success_updates_state_and_tools() {
        runBlocking {
            val conn: McpServerConnection = mock()
            whenever(conn.connect()).thenReturn(Result.success(Unit))
            whenever(conn.status).thenReturn(ServerStatus.Running)
            whenever(conn.getCapabilities(true)).thenReturn(
                Result.success(ServerCapabilities(tools = listOf(ToolDescriptor("t1"), ToolDescriptor("t2"))))
            )

            val vm = ServersViewModel { _, _ -> conn }
            val cfg = McpServerConfig("s1", "Server 1", HttpTransport("http://localhost"))

            val res = vm.testConnection(cfg)
            assertTrue(res.isSuccess)

            val ui = vm.uiStates[cfg.id]?.value
            assertNotNull(ui)
            assertEquals(ServerStatus.Running, ui.status)
            assertEquals(2, ui.toolsCount)
            assertNotNull(ui.lastCapabilities)
        }
    }

    @Test
    fun connect_then_disconnect_updates_status() {
        runBlocking {
            val conn: McpServerConnection = mock()
            whenever(conn.connect()).thenReturn(Result.success(Unit))
            whenever(conn.status).thenReturn(ServerStatus.Running)

            val vm = ServersViewModel { _, _ -> conn }
            val cfg = McpServerConfig("s2", "Server 2", WebSocketTransport("ws://localhost"))

            val r = vm.connect(cfg)
            assertTrue(r.isSuccess)
            val afterConnect = vm.uiStates[cfg.id]?.value
            assertEquals(ServerStatus.Running, afterConnect?.status)

            vm.disconnect(cfg.id)
            val afterDisconnect = vm.uiStates[cfg.id]?.value
            assertEquals(ServerStatus.Stopped, afterDisconnect?.status)
        }
    }

    @Test
    fun removeServer_removes_from_list_and_states() {
        runBlocking {
            val conn: McpServerConnection = mock()
            val vm = ServersViewModel { _, _ -> conn }
            val cfg = McpServerConfig("s3", "Server 3", StdioTransport(command = "echo"))

            val list = androidx.compose.runtime.mutableStateListOf(cfg)
            // Ensure state exists
            whenever(conn.connect()).thenReturn(Result.success(Unit))
            whenever(conn.status).thenReturn(ServerStatus.Running)
            // disconnect is suspend Unit; return Unit explicitly
            whenever(conn.disconnect()).thenAnswer { }
            vm.connect(cfg)
            assertTrue(list.any { it.id == "s3" })
            assertNotNull(vm.uiStates[cfg.id])

            vm.removeServer(list, cfg.id)
            assertTrue(list.none { it.id == "s3" })
            // UI state for server should be cleaned
            assertTrue(vm.uiStates[cfg.id] == null)
        }
    }

    @Test
    fun applyEnabledChange_updates_config_in_list() {
        val vm = ServersViewModel()
        val cfg = McpServerConfig("s4", "Server 4", HttpTransport("http://u"), enabled = true)
        val list = androidx.compose.runtime.mutableStateListOf(cfg)

        vm.applyEnabledChange(list, "s4", false)
        assertEquals(false, list[0].enabled)
    }
}
