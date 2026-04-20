package io.qent.broxy.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.qent.broxy.ui.adapter.models.UiAuthorizationPopup
import io.qent.broxy.ui.adapter.models.UiAuthorizationPopupStatus
import io.qent.broxy.ui.strings.LocalStrings
import kotlinx.coroutines.delay

@Composable
actual fun AuthorizationPopupDialog(
    popup: UiAuthorizationPopup,
    onCancel: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val status = popup.status
    val isSuccess = popup.status == UiAuthorizationPopupStatus.Success
    val isAwaitingBrowserPermission = status == UiAuthorizationPopupStatus.AwaitingBrowserPermission
    val onDismissLatest = rememberUpdatedState(onDismiss)
    val accentColor =
        if (isAwaitingBrowserPermission) {
            Color(0xFF2563EB)
        } else {
            Color(0xFF16A34A)
        }
    val icon: ImageVector =
        if (isAwaitingBrowserPermission) {
            Icons.Outlined.OpenInBrowser
        } else {
            Icons.Outlined.VerifiedUser
        }
    val description =
        if (isAwaitingBrowserPermission) {
            strings.authorizationPopupPermissionSubtitle
        } else {
            strings.authorizationPopupSubtitle
        }
    if (isSuccess) {
        LaunchedEffect(popup.serverId, popup.status) {
            delay(1_200)
            onDismissLatest.value()
        }
    }

    CalloutDialog(
        title = strings.authorizationDialogTitle,
        prompt = strings.authorizationPopupTitle(popup.serverName),
        description = description,
        icon = icon,
        accentColor = accentColor,
        onDismiss = onCancel,
        dismissOnClickOutside = false,
        dismissOnBackPress = false,
        confirmButton = {
            if (isAwaitingBrowserPermission) {
                AppPrimaryButton(onClick = onOpenInBrowser) { Text(strings.continueInBrowser) }
            } else {
                AppSecondaryButton(onClick = onCancel) { Text(strings.cancel) }
            }
        },
        dismissButton =
            if (isAwaitingBrowserPermission) {
                { AppSecondaryButton(onClick = onCancel) { Text(strings.cancel) } }
            } else {
                null
            },
    )
}
