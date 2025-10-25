package io.qent.broxy.ui.screens

import AppPrimaryButton
import AppSecondaryButton
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiAiClient
import io.qent.broxy.ui.adapter.models.UiAiClientBroxyConfigMismatchNotice
import io.qent.broxy.ui.adapter.models.UiAiClientMissingConfigNotice
import io.qent.broxy.ui.adapter.models.UiAiClientNoticeSeverity
import io.qent.broxy.ui.adapter.models.UiAiClientStatusLoadFailedNotice
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.ClientIconBadge
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.strings.AppStrings
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme

@Composable
fun ClientsScreen(ui: UIState) {
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
                            items(clients, key = { it.id }) { client ->
                                ClientCard(
                                    client = client,
                                    onInfo = { ui.intents.openAiClientInfo(client.id) },
                                    onConnect = { ui.intents.connectAiClient(client.id) },
                                    onDisconnect = { ui.intents.disconnectAiClient(client.id) },
                                )
                            }
                            items(connectionInfoCards(ui.inboundSsePort, strings), key = { it.title }) { card ->
                                ConnectionInfoCard(title = card.title, json = card.json)
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
            Text(
                text = client.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        IconButton(onClick = onInfo) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = strings.openClientInfo,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }

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
private fun ConnectionInfoCard(
    title: String,
    json: String,
) {
    val contentIndent = AppTheme.spacing.xs
    SettingsLikeItem(
        title = title,
        titleContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = contentIndent),
            )
        },
        descriptionContent = {
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.xs),
                thickness = AppTheme.strokeWidths.thin,
                color = MaterialTheme.colorScheme.outline,
            )
        },
        supportingContent = {
            SelectionContainer(modifier = Modifier.padding(start = contentIndent)) {
                Text(
                    text = json,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
    ) {}
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

private data class ConnectionInfo(
    val title: String,
    val json: String,
)

private fun connectionInfoCards(
    inboundPort: Int,
    strings: AppStrings,
): List<ConnectionInfo> =
    listOf(
        ConnectionInfo(
            title = strings.connectionInfoStdioTitle,
            json =
                """
                {
                  "mcpServers": {
                    "broxy": {
                      "command": "/Applications/broxy.app/Contents/MacOS/broxy",
                      "args": ["--stdio-proxy"]
                    }
                  }
                }
                """.trimIndent(),
        ),
        ConnectionInfo(
            title = strings.connectionInfoHttpTitle,
            json =
                """
                {
                  "mcpServers": {
                    "broxy": {
                      "url": "http://localhost:$inboundPort/mcp"
                    }
                  }
                }
                """.trimIndent(),
        ),
        ConnectionInfo(
            title = strings.connectionInfoSseTitle,
            json =
                """
                {
                  "mcpServers": {
                    "broxy": {
                      "url": "http://localhost:$inboundPort/sse"
                    }
                  }
                }
                """.trimIndent(),
        ),
    )

private val ClientIconSize = 42.dp
