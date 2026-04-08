@file:Suppress("FunctionNaming")

package io.qent.broxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.icons.ServerIconResolver
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.services.checkStdioCommandAvailability
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppPrimaryButton
import io.qent.broxy.ui.components.AppSecondaryButton
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.EditorHeaderRow
import io.qent.broxy.ui.components.OpenExternalLinkButton
import io.qent.broxy.ui.components.ServerForm
import io.qent.broxy.ui.components.ServerFormStateFactory
import io.qent.broxy.ui.components.ServerIconBadge
import io.qent.broxy.ui.components.toDraft
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.ServerEditorState
import kotlinx.coroutines.launch

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun ServerEditorScreen(
    ui: UIState,
    store: AppStore,
    editor: ServerEditorState,
    onClose: () -> Unit,
    notify: (String) -> Unit = {},
) {
    val strings = LocalStrings.current
    val initialDraft =
        remember(editor) {
            when (editor) {
                ServerEditorState.Create ->
                    UiServerDraft(
                        id = "",
                        name = "",
                        enabled = true,
                        transport = UiStdioDraft(command = "", args = emptyList()),
                        env = emptyMap(),
                        originalId = null,
                        iconPath = null,
                    )

                is ServerEditorState.CreateFromImport -> editor.initialDraft
                is ServerEditorState.Edit -> store.getServerDraft(editor.serverId)
            }
        }

    if (initialDraft == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            EditorHeaderRow(
                title = strings.editServer,
                onBack = onClose,
            )
            Text(
                text = strings.serverNotFound,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val isCreate = editor is ServerEditorState.Create || editor is ServerEditorState.CreateFromImport
    val title = if (isCreate) strings.addServer else strings.editServer
    val primaryActionLabel = if (isCreate) strings.add else strings.save

    var form by remember(editor) { mutableStateOf(ServerFormStateFactory.from(initialDraft)) }
    val initialIconPath = initialDraft.iconPath
    var importedIcons by remember(editor) { mutableStateOf(emptySet<String>()) }

    val resolvedName = form.name.trim()
    val baseGeneratedId = generateServerId(resolvedName)
    val readyUi = ui as? UIState.Ready
    val existingServerIds =
        readyUi
            ?.servers
            ?.asSequence()
            ?.map { it.id }
            ?.toSet()
            .orEmpty()
    val occupiedIds = if (isCreate) existingServerIds else existingServerIds - initialDraft.id
    val resolvedId = generateUniqueServerId(baseGeneratedId, occupiedIds)
    val catalogServers = readyUi?.catalogServers.orEmpty()
    val registryMetadata =
        remember(catalogServers) {
            ServerIconResolver.registryMetadataFromItems(catalogServers)
        }
    val registryIconUrls =
        remember(catalogServers) {
            ServerIconResolver.registryIconUrlsFromItems(catalogServers)
        }
    val resolvedDraft =
        remember(form, resolvedId, resolvedName) {
            form.toDraft(
                id = resolvedId,
                name = resolvedName,
                originalId = null,
            )
        }
    val serverIcon =
        remember(resolvedDraft, registryIconUrls) {
            ServerIconResolver.resolve(resolvedDraft, registryIconUrls)
        }
    val serverExternalUrl =
        remember(resolvedDraft, registryMetadata) {
            ServerIconResolver.resolveMatchedMetadata(resolvedDraft, registryMetadata)?.externalUrl
        }

    val hasValidTransportFields =
        when (form.transportType) {
            "STDIO" -> form.command.trim().isNotBlank()
            "HTTP", "STREAMABLE_HTTP", "WS" -> form.url.trim().isNotBlank()
            else -> true
        }

    val canSubmit =
        readyUi != null &&
            resolvedName.isNotBlank() &&
            resolvedId.isNotBlank() &&
            hasValidTransportFields

    val scope = rememberCoroutineScope()
    var commandWarning by remember(editor) { mutableStateOf<String?>(null) }
    var commandCheckToken by remember(editor) { mutableStateOf(0) }

    val scrollState = rememberScrollState()
    val actionRowHeight = 40.dp

    val closeEditor = {
        if (importedIcons.isNotEmpty()) {
            importedIcons.forEach { iconPath ->
                scope.launch {
                    store.discardServerIcon(iconPath)
                }
            }
        }
        onClose()
    }

    val pickIcon: () -> Unit = {
        scope.launch {
            val result = store.pickServerIcon()
            val pickedPath = result.getOrNull()?.trim().orEmpty()
            if (pickedPath.isBlank()) return@launch
            val previousPath = form.iconPath
            form = form.copy(iconPath = pickedPath)
            if (previousPath != null && previousPath != initialIconPath && previousPath in importedIcons) {
                store.discardServerIcon(previousPath)
                importedIcons = importedIcons - previousPath
            }
            importedIcons = importedIcons + pickedPath
        }
        Unit
    }

    val clearIcon: () -> Unit = {
        val previousPath = form.iconPath?.trim().orEmpty()
        if (previousPath.isNotBlank()) {
            form = form.copy(iconPath = null)
            if (previousPath != initialIconPath && previousPath in importedIcons) {
                importedIcons = importedIcons - previousPath
                scope.launch {
                    store.discardServerIcon(previousPath)
                }
            }
        }
        Unit
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AppTheme.spacing.xs))

            EditorHeaderRow(
                title = title,
                onBack = closeEditor,
            ) {
                AppSecondaryButton(
                    onClick = closeEditor,
                    modifier = Modifier.height(actionRowHeight),
                ) {
                    Text(strings.cancel, style = MaterialTheme.typography.labelSmall)
                }
                AppPrimaryButton(
                    onClick = {
                        val currentReadyUi = readyUi ?: return@AppPrimaryButton
                        scope.launch {
                            val originalId = if (isCreate) null else (initialDraft.originalId ?: initialDraft.id)
                            val draft =
                                form.toDraft(
                                    id = resolvedId,
                                    name = resolvedName,
                                    originalId = originalId,
                                )

                            if (editor is ServerEditorState.CreateFromImport) {
                                currentReadyUi.intents.saveImportedServerFromClient(
                                    clientId = editor.clientId,
                                    sourceServerId = editor.sourceServerId,
                                    draft = draft,
                                )
                            } else {
                                currentReadyUi.intents.upsertServer(draft)
                            }
                            onClose()
                            notify(strings.savedName(draft.name))
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier.height(actionRowHeight),
                ) {
                    Text(primaryActionLabel, style = MaterialTheme.typography.labelSmall)
                }
            }

            OutlinedTextField(
                value = form.name,
                onValueChange = { form = form.copy(name = it) },
                label = { Text(strings.nameLabel) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    Box(modifier = Modifier.padding(end = AppTheme.spacing.xs)) {
                        if (serverExternalUrl != null && readyUi != null) {
                            OpenExternalLinkButton(
                                onClick = { readyUi.intents.openExternalUrl(serverExternalUrl) },
                                contentDescription = strings.openServerPageContentDescription,
                                buttonSize = 32.dp,
                                iconSize = 18.dp,
                                hoverUrl = serverExternalUrl,
                                modifier =
                                    Modifier
                                        .align(Alignment.Center)
                                        .offset(x = (-38).dp, y = 4.dp)
                                        .pointerHoverIcon(PointerIcon.Default),
                            )
                        }
                        ServerIconBadge(
                            icon = serverIcon,
                            backgroundColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Default),
                            onClick = pickIcon,
                            onRemove = clearIcon,
                        )
                    }
                },
            )

            ServerForm(
                state = form,
                onStateChange = { next ->
                    if (next.command != form.command) {
                        commandWarning = null
                    }
                    if (next.transportType != "STDIO") {
                        commandWarning = null
                    }
                    form = next
                },
                commandWarning = commandWarning,
                onCommandBlur = { command ->
                    if (form.transportType != "STDIO") return@ServerForm
                    val trimmed = command.trim()
                    if (trimmed.isBlank()) {
                        commandWarning = null
                        return@ServerForm
                    }
                    val token = commandCheckToken + 1
                    commandCheckToken = token
                    val envMap = parseEnvMap(form.env)
                    scope.launch {
                        val result = checkStdioCommandAvailability(trimmed, envMap)
                        if (commandCheckToken != token) return@launch
                        val availability = result.getOrNull()
                        commandWarning =
                            if (availability == null || availability.isAvailable) {
                                null
                            } else {
                                strings.commandNotFound
                            }
                    }
                },
            )

            Spacer(Modifier.height(AppTheme.spacing.md))
        }
        AppVerticalScrollbar(
            scrollState = scrollState,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .offset(x = AppTheme.spacing.md - AppTheme.strokeWidths.hairline),
        )
    }
}

private fun generateServerId(name: String): String {
    val normalized = name.trim().lowercase()
    if (normalized.isBlank()) return ""

    val sb = StringBuilder()
    var lastWasDash = false
    for (ch in normalized) {
        val isAllowed = ch.isLetterOrDigit()
        if (isAllowed) {
            sb.append(ch)
            lastWasDash = false
        } else if (!lastWasDash) {
            sb.append('-')
            lastWasDash = true
        }
    }

    return sb.toString().trim('-')
}

private fun generateUniqueServerId(
    baseId: String,
    occupiedIds: Set<String>,
): String {
    if (baseId.isBlank()) {
        return ""
    }
    var candidate = baseId
    var suffix = 2
    while (candidate in occupiedIds) {
        candidate = "$baseId-$suffix"
        suffix++
    }
    return candidate
}

private fun parseEnvMap(raw: String): Map<String, String> =
    raw
        .lines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val idx = trimmed.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (key.isEmpty()) null else key to value
        }.toMap()
