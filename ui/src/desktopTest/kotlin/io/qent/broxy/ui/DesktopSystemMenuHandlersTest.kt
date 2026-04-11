package io.qent.broxy.ui

import java.awt.Desktop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopSystemMenuHandlersTest {
    @Test
    fun `install handlers wires about and preferences and cleans up`() {
        val menuBridge =
            FakeMacOsSystemMenuBridge(
                supportedActions = setOf(Desktop.Action.APP_ABOUT, Desktop.Action.APP_PREFERENCES),
            )
        var openSettingsInvocations = 0

        val dispose =
            installMacOsSystemMenuHandlers(menuBridge) {
                openSettingsInvocations += 1
            }

        assertEquals(
            listOf(Desktop.Action.APP_ABOUT, Desktop.Action.APP_PREFERENCES),
            menuBridge.checkedActions,
        )
        assertEquals(1, menuBridge.setDefaultAboutHandlerInvocations)
        menuBridge.installedPreferencesHandler?.invoke()
        assertEquals(1, openSettingsInvocations)

        dispose()

        assertEquals(2, menuBridge.setDefaultAboutHandlerInvocations)
        assertNull(menuBridge.installedPreferencesHandler)
    }

    @Test
    fun `install handlers skips unsupported actions`() {
        val menuBridge = FakeMacOsSystemMenuBridge(supportedActions = emptySet())

        val dispose = installMacOsSystemMenuHandlers(menuBridge) {}
        dispose()

        assertEquals(
            listOf(Desktop.Action.APP_ABOUT, Desktop.Action.APP_PREFERENCES),
            menuBridge.checkedActions,
        )
        assertEquals(0, menuBridge.setDefaultAboutHandlerInvocations)
        assertNull(menuBridge.installedPreferencesHandler)
    }

    private class FakeMacOsSystemMenuBridge(
        private val supportedActions: Set<Desktop.Action>,
    ) : MacOsSystemMenuBridge {
        val checkedActions = mutableListOf<Desktop.Action>()
        var setDefaultAboutHandlerInvocations = 0
        var installedPreferencesHandler: (() -> Unit)? = null

        override fun isSupported(action: Desktop.Action): Boolean {
            checkedActions += action
            return action in supportedActions
        }

        override fun setDefaultAboutHandler() {
            setDefaultAboutHandlerInvocations += 1
        }

        override fun setPreferencesHandler(handler: (() -> Unit)?) {
            installedPreferencesHandler = handler
        }
    }
}
