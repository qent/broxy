package io.qent.broxy.core.mcp

sealed interface ServerStatus {
    data object Starting : ServerStatus
    data object Running : ServerStatus
    data object Stopping : ServerStatus
    data object Stopped : ServerStatus
    data class Error(val message: String? = null) : ServerStatus
}
