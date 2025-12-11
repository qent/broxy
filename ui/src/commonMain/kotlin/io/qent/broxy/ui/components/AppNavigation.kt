package io.qent.broxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qent.broxy.ui.theme.AppTheme
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
    val spacing = AppTheme.spacing
    val colors = MaterialTheme.colorScheme
    
    // Custom Sidebar Implementation matching design
    Column(
        modifier = modifier
            .width(120.dp) // Fixed width from design
            .background(AppTheme.extendedColors.sidebarBackground) // Sidebar background
            .padding(vertical = 20.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        // Top Section: Logo (Placeholder) + Nav
        Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            modifier = Modifier.width(120.dp)
        ) {
            // Logo Placeholder
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        color = colors.surfaceVariant, 
                        shape = AppTheme.shapes.card
                    )
            )
            
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
            
            // Navigation Items
            Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 10.dp).width(120.dp)
            ) {
                navItems.forEach { item ->
                    val isSelected = selected == item.screen
                    val backgroundColor = if (isSelected) colors.primary else androidx.compose.ui.graphics.Color.Transparent
                    val contentColor = if (isSelected) colors.onPrimary else colors.secondary
                    
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppTheme.shapes.button)
                            .background(backgroundColor)
                            .clickable { onSelect(item.screen) }
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                    ) {
                        // Icon wrapper to ensure size consistency
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.material3.LocalContentColor provides contentColor
                        ) {
                            item.icon()
                        }
                        
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 11.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            ),
                            color = contentColor,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
