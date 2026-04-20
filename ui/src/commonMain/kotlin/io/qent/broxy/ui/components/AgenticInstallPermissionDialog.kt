@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiAgenticInstallPermissionPopup
import io.qent.broxy.ui.adapter.models.UiServerIcon
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

@Composable
@Suppress("LongMethod")
fun AgenticInstallPermissionDialog(
    popup: UiAgenticInstallPermissionPopup,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    val strings = LocalStrings.current
    val serverIcon =
        remember(popup.iconUrl) {
            val iconUrl = popup.iconUrl?.trim().takeUnless { it.isNullOrEmpty() }
            if (iconUrl == null) {
                UiServerIcon.Default
            } else {
                UiServerIcon.Remote(iconUrl)
            }
        }
    val serverDescription = popup.serverDescription.trim().ifEmpty { strings.noDescriptionProvided }

    AppDialog(
        title = strings.authorizationDialogTitle,
        onDismissRequest = onDeny,
        minWidth = AppTheme.layout.dialogMinWidth,
        maxWidth = 520.dp,
        dismissOnClickOutside = false,
        dismissOnBackPress = false,
        maxContentHeight = null,
        enableScroll = false,
        confirmButton = {
            AppPrimaryButton(onClick = onAllow) { Text(strings.allow) }
        },
        dismissButton = {
            AppSecondaryButton(onClick = onDeny) { Text(strings.deny) }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = AppTheme.spacing.xs, bottom = AppTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                ServerIconBadge(
                    icon = serverIcon,
                    modifier = Modifier.fillMaxSize(),
                    padding = AppTheme.spacing.xs,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
            ) {
                Text(
                    text = strings.agenticInstallPermissionTitle(popup.serverName),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = strings.agenticInstallPermissionSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = serverDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
