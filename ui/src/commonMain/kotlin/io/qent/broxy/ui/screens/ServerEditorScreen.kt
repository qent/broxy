package io.qent.broxy.ui.screens

import AppPrimaryButton
import AppSecondaryButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.services.checkStdioCommandAvailability
import io.qent.broxy.ui.adapter.services.validateServerConnection
import io.qent.broxy.ui.adapter.store.AppStore
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.EditorHeaderRow
import io.qent.broxy.ui.components.ServerForm
import io.qent.broxy.ui.components.ServerFormStateFactory
import io.qent.broxy.ui.components.toDraft
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.ServerEditorState
import kotlinx.coroutines.launch

@Composable
fun ServerEditorScreen(
    ui: UIState,
    store: AppStore,
    editor: ServerEditorState,
    onClose: () -> Unit,
    notify: (String) -> Unit = {},
) {
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
                    )

                is ServerEditorState.Edit -> store.getServerDraft(editor.serverId)
            }
        }

    if (initialDraft == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            EditorHeaderRow(
                title = "Edit server",
                onBack = onClose,
            )
            Text(
                text = "Server not found.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val isCreate = editor is ServerEditorState.Create
    val title = if (isCreate) "Add server" else "Edit server"
    val primaryActionLabel = if (isCreate) "Add" else "Save"

    var form by remember(editor) { mutableStateOf(ServerFormStateFactory.from(initialDraft)) }

    val resolvedName = form.name.trim()
    val baseGeneratedId = generateServerId(resolvedName)
    val existingServerIds = (ui as? UIState.Ready)?.servers?.asSequence()?.map { it.id }?.toSet().orEmpty()
    val occupiedIds = if (isCreate) existingServerIds else existingServerIds - initialDraft.id
    val resolvedId = generateUniqueServerId(baseGeneratedId, occupiedIds)

    val hasValidTransportFields =
        when (form.transportType) {
            "STDIO" -> form.command.trim().isNotBlank()
            "HTTP", "STREAMABLE_HTTP", "WS" -> form.url.trim().isNotBlank()
            else -> true
        }

    val canSubmit =
        ui is UIState.Ready &&
            resolvedName.isNotBlank() &&
            resolvedId.isNotBlank() &&
            hasValidTransportFields

    val scope = rememberCoroutineScope()
    var commandWarning by remember(editor) { mutableStateOf<String?>(null) }
    var commandCheckToken by remember(editor) { mutableStateOf(0) }

    val scrollState = rememberScrollState()
    val actionRowHeight = 40.dp

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
            onBack = onClose,
        ) {
            AppSecondaryButton(
                onClick = onClose,
                modifier = Modifier.height(actionRowHeight),
            ) {
                Text("Cancel", style = MaterialTheme.typography.labelSmall)
            }
            AppPrimaryButton(
                onClick = {
                    val readyUi = ui as? UIState.Ready ?: return@AppPrimaryButton
                    scope.launch {
                        val originalId = if (isCreate) null else (initialDraft.originalId ?: initialDraft.id)
                        val draft =
                            form.toDraft(
                                id = resolvedId,
                                name = resolvedName,
                                originalId = originalId,
                            )

                        var toSave = draft
                        if (draft.enabled) {
                            val result = validateServerConnection(draft)
                            if (result.isFailure) {
                                val e = result.exceptionOrNull()
                                val isTimeout = e?.message?.contains("timed out", ignoreCase = true) == true
                                if (isTimeout) {
                                    notify("Connection timed out. Saved as disabled.")
                                } else {
                                    val errMsg = e?.message?.takeIf { it.isNotBlank() }
                                    val details = errMsg?.let { ": $it" } ?: ""
                                    notify("Connection failed$details. Saved as disabled.")
                                }
                                toSave = draft.copy(enabled = false)
                            }
                        }

                        readyUi.intents.upsertServer(toSave)
                        onClose()
                        notify("Saved ${toSave.name}")
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
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
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
                            "Command not found on PATH."
                        }
                }
            },
        )

        Spacer(Modifier.height(AppTheme.spacing.md))
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
    if (baseId.isBlank()) return ""
    if (baseId !in occupiedIds) return baseId

    var suffix = 2
    while (true) {
        val candidate = "$baseId-$suffix"
        if (candidate !in occupiedIds) return candidate
        suffix++
    }
}

private fun parseEnvMap(raw: String): Map<String, String> =
    raw.lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val idx = trimmed.indexOf(':')
        if (idx <= 0) return@mapNotNull null
        val key = trimmed.substring(0, idx).trim()
        val value = trimmed.substring(idx + 1).trim()
        if (key.isEmpty()) null else key to value
    }.toMap()
