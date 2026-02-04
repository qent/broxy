@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.theme.AppTheme

@Composable
@Suppress("LongParameterList")
fun SettingsLikeItem(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable ColumnScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues? = null,
    control: @Composable RowScope.() -> Unit,
) {
    SettingsLikeItemImpl(
        title = title,
        titleColor = MaterialTheme.colorScheme.onSurface,
        descriptionContent = {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = modifier,
        supportingContent = supportingContent,
        onClick = onClick,
        leadingContent = leadingContent,
        titleContent = titleContent,
        border = border,
        contentPadding = contentPadding,
        control = control,
    )
}

@Composable
@Suppress("LongParameterList")
fun SettingsLikeItem(
    title: String,
    descriptionContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable ColumnScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    leadingContent: (@Composable () -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues? = null,
    control: @Composable RowScope.() -> Unit,
) {
    SettingsLikeItemImpl(
        title = title,
        titleColor = titleColor,
        descriptionContent = descriptionContent,
        modifier = modifier,
        supportingContent = supportingContent,
        onClick = onClick,
        titleContent = titleContent,
        leadingContent = leadingContent,
        border = border,
        contentPadding = contentPadding,
        control = control,
    )
}

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun SettingsLikeItemImpl(
    title: String,
    titleColor: Color,
    descriptionContent: @Composable () -> Unit,
    modifier: Modifier,
    supportingContent: (@Composable ColumnScope.() -> Unit)?,
    onClick: (() -> Unit)?,
    leadingContent: (@Composable () -> Unit)?,
    titleContent: (@Composable () -> Unit)?,
    border: BorderStroke? = null,
    contentPadding: PaddingValues? = null,
    control: @Composable RowScope.() -> Unit,
) {
    val resolvedContentPadding =
        contentPadding ?: PaddingValues(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.md)
    val clickModifier =
        if (onClick == null) {
            Modifier
        } else {
            Modifier
                .clip(AppTheme.shapes.card)
                .clickable(onClick = onClick)
        }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .then(clickModifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = border ?: BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = AppTheme.shapes.card,
    ) {
        val rowModifier =
            if (leadingContent == null) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            }
        Row(
            modifier = rowModifier.padding(resolvedContentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingContent != null) {
                Box(
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    leadingContent()
                }
                Spacer(Modifier.width(AppTheme.spacing.sm))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
            ) {
                if (titleContent == null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = titleColor,
                    )
                } else {
                    titleContent()
                }
                descriptionContent()
                supportingContent?.invoke(this)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = control,
            )
        }
    }
}

val DefaultSearchFieldWidth: Dp = 520.dp
