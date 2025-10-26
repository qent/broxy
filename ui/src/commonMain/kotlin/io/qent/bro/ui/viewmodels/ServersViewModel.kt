package io.qent.bro.ui.viewmodels

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.qent.bro.core.mcp.DefaultMcpServerConnection
import io.qent.bro.core.mcp.McpServerConnection
import io.qent.bro.core.mcp.ServerCapabilities
import io.qent.bro.core.mcp.ServerStatus
import io.qent.bro.core.models.McpServerConfig
import io.qent.bro.core.models.TransportConfig
import io.qent.bro.core.utils.Logger

data class ServerUiState(
    val status: ServerStatus = ServerStatus.Stopped,
    val toolsCount: Int? = null,
    val testing: Boolean = false,
    val lastError: String? = null,
    val lastCapabilities: ServerCapabilities? = null,
    val showDetails: Boolean = false,
    val showEdit: Boolean = false
)

fun interface ConnectionProvider {
    fun create(config: McpServerConfig, logger: Logger): McpServerConnection
}

class ServersViewModel(
    private val provider: ConnectionProvider = ConnectionProvider { cfg, logger ->
        DefaultMcpServerConnection(cfg, logger = logger)
    }
) {
    private val connections = mutableMapOf<String, McpServerConnection>()
    private val logsMap = mutableMapOf<String, SnapshotStateList<String>>()
    val uiStates = mutableStateMapOf<String, MutableState<ServerUiState>>()

    fun getLogs(serverId: String): SnapshotStateList<String> = logsMap.getOrPut(serverId) { mutableStateListOf() }

    private fun state(serverId: String) = uiStates.getOrPut(serverId) { mutableStateOf(ServerUiState()) }

    private fun buildLogger(serverId: String): Logger = object : Logger {
        override fun debug(message: String) { logsMap.getOrPut(serverId) { mutableStateListOf() }.add("[DEBUG] $message") }
        override fun info(message: String) { logsMap.getOrPut(serverId) { mutableStateListOf() }.add("[INFO] $message") }
        override fun warn(message: String, throwable: Throwable?) {
            logsMap.getOrPut(serverId) { mutableStateListOf() }.add("[WARN] $message" + (throwable?.message?.let { ": $it" } ?: ""))
        }
        override fun error(message: String, throwable: Throwable?) {
            logsMap.getOrPut(serverId) { mutableStateListOf() }.add("[ERROR] $message" + (throwable?.message?.let { ": $it" } ?: ""))
        }
    }

    private fun ensureConnection(config: McpServerConfig): McpServerConnection =
        connections.getOrPut(config.id) { provider.create(config, buildLogger(config.id)) }

    fun openDetails(serverId: String) { state(serverId).value = state(serverId).value.copy(showDetails = true) }
    fun closeDetails(serverId: String) { state(serverId).value = state(serverId).value.copy(showDetails = false) }

    fun openEdit(serverId: String) { state(serverId).value = state(serverId).value.copy(showEdit = true) }
    fun closeEdit(serverId: String) { state(serverId).value = state(serverId).value.copy(showEdit = false) }

    suspend fun connect(config: McpServerConfig): Result<Unit> {
        val st = state(config.id)
        st.value = st.value.copy(testing = true, lastError = null)
        val conn = ensureConnection(config)
        val r = conn.connect()
        st.value = st.value.copy(testing = false, status = conn.status, lastError = r.exceptionOrNull()?.message)
        return r
    }

    suspend fun disconnect(serverId: String) {
        connections[serverId]?.disconnect()
        val st = state(serverId)
        st.value = st.value.copy(status = ServerStatus.Stopped)
    }

    suspend fun testConnection(config: McpServerConfig): Result<ServerCapabilities> {
        val st = state(config.id)
        st.value = st.value.copy(testing = true, lastError = null)
        val conn = ensureConnection(config)
        val r = conn.connect()
        if (r.isFailure) {
            val ex = r.exceptionOrNull()
            st.value = st.value.copy(testing = false, status = conn.status, lastError = ex?.message)
            return Result.failure(ex!!)
        }
        val caps = conn.getCapabilities(forceRefresh = true)
        if (caps.isSuccess) {
            val tools = caps.getOrNull()?.tools?.size
            st.value = st.value.copy(testing = false, status = conn.status, toolsCount = tools, lastCapabilities = caps.getOrNull())
            return Result.success(caps.getOrThrow())
        } else {
            val ex = caps.exceptionOrNull()
            st.value = st.value.copy(testing = false, status = conn.status, lastError = ex?.message)
            return Result.failure(ex!!)
        }
    }

    fun applyEnabledChange(list: SnapshotStateList<McpServerConfig>, id: String, enabled: Boolean) {
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = list[idx].copy(enabled = enabled)
    }

    fun replaceConfig(list: SnapshotStateList<McpServerConfig>, newConfig: McpServerConfig) {
        val idx = list.indexOfFirst { it.id == newConfig.id }
        if (idx >= 0) list[idx] = newConfig else list.add(newConfig)
    }

    fun updateConnectionConfig(newConfig: McpServerConfig) {
        connections[newConfig.id] = provider.create(newConfig, buildLogger(newConfig.id))
        val st = state(newConfig.id)
        st.value = st.value.copy(status = ServerStatus.Stopped, toolsCount = null, lastError = null, lastCapabilities = null)
    }

    suspend fun removeServer(list: SnapshotStateList<McpServerConfig>, id: String) {
        disconnect(id)
        list.removeAll { it.id == id }
        connections.remove(id)
        uiStates.remove(id)
        logsMap.remove(id)
    }
}
