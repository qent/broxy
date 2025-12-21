package io.qent.broxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
    val navItems =
        listOf(
            NavItem(Screen.Servers, strings.navMcp, Icons.Outlined.Storage),
            NavItem(Screen.Presets, strings.navPresets, Icons.Outlined.Tune),
            NavItem(Screen.Settings, strings.navSettings, Icons.Outlined.Settings),
        )

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
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            modifier = Modifier.size(22.dp),
                        )
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

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            ProxyStatusIndicator(status = proxyStatus)
        }
    }
}
