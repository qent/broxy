package io.qent.broxy.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qent.broxy.ui.viewmodels.Screen

data class NavItem(
    val screen: Screen,
    val label: String,
    val icon: @Composable () -> Unit
)

private val navItems = listOf(
    NavItem(Screen.Servers, "MCP") { Icon(Icons.Outlined.Storage, contentDescription = "MCP servers") },
    NavItem(Screen.Presets, "Presets") { Icon(Icons.Outlined.Tune, contentDescription = "Presets") },
    NavItem(Screen.Proxy, "Broxy") { Icon(Icons.Outlined.Dns, contentDescription = "Broxy") },
    NavItem(Screen.Logs, "Logs") { Icon(Icons.Outlined.List, contentDescription = "Logs") },
    NavItem(Screen.Settings, "Settings") { Icon(Icons.Outlined.Settings, contentDescription = "Settings") },
)

@Composable
fun AppNavigationRail(
    selected: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(modifier = modifier) {
        navItems.forEachIndexed { index, item ->
            NavigationRailItem(
                selected = selected == item.screen,
                onClick = { onSelect(item.screen) },
                icon = item.icon,
                label = { Text(item.label, fontSize = 12.sp) },
                modifier = if (index == 0) Modifier.padding(top = 8.dp) else Modifier
            )
        }
    }
}
