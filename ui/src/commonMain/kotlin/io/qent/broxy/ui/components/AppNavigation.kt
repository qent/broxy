package io.qent.broxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.Screen

data class NavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
)

private val navItems = listOf(
    NavItem(Screen.Servers, "MCP", Icons.Outlined.Storage),
    NavItem(Screen.Presets, "Presets", Icons.Outlined.Tune),
    NavItem(Screen.Logs, "Logs", Icons.Outlined.List),
    NavItem(Screen.Settings, "Settings", Icons.Outlined.Settings),
)

@Composable
fun AppNavigationRail(
    selected: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .width(AppTheme.layout.navigationRailWidth)
            .background(AppTheme.extendedColors.sidebarBackground)
            .padding(vertical = AppTheme.spacing.lg),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.spacing.sm)
        ) {
            // Navigation Items
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
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalContentColor provides contentColor
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 10.sp,
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
