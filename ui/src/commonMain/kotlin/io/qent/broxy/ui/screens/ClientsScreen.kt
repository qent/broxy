@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiAiClient
import io.qent.broxy.ui.adapter.models.UiAiClientBroxyConfigMismatchNotice
import io.qent.broxy.ui.adapter.models.UiAiClientMissingConfigNotice
import io.qent.broxy.ui.adapter.models.UiAiClientNoticeSeverity
import io.qent.broxy.ui.adapter.models.UiAiClientStatusLoadFailedNotice
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppPrimaryButton
import io.qent.broxy.ui.components.AppSecondaryButton
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.ClientIconBadge
import io.qent.broxy.ui.components.OpenExternalLinkButton
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.strings.AppStrings
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun ClientsScreen(
    ui: UIState,
    notify: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            when (ui) {
                UIState.Loading -> Text(strings.loading, style = MaterialTheme.typography.bodyMedium)
                is UIState.Error -> Text(strings.errorMessage(ui.message), style = MaterialTheme.typography.bodyMedium)
                is UIState.Ready -> {
                    val clients = ui.clients
                    if (clients.isEmpty()) {
                        EmptyState(
                            title = strings.clientsEmptyTitle,
                            subtitle = strings.clientsEmptySubtitle,
                        )
                    } else {
                        val connectionInfos = connectionInfoCards(ui.inboundHttpPort, strings)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f, fill = true),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                            contentPadding =
                                PaddingValues(
                                    top = AppTheme.spacing.lg,
                                    bottom = AppTheme.spacing.lg,
                                ),
                        ) {
                            item(key = "connection-info-tabs") {
                                ConnectionInfoTabs(
                                    cards = connectionInfos,
                                    onCopied = { notify(strings.connectionSnippetCopied) },
                                )
                            }
                            items(clients, key = { it.id }) { client ->
                                ClientCard(
                                    client = client,
                                    onInfo = { ui.intents.openAiClientInfo(client.id) },
                                    onConnect = { ui.intents.connectAiClient(client.id) },
                                    onDisconnect = { ui.intents.disconnectAiClient(client.id) },
                                )
                            }
                        }
                    }
                }
            }
        }

        AppVerticalScrollbar(
            listState = listState,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .offset(x = AppTheme.spacing.md - AppTheme.strokeWidths.hairline),
        )
    }
}

