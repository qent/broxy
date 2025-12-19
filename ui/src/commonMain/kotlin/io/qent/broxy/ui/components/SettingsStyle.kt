package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun SettingsLikeItem(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable ColumnScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    border: BorderStroke? = null,
    control: @Composable RowScope.() -> Unit,
) {
    SettingsLikeItemImpl(
        title = title,
        titleColor = MaterialTheme.colorScheme.onSurface,
        descriptionContent = {
            androidx.compose.material3.Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = modifier,
        supportingContent = supportingContent,
        onClick = onClick,
        border = border,
        control = control,
    )
}

@Composable
fun SettingsLikeItem(
    title: String,
    descriptionContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable ColumnScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke? = null,
    control: @Composable RowScope.() -> Unit,
) {
    SettingsLikeItemImpl(
        title = title,
        titleColor = titleColor,
        descriptionContent = descriptionContent,
        modifier = modifier,
        supportingContent = supportingContent,
        onClick = onClick,
        border = border,
        control = control,
    )
}

@Composable
private fun SettingsLikeItemImpl(
    title: String,
    titleColor: Color,
    descriptionContent: @Composable () -> Unit,
    modifier: Modifier,
    supportingContent: (@Composable ColumnScope.() -> Unit)?,
    onClick: (() -> Unit)?,
    border: BorderStroke? = null,
    control: @Composable RowScope.() -> Unit,
) {
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .then(clickModifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = border ?: BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = AppTheme.shapes.card,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.lg, vertical = AppTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                androidx.compose.material3.Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                )
                Spacer(Modifier.height(2.dp))
                descriptionContent()
                supportingContent?.invoke(this)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = control,
            )
        }
    }
}

@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(32.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        decorationBox = { innerTextField ->
            CompactInputSurface {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = AppTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            androidx.compose.material3.Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                }
            }
        },
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun CompactInputSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.height(32.dp),
        shape = AppTheme.shapes.input,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(AppTheme.strokeWidths.thin, MaterialTheme.colorScheme.outline),
        content = content,
    )
}

val DefaultSearchFieldWidth: Dp = 260.dp
