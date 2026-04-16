package io.qent.broxy.ui

import io.qent.broxy.ui.adapter.catalog.CatalogConnectionType
import io.qent.broxy.ui.adapter.catalog.CatalogInstallSession
import io.qent.broxy.ui.adapter.catalog.CatalogServerDetail
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.viewmodels.PresetEditorState
import io.qent.broxy.ui.viewmodels.Screen
import io.qent.broxy.ui.viewmodels.ServerEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopSystemSettingsMenuTest {
    @Test
    fun `open settings from system menu switches screen to settings`() {
        val appState = AppState(initialScreen = Screen.Catalog)

        openSettingsFromSystemMenu(appState) {}

        assertEquals(Screen.Settings, appState.currentScreen.value)
    }

    @Test
    fun `open settings from system menu resets sub-navigation state`() {
        val appState = AppState(initialScreen = Screen.Servers)
        appState.serverEditor.value = ServerEditorState.Create
        appState.serverDetailsId.value = "server-id"
        appState.presetEditor.value = PresetEditorState.Create
        appState.catalogInstall.value = createCatalogInstallSession()

        openSettingsFromSystemMenu(appState) {}

        assertNull(appState.serverEditor.value)
        assertNull(appState.serverDetailsId.value)
        assertNull(appState.presetEditor.value)
        assertNull(appState.catalogInstall.value)
    }

    @Test
    fun `open settings from system menu requests show and focus`() {
        val appState = AppState(initialScreen = Screen.Settings)
        var showAndFocusInvocations = 0

        openSettingsFromSystemMenu(appState) {
            showAndFocusInvocations += 1
        }

        assertEquals(1, showAndFocusInvocations)
    }

    private fun createCatalogInstallSession(): CatalogInstallSession =
        CatalogInstallSession(
            serverId = "io.qent.broxy/test",
            defaultName = "Test",
            transportLabel = "HTTP",
            connectionType = CatalogConnectionType.StreamableHttp,
            detail =
                CatalogServerDetail(
                    name = "io.qent.broxy/test",
                    description = "Test install session",
                    version = "latest",
                ),
            installSteps = emptyList(),
            fields = emptyList(),
        )
}
