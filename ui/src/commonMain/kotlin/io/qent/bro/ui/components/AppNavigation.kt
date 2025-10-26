package io.qent.bro.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.qent.bro.ui.viewmodels.Screen

data class NavItem(
    val screen: Screen,
    val label: String,
    val icon: @Composable () -> Unit
)

private val navItems = listOf(
    NavItem(Screen.Servers, "Servers") { Icon(Icons.Outlined.Storage, contentDescription = "Servers") },
    NavItem(Screen.Presets, "Presets") { Icon(Icons.Outlined.Tune, contentDescription = "Presets") },
    NavItem(Screen.Proxy, "Proxy") { Icon(Icons.Outlined.Dns, contentDescription = "Proxy") },
    NavItem(Screen.Settings, "Settings") { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
)

@Composable
fun AppNavigationRail(
    selected: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(modifier = modifier) {
        navItems.forEach { item ->
            NavigationRailItem(
                selected = selected == item.screen,
                onClick = { onSelect(item.screen) },
                icon = item.icon,
                label = { Text(item.label) }
            )
        }
    }
}
