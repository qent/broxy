package io.qent.broxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qent.broxy.ui.adapter.models.UiProxyStatus
import io.qent.broxy.ui.icons.rememberNavIconPainter
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.Screen

data class NavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun AppNavigationRail(
    selected: Screen,
    onSelect: (Screen) -> Unit,
    proxyStatus: UiProxyStatus?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val strings = LocalStrings.current
    val navIconSize = 22.dp
    val clientsNavPainter = rememberNavIconPainter("clients", navIconSize)
    val navItems =
        listOf(
            NavItem(Screen.Servers, strings.navMcp, Icons.Outlined.Storage),
            NavItem(Screen.Presets, strings.navPresets, Icons.Outlined.Tune),
            NavItem(Screen.Clients, strings.navClients, Icons.Outlined.Cable),
        )
    val settingsItem = NavItem(Screen.Settings, strings.navSettings, Icons.Outlined.Settings)

    Column(
        modifier =
            modifier
                .width(AppTheme.layout.navigationRailWidth)
                .background(AppTheme.extendedColors.sidebarBackground)
                .padding(vertical = AppTheme.spacing.md),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.sm),
        ) {
            // Navigation Items
            navItems.forEach { item ->
                val isSelected = selected == item.screen
                val backgroundColor = if (isSelected) colors.primary else androidx.compose.ui.graphics.Color.Transparent
                val contentColor = if (isSelected) colors.onPrimary else colors.secondary

                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(AppTheme.shapes.button)
                            .background(backgroundColor)
                            .clickable { onSelect(item.screen) }
                            .padding(vertical = 7.dp, horizontal = 3.dp),
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalContentColor provides contentColor,
                    ) {
                        val iconPainter = if (item.screen == Screen.Clients) clientsNavPainter else null
                        if (iconPainter != null) {
                            Icon(
                                painter = iconPainter,
                                contentDescription = item.label,
                                modifier = Modifier.size(navIconSize),
                            )
                        } else {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                modifier = Modifier.size(navIconSize),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = item.label,
                        style =
                            MaterialTheme.typography.labelMedium.copy(
                                fontSize = 10.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            ),
                        color = contentColor,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.sm),
        ) {
            val isSettingsSelected = selected == settingsItem.screen
            val settingsBackground = if (isSettingsSelected) colors.primary else androidx.compose.ui.graphics.Color.Transparent
            val settingsContent = if (isSettingsSelected) colors.onPrimary else colors.secondary

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(AppTheme.shapes.button)
                        .background(settingsBackground)
                        .clickable { onSelect(settingsItem.screen) }
                        .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides settingsContent,
                ) {
                    Icon(
                        imageVector = settingsItem.icon,
                        contentDescription = settingsItem.label,
                        modifier = Modifier.size(navIconSize),
                    )
                }
            }

            ProxyStatusIndicator(status = proxyStatus)
        }
    }
}
