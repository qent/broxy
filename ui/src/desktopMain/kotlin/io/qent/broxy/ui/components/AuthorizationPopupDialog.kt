package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.qent.broxy.ui.adapter.models.UiAuthorizationPopup
import io.qent.broxy.ui.adapter.models.UiAuthorizationPopupStatus
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

private val javafxReady = AtomicBoolean(false)

@Composable
actual fun AuthorizationPopupDialog(
    popup: UiAuthorizationPopup,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val isSuccess = popup.status == UiAuthorizationPopupStatus.Success
    val onDismissLatest = rememberUpdatedState(onDismiss)
    if (isSuccess) {
        LaunchedEffect(popup.serverId, popup.status) {
            delay(1_200)
            onDismissLatest.value()
        }
    }

    Dialog(
        onDismissRequest = {},
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
                dismissOnBackPress = false,
            ),
    ) {
        BoxWithConstraints {
            val dialogWidth = maxWidth.coerceAtMost(920.dp)
            val dialogHeight = maxHeight.coerceAtMost(680.dp)
            Surface(
                modifier =
                    Modifier
                        .size(dialogWidth, dialogHeight)
                        .padding(AppTheme.spacing.lg),
                shape = AppTheme.shapes.dialog,
                tonalElevation = AppTheme.elevation.level3,
                color = MaterialTheme.colorScheme.surface,
                border =
                    BorderStroke(
                        width = AppTheme.strokeWidths.thin,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    ),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = AppTheme.spacing.lg,
                                    end = AppTheme.spacing.sm,
                                    top = AppTheme.spacing.md,
                                    bottom = AppTheme.spacing.sm,
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = true)) {
                            Text(
                                text = strings.authorizationPopupTitle(popup.serverName),
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(AppTheme.spacing.xs))
                            Text(
                                text = strings.authorizationPopupSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onCancel) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = strings.close,
                            )
                        }
                    }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = true)
                                .padding(horizontal = AppTheme.spacing.lg, vertical = AppTheme.spacing.sm),
                    ) {
                        AuthorizationWebView(url = popup.authorizationUrl)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthorizationWebView(url: String) {
    val urlState by rememberUpdatedState(url)
    var panel by remember { mutableStateOf<JFXPanel?>(null) }

    DisposableEffect(urlState) {
        if (javafxReady.compareAndSet(false, true)) {
            Platform.setImplicitExit(false)
        }
        val jfxPanel = JFXPanel()
        panel = jfxPanel
        Platform.runLater {
            val webView = WebView()
            webView.isContextMenuEnabled = false
            webView.engine.load(urlState)
            jfxPanel.scene = Scene(webView)
        }
        onDispose {
            Platform.runLater {
                jfxPanel.scene = null
            }
            panel = null
        }
    }

    val panelInstance = panel
    if (panelInstance != null) {
        SwingPanel(
            factory = { panelInstance },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
