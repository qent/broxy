package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.qent.broxy.ui.theme.AppTheme

/**
 * Unified dialog scaffold that provides consistent padding, background and scrolling behaviour.
 *
 * @param title Title rendered at the top of the dialog.
 * @param onDismissRequest Callback invoked when the dialog should be dismissed.
 * @param modifier Additional modifiers for the surface container.
 * @param minWidth Minimum width for the dialog surface.
 * @param maxWidth Maximum width for the dialog surface.
 * @param titleStyle Typography style for the title text.
 * @param dismissButton Optional secondary action shown before the confirm button.
 * @param confirmButton Primary action placed at the end of the action row.
 * @param content Dialog body that becomes scrollable when it exceeds the max height.
 */
@Composable
fun AppDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = AppTheme.layout.dialogMinWidth,
    maxWidth: Dp = 640.dp,
    titleStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    dismissButton: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val scrollbarAdapter = rememberScrollbarAdapter(scrollState)
    val showScrollbar by remember {
        derivedStateOf { scrollState.canScrollForward || scrollState.canScrollBackward }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                modifier
                    .padding(horizontal = AppTheme.spacing.xl, vertical = AppTheme.spacing.lg)
                    .widthIn(
                        min = minWidth,
                        max = maxWidth,
                    ),
            shape = AppTheme.shapes.dialog,
            tonalElevation = AppTheme.elevation.level3,
            color = MaterialTheme.colorScheme.surface,
            border =
                BorderStroke(
                    width = AppTheme.strokeWidths.thin,
                    color = MaterialTheme.colorScheme.outlineVariant,
                ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = AppTheme.spacing.lg,
                                end =
                                    AppTheme.spacing.lg -
                                        if (showScrollbar) {
                                            AppTheme.layout.scrollbarThickness
                                        } else {
                                            0.dp
                                        },
                                top = AppTheme.spacing.md,
                            ),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                ) {
                    Text(title, style = titleStyle)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .weight(1f, fill = true)
                                    .heightIn(max = AppTheme.layout.dialogMaxHeight)
                                    .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                            content = content,
                        )
                        if (showScrollbar) {
                            AppVerticalScrollbar(
                                adapter = scrollbarAdapter,
                                modifier = Modifier.heightIn(max = AppTheme.layout.dialogMaxHeight),
                                canScroll = showScrollbar,
                                isScrollInProgress = scrollState.isScrollInProgress,
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = AppTheme.spacing.lg,
                                vertical = AppTheme.spacing.sm,
                            ),
                    horizontalArrangement = Arrangement.End,
                ) {
                    dismissButton?.invoke()
                    if (dismissButton != null) {
                        androidx.compose.foundation.layout.Spacer(
                            modifier = Modifier.width(AppTheme.spacing.sm),
                        )
                    }
                    confirmButton()
                }
            }
        }
    }
}