@Composable
@Suppress("LongMethod")
private fun ClientCard(
    client: UiAiClient,
    onInfo: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val strings = LocalStrings.current
    val descriptionColor = MaterialTheme.colorScheme.onSurfaceVariant
    val noticeColor =
        when (client.notice?.severity) {
            UiAiClientNoticeSeverity.Error -> MaterialTheme.colorScheme.error
            UiAiClientNoticeSeverity.Warning -> MaterialTheme.colorScheme.secondary
            null -> descriptionColor
        }

    val iconSize = ClientIconSize
    val buttonModifier = Modifier.height(32.dp).width(122.dp)

    SettingsLikeItem(
        title = client.name,
        titleColor = MaterialTheme.colorScheme.onSurface,
        leadingContent = {
            ClientIconBadge(
                iconId = client.iconId,
                backgroundColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(iconSize),
            )
        },
        titleContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs),
            ) {
                Text(
                    text = client.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OpenExternalLinkButton(
                    onClick = onInfo,
                    contentDescription = strings.openClientInfo,
                    buttonSize = 24.dp,
                    modifier = Modifier.align(Alignment.Top),
                    hoverUrl = client.infoUrl,
                )
            }
        },
        descriptionContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = client.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = descriptionColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val noticeText =
                    when (val notice = client.notice) {
                        is UiAiClientMissingConfigNotice -> strings.aiClientConfigNotFound(notice.clientName)
                        is UiAiClientBroxyConfigMismatchNotice -> {
                            val configuredUrl = notice.configuredUrl
                            if (configuredUrl.isNullOrBlank()) {
                                strings.aiClientOtherBroxyConfig()
                            } else {
                                strings.aiClientOtherBroxyConfigAt(configuredUrl)
                            }
                        }
                        is UiAiClientStatusLoadFailedNotice -> strings.aiClientStatusLoadFailed(notice.details)
                        null -> null
                    }
                if (noticeText != null) {
                    Text(strings.separatorDot, style = MaterialTheme.typography.bodySmall, color = descriptionColor)
                    Text(
                        text = noticeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = noticeColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) {
        if (client.isConnected) {
            AppSecondaryButton(
                onClick = onDisconnect,
                enabled = client.canConnect,
                modifier = buttonModifier,
            ) {
                Text(strings.disconnect, style = MaterialTheme.typography.labelSmall)
            }
        } else {
            AppPrimaryButton(
                onClick = onConnect,
                enabled = client.canConnect,
                modifier = buttonModifier,
            ) {
                Text(strings.connect, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ConnectionInfoTabs(
    cards: List<ConnectionInfo>,
    onCopied: () -> Unit,
) {
    if (cards.isEmpty()) return
    val strings = LocalStrings.current
    var selectedIndex by remember { mutableStateOf(0) }
    val selected = cards.getOrNull(selectedIndex) ?: cards.first()
    SettingsLikeItem(
        title = selected.title,
        contentPadding = PaddingValues(horizontal = AppTheme.spacing.sm, vertical = AppTheme.spacing.sm),
        titleContent = {
            PrimaryTabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                divider = {},
            ) {
                cards.forEachIndexed { index, card ->
                    Tab(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        modifier = Modifier.height(32.dp),
                        text = { Text(card.title, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
        descriptionContent = {},
        supportingContent = {
            ConnectionInfoSnippet(
                info = selected,
                copyContentDescription = strings.copyConnectionSnippetContentDescription,
                onCopied = onCopied,
            )
        },
    ) {}
}

@Composable
private fun ConnectionInfoSnippet(
    info: ConnectionInfo,
    copyContentDescription: String,
    onCopied: () -> Unit,
) {
    val contentIndent = AppTheme.spacing.xxs
    val snippetTint = MaterialTheme.colorScheme.onSurfaceVariant
    val copyPayloads = info.copyPayloads.ifEmpty { listOf("") }
    Row(
        modifier = Modifier.padding(start = contentIndent),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        SelectionContainer {
            Text(
                text = info.json,
                style = MaterialTheme.typography.bodySmall,
                color = snippetTint,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(AppTheme.spacing.xs),
            )
        }
        if (copyPayloads.size == 1) {
            ConnectionInfoCopyButton(
                copyPayload = copyPayloads.first(),
                contentDescription = copyContentDescription,
                tint = snippetTint,
                modifier = Modifier.align(Alignment.CenterVertically),
                onCopied = onCopied,
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.CenterVertically),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                copyPayloads.forEach { payload ->
                    ConnectionInfoCopyButton(
                        copyPayload = payload,
                        contentDescription = copyContentDescription,
                        tint = snippetTint,
                        onCopied = onCopied,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionInfoCopyButton(
    copyPayload: String,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onCopied: () -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier =
            modifier
                .size(18.dp)
                .hoverable(interactionSource = interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        clipboardManager.setText(AnnotatedString(copyPayload))
                        onCopied()
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (isHovered) tint.copy(alpha = 0.14f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(AppTheme.spacing.sm))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal data class ConnectionInfo(
    val title: String,
    val json: String,
    val copyPayloads: List<String>,
)

private const val BROXY_STDIO_COMMAND = "/Applications/broxy.app/Contents/MacOS/broxy"
private const val BROXY_STDIO_ARG = "--stdio-proxy"

internal fun httpConnectionInfoCopyPayload(inboundPort: Int): String = "http://localhost:$inboundPort/mcp"

internal fun sseConnectionInfoCopyPayload(inboundPort: Int): String = "http://localhost:$inboundPort/sse"

internal fun stdioConnectionInfoCopyPayloads(): List<String> = listOf(BROXY_STDIO_COMMAND, BROXY_STDIO_ARG)

internal fun connectionInfoCards(
    inboundPort: Int,
    strings: AppStrings,
): List<ConnectionInfo> =
    listOf(
        ConnectionInfo(
            title = strings.connectionInfoHttpTitle,
            copyPayloads = listOf(httpConnectionInfoCopyPayload(inboundPort)),
            json =
                """
                {
                  "mcpServers": {
                    "broxy": {
                      "url": "${httpConnectionInfoCopyPayload(inboundPort)}"
                    }
                  }
                }
                """.trimIndent(),
        ),
        ConnectionInfo(
            title = strings.connectionInfoStdioTitle,
            copyPayloads = stdioConnectionInfoCopyPayloads(),
            json =
                """
                {
                  "mcpServers": {
                    "broxy": {
                      "command": "$BROXY_STDIO_COMMAND",
                      "args": ["$BROXY_STDIO_ARG"]
                    }
                  }
                }
                """.trimIndent(),
        ),
        ConnectionInfo(
            title = strings.connectionInfoSseTitle,
            copyPayloads = listOf(sseConnectionInfoCopyPayload(inboundPort)),
            json =
                """
                {
                  "mcpServers": {
                    "broxy": {
                      "url": "${sseConnectionInfoCopyPayload(inboundPort)}"
                    }
                  }
                }
                """.trimIndent(),
        ),
    )

private val ClientIconSize = 42.dp
