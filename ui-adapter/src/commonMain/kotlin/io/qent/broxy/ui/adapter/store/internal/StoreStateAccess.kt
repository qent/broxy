package io.qent.broxy.ui.adapter.store.internal

import io.qent.broxy.ui.adapter.models.UiMcpServersConfig

internal class StoreStateAccess(
    private val snapshotProvider: () -> StoreSnapshot,
    private val snapshotUpdater: (StoreSnapshot.() -> StoreSnapshot) -> Unit,
    private val snapshotConfigProvider: () -> UiMcpServersConfig,
    private val errorHandler: (String) -> Unit
) {
    val snapshot: StoreSnapshot
        get() = snapshotProvider()

    fun updateSnapshot(block: StoreSnapshot.() -> StoreSnapshot) {
        snapshotUpdater(block)
    }

    fun snapshotConfig(): UiMcpServersConfig = snapshotConfigProvider()

    fun setError(message: String) {
        errorHandler(message)
    }
}
