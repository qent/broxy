@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.liquidglass.GlassBackgroundScenario
import io.qent.broxy.ui.liquidglass.GlassCard
import io.qent.broxy.ui.liquidglass.GlassPanel
import io.qent.broxy.ui.liquidglass.GlassSurfaceVariant
import io.qent.broxy.ui.liquidglass.components.GlassButton
import io.qent.broxy.ui.liquidglass.components.GlassIconButton
import io.qent.broxy.ui.liquidglass.components.GlassSwitch
import io.qent.broxy.ui.liquidglass.components.GlassTextField
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun GlassShowcaseScreen(
    scenario: GlassBackgroundScenario,
    modifier: Modifier = Modifier,
) {
    val text = remember { mutableStateOf("") }
    val toggle = remember { mutableStateOf(true) }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        variant = GlassSurfaceVariant.Regular,
        padding = PaddingValues(AppTheme.spacing.md),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            Text(
                text = "Liquid Glass Showcase",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Background: ${scenario.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GlassPanel(
                variant = GlassSurfaceVariant.Clear,
                padding = PaddingValues(AppTheme.spacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                ) {
                    GlassButton(text = "Action", onClick = {}, modifier = Modifier.size(width = 96.dp, height = 32.dp))
                    GlassIconButton(
                        icon = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        onClick = {},
                    )
                    GlassSwitch(
                        checked = toggle.value,
                        onCheckedChange = { toggle.value = it },
                    )
                }
            }
            GlassTextField(
                value = text.value,
                onValueChange = { text.value = it },
                placeholder = "Search on glass",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
