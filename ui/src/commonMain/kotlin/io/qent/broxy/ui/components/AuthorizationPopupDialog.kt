package io.qent.broxy.ui.components

import androidx.compose.runtime.Composable
import io.qent.broxy.ui.adapter.models.UiAuthorizationPopup

@Composable
expect fun AuthorizationPopupDialog(
    popup: UiAuthorizationPopup,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
)
