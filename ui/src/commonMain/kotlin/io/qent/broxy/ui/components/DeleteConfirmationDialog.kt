@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.qent.broxy.ui.strings.LocalStrings

private const val DANGER_RED_HEX = 0xFFDC2626

@Composable
@Suppress("LongParameterList")
fun DeleteConfirmationDialog(
    title: String,
    prompt: String,
    description: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    dismissLabel: String? = null,
) {
    val strings = LocalStrings.current
    val resolvedConfirmLabel = confirmLabel ?: strings.delete
    val resolvedDismissLabel = dismissLabel ?: strings.cancel
    val dangerColor = Color(DANGER_RED_HEX)

    CalloutDialog(
        title = title,
        prompt = prompt,
        description = description,
        icon = Icons.Outlined.Delete,
        accentColor = dangerColor,
        onDismiss = onDismiss,
        dismissButton = { AppSecondaryButton(onClick = onDismiss) { Text(resolvedDismissLabel) } },
        confirmButton = { AppDangerButton(onClick = onConfirm) { Text(resolvedConfirmLabel) } },
    )
}
