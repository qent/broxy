@file:Suppress("FunctionNaming", "MatchingDeclarationName")

package io.qent.broxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
@Suppress("LongMethod")
fun AppNavigationRail(
    selected: Screen,
    onSelect: (Screen) -> Unit,
    proxyStatus: UiProxyStatus?,
    onToggleProxy: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val strings = LocalStrings.current
    val navIconSize = 22.dp
    val connectionNavPainter = rememberNavIconPainter("plug_connect", navIconSize)
    val navItems =
        listOf(
            NavItem(Screen.Servers, strings.navMcp, Icons.Outlined.Storage),
            NavItem(Screen.Presets, strings.navPresets, Icons.Outlined.Tune),
            NavItem(Screen.Clients, strings.navConnection, Icons.Outlined.Cable),
        )
    val settingsItem = NavItem(Screen.Settings, strings.navSettings, Icons.Outlined.Settings)

    Column(
        modifier =
            modifier
                .width(AppTheme.layout.navigationRailWidth)
                .background(AppTheme.extendedColors.sidebarBackground)
                .padding(vertical = AppTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.sm),
        ) {
            // Navigation Items
            navItems.forEach { item ->
                val isSelected = selected == item.screen
                val backgroundColor = if (isSelected) colors.primary else Color.Transparent
                val contentColor = if (isSelected) colors.onPrimary else colors.secondary

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(AppTheme.shapes.button)
                            .background(backgroundColor)
                            .clickable { onSelect(item.screen) }
                            .padding(vertical = 7.dp, horizontal = 3.dp),
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides contentColor,
                    ) {
                        val iconPainter = if (item.screen == Screen.Clients) connectionNavPainter else null
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
                                fontWeight = FontWeight.Medium,
                            ),
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
            val settingsBackground = if (isSettingsSelected) colors.primary else Color.Transparent
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
                CompositionLocalProvider(
                    LocalContentColor provides settingsContent,
                ) {
                    Icon(
                        imageVector = settingsItem.icon,
                        contentDescription = settingsItem.label,
                        modifier = Modifier.size(navIconSize),
                    )
                }
            }

            ProxyStatusIndicator(status = proxyStatus, onClick = onToggleProxy)
        }
    }
}
