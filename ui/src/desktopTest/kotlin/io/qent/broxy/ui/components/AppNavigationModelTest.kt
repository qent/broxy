package io.qent.broxy.ui.components

import io.qent.broxy.ui.strings.EnglishStrings
import io.qent.broxy.ui.viewmodels.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppNavigationModelTest {
    @Test
    fun `bottom navigation keeps clients above agent settings and icon-only`() {
        val items = navigationRailItems(EnglishStrings)

        assertEquals(
            listOf(Screen.Clients, Screen.AgentSettings, Screen.Settings),
            items.bottomItems.map { it.screen },
        )
        assertTrue(items.bottomItems.all { !it.showLabel })
    }

    @Test
    fun `top navigation keeps labeled primary sections`() {
        val items = navigationRailItems(EnglishStrings)

        assertEquals(
            listOf(Screen.Catalog, Screen.Servers, Screen.Presets, Screen.Agents, Screen.Runs),
            items.topItems.map { it.screen },
        )
        assertTrue(items.topItems.all { it.showLabel })
    }
}
