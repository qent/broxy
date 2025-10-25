package io.qent.broxy.ui.components

import AppSecondaryButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
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
    val isSuccess = popup.status == UiAuthorizationPopupStatus.Success
    val openedInBrowser = remember(popup.serverId, popup.authorizationUrl) { mutableStateOf(false) }
    val onDismissLatest = rememberUpdatedState(onDismiss)
    val accentColor = Color(0xFF16A34A)
    if (isSuccess) {
        LaunchedEffect(popup.serverId, popup.status) {
            delay(1_200)
            onDismissLatest.value()
        }
    }

    CalloutDialog(
        title = strings.authorizationDialogTitle,
        prompt = strings.authorizationPopupTitle(popup.serverName),
        description = strings.authorizationPopupSubtitle,
        icon = Icons.Outlined.VerifiedUser,
        accentColor = accentColor,
        onDismiss = onCancel,
        dismissOnClickOutside = false,
        dismissOnBackPress = false,
        confirmButton = { AppSecondaryButton(onClick = onCancel) { Text(strings.cancel) } },
    )
    LaunchedEffect(popup.serverId, popup.authorizationUrl) {
        if (!openedInBrowser.value) {
            openedInBrowser.value = true
            onOpenInBrowser()
        }
    }
}
