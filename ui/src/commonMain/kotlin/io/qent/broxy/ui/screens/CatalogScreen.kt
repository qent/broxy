@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "TooManyFunctions", "DEPRECATION")

package io.qent.broxy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.qent.broxy.ui.adapter.catalog.CatalogFieldFormat
import io.qent.broxy.ui.adapter.catalog.CatalogInstallField
import io.qent.broxy.ui.adapter.catalog.CatalogInstallPlanner
import io.qent.broxy.ui.adapter.catalog.CatalogInstallSession
import io.qent.broxy.ui.adapter.catalog.CatalogServerItem
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiServerIcon
import io.qent.broxy.ui.adapter.store.UIState
import io.qent.broxy.ui.components.AppPrimaryButton
import io.qent.broxy.ui.components.AppSecondaryButton
import io.qent.broxy.ui.components.AppVerticalScrollbar
import io.qent.broxy.ui.components.DeleteConfirmationDialog
import io.qent.broxy.ui.components.EditorHeaderRow
import io.qent.broxy.ui.components.OpenExternalLinkButton
import io.qent.broxy.ui.components.SearchField
import io.qent.broxy.ui.components.SearchFieldFabAlignedBottomPadding
import io.qent.broxy.ui.components.ServerIconBadge
import io.qent.broxy.ui.components.SettingsLikeItem
import io.qent.broxy.ui.strings.LocalStrings
import io.qent.broxy.ui.theme.AppTheme
import io.qent.broxy.ui.viewmodels.AppState
import io.qent.broxy.ui.viewmodels.Screen

private const val CATALOG_COLUMNS_COUNT = 2
private const val CATALOG_DESCRIPTION_MAX_LINES = 3
private val CATALOG_CARD_ICON_SIZE = 42.dp
private val CATALOG_ACTION_BUTTON_SIZE = 28.dp
private val CATALOG_ACTION_ICON_SIZE = 16.dp
private val CATALOG_INSTALL_TITLE_ICON_SIZE = 32.dp
private val CATALOG_INSTALL_CONTROL_WIDTH = 280.dp
private val CATALOG_INSTALL_CONTROL_HEIGHT = 32.dp
private const val CATALOG_MARKDOWN_URL_TAG = "catalog-url"
private val CATALOG_EXTERNAL_LINK_REGEX = Regex("""\[([^\]]+)]\((https?://[^\s)]+)\)""")
private val CATALOG_FIELD_REFERENCE_REGEX = Regex("""\[([^\]]+)](?!\()""")
private val CATALOG_NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{Nd}]+")
private val CATALOG_PUNCTUATION_SPACING_REGEX = Regex("\\s+([,.;:!?])")
private val CATALOG_MULTIPLE_SPACES_REGEX = Regex("\\s{2,}")

@Composable
@Suppress("CyclomaticComplexMethod")
fun CatalogScreen(
    ui: UIState,
    state: AppState,
    notify: (String) -> Unit = {},
) {
    val strings = LocalStrings.current
    val readyUi = ui as? UIState.Ready

    if (readyUi?.pendingCatalogInstallSession != null) {
        LaunchedEffect(readyUi.pendingCatalogInstallRequestId) {
            val session = readyUi.pendingCatalogInstallSession ?: return@LaunchedEffect
            state.catalogInstall.value = session
            readyUi.intents.consumePendingCatalogInstall()
        }
    }

    val activeSession = state.catalogInstall.value
    if (activeSession != null && readyUi != null) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(1.dp))
                CatalogInstallScreen(
                    session = activeSession,
                    onClose = { state.catalogInstall.value = null },
                    onInstall = { draft ->
                        readyUi.intents.upsertCatalogServer(draft)
                        state.catalogInstall.value = null
                        state.currentScreen.value = Screen.Servers
                        notify(strings.savedName(draft.name))
                    },
                    onOpenExternalUrl = { url -> readyUi.intents.openExternalUrl(url) },
                    notify = notify,
                )
            }
        }
        return
    }

    var query by rememberSaveable { mutableStateOf("") }
    var pendingUninstall by remember { mutableStateOf<CatalogServerItem?>(null) }
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.spacing.md)) {
        when (ui) {
            is UIState.Loading -> {
                Text(strings.loading, style = MaterialTheme.typography.bodyMedium)
            }

            is UIState.Error -> {
                Text(strings.errorMessage(ui.message), style = MaterialTheme.typography.bodyMedium)
            }

            is UIState.Ready -> {
                val filtered = filterCatalogItems(ui.catalogServers, query)
                val rows = buildCatalogRows(filtered)

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                ) {
                    if (ui.catalogLoading && filtered.isEmpty()) {
                        Text(strings.loadingInline, style = MaterialTheme.typography.bodyMedium)
                    } else if (filtered.isEmpty()) {
                        Text("No catalog servers", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f, fill = true),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                            contentPadding =
                                PaddingValues(
                                    top = AppTheme.spacing.lg,
                                    bottom = AppTheme.spacing.fab,
                                ),
                        ) {
                            items(items = rows, key = { row -> row.left.id }) { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    CatalogServerCard(
                                        item = row.left,
                                        modifier = Modifier.weight(1f),
                                        onInstall = {
                                            ui.intents.installCatalogServer(row.left.id)
                                            if (shouldRedirectToServersAfterCatalogInstall(row.left)) {
                                                state.catalogInstall.value = null
                                                state.currentScreen.value = Screen.Servers
                                            }
                                        },
                                        onUninstall = { pendingUninstall = row.left },
                                        onOpenExternalUrl = { url -> ui.intents.openExternalUrl(url) },
                                    )

                                    val right = row.right
                                    if (right != null) {
                                        CatalogServerCard(
                                            item = right,
                                            modifier = Modifier.weight(1f),
                                            onInstall = {
                                                ui.intents.installCatalogServer(right.id)
                                                if (shouldRedirectToServersAfterCatalogInstall(right)) {
                                                    state.catalogInstall.value = null
                                                    state.currentScreen.value = Screen.Servers
                                                }
                                            },
                                            onUninstall = { pendingUninstall = right },
                                            onOpenExternalUrl = { url -> ui.intents.openExternalUrl(url) },
                                        )
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
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

        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search catalog",
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SearchFieldFabAlignedBottomPadding),
        )

        val toUninstall = pendingUninstall
        if (readyUi != null && toUninstall != null) {
            val uninstallName = readyUi.servers.firstOrNull { it.id == toUninstall.id }?.name ?: toUninstall.title
            DeleteConfirmationDialog(
                title = strings.deleteServerTitle,
                prompt = strings.deleteServerPrompt(uninstallName),
                description = strings.deleteServerDescription,
                onConfirm = {
                    readyUi.intents.uninstallCatalogServer(toUninstall.id)
                    pendingUninstall = null
                },
                onDismiss = { pendingUninstall = null },
                confirmLabel = strings.delete,
                dismissLabel = strings.cancel,
            )
        }
    }
}

@Composable
private fun CatalogServerCard(
    item: CatalogServerItem,
    modifier: Modifier = Modifier,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val externalUrl = resolveCatalogExternalUrl(item)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = AppTheme.shapes.card,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
            ) {
                ServerIconBadge(
                    icon = item.iconUrl.toUiServerIcon(),
                    modifier = Modifier.size(CATALOG_CARD_ICON_SIZE),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs),
                    ) {
                        Text(
                            text = item.title,
                            modifier = Modifier.offset(y = (-2).dp),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (externalUrl != null) {
                            OpenExternalLinkButton(
                                onClick = { onOpenExternalUrl(externalUrl) },
                                contentDescription = strings.openServerPageContentDescription,
                                buttonSize = 24.dp,
                                modifier = Modifier.align(Alignment.Top),
                                hoverUrl = externalUrl,
                            )
                        }
                    }
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        minLines = CATALOG_DESCRIPTION_MAX_LINES,
                        maxLines = CATALOG_DESCRIPTION_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CatalogInstallAction(
                    installed = item.installed,
                    onInstall = onInstall,
                    onUninstall = onUninstall,
                    modifier = Modifier.offset(x = 6.dp, y = (-6).dp),
                )
            }
        }
    }
}

@Composable
private fun CatalogInstallAction(
    installed: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!installed) {
        val hoverInteraction = remember { MutableInteractionSource() }
        val isHovered by hoverInteraction.collectIsHoveredAsState()
        Box(
            modifier =
                modifier
                    .size(CATALOG_ACTION_BUTTON_SIZE)
                    .hoverable(hoverInteraction),
            contentAlignment = Alignment.Center,
        ) {
            CatalogCompactActionButton(
                icon = Icons.Outlined.Add,
                contentDescription = "Install",
                onClick = onInstall,
                showBorder = isHovered,
            )
        }
        return
    }

    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    Box(
        modifier =
            modifier
                .size(CATALOG_ACTION_BUTTON_SIZE)
                .hoverable(hoverInteraction),
        contentAlignment = Alignment.Center,
    ) {
        if (isHovered) {
            CatalogCompactActionButton(
                icon = Icons.Outlined.Remove,
                contentDescription = "Uninstall",
                onClick = onUninstall,
                showBorder = true,
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Installed",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(CATALOG_ACTION_ICON_SIZE),
            )
        }
    }
}

@Composable
private fun CatalogCompactActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    showBorder: Boolean,
) {
    val borderModifier =
        if (showBorder) {
            Modifier.border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = AppTheme.shapes.button,
            )
        } else {
            Modifier
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .then(borderModifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(CATALOG_ACTION_ICON_SIZE),
        )
    }
}

internal data class CatalogRow(
    val left: CatalogServerItem,
    val right: CatalogServerItem?,
)

internal fun shouldRedirectToServersAfterCatalogInstall(item: CatalogServerItem): Boolean = item.canInstallWithoutInput

internal fun filterCatalogItems(
    items: List<CatalogServerItem>,
    query: String,
): List<CatalogServerItem> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isBlank()) return items

    return items.filter { item ->
        item.title.contains(trimmedQuery, ignoreCase = true) ||
            item.canonicalName.contains(trimmedQuery, ignoreCase = true) ||
            item.description.contains(trimmedQuery, ignoreCase = true)
    }
}

internal fun resolveCatalogExternalUrl(item: CatalogServerItem): String? =
    resolveCatalogExternalUrl(
        websiteUrl = item.websiteUrl,
        repositoryUrl = item.repositoryUrl,
    )

internal fun resolveCatalogExternalUrl(
    websiteUrl: String?,
    repositoryUrl: String?,
): String? = websiteUrl?.trim()?.takeIf { it.isNotEmpty() } ?: repositoryUrl?.trim()?.takeIf { it.isNotEmpty() }

internal fun buildCatalogRows(items: List<CatalogServerItem>): List<CatalogRow> =
    items
        .chunked(CATALOG_COLUMNS_COUNT)
        .map { chunk ->
            CatalogRow(
                left = chunk.first(),
                right = chunk.getOrNull(1),
            )
        }

@Composable
@Suppress("CyclomaticComplexMethod")
private fun CatalogInstallScreen(
    session: CatalogInstallSession,
    onClose: () -> Unit,
    onInstall: (UiServerDraft) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    notify: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val clipboardManager = LocalClipboardManager.current
    val installStepSpecs = remember(session) { buildCatalogInstallStepSpecs(session) }
    val fieldsById = remember(session) { session.fields.associateBy { it.id } }
    val values =
        remember(session) {
            mutableStateMapOf<String, String>().apply {
                putAll(CatalogInstallPlanner.buildInitialFieldValues(session))
            }
        }
    val missingRequired = CatalogInstallPlanner.missingRequiredFields(session, values)
    val canSubmit = missingRequired.isEmpty()
    val scrollState = rememberScrollState()
    val actionRowHeight = 40.dp
    val serverTitle = session.detail.displayName()
    val serverIcon = session.detail.iconUrl().toUiServerIcon()
    val externalUrl = resolveCatalogExternalUrl(session.detail.websiteUrl, session.detail.repository?.url)
    val serverDescription =
        session.detail.description
            .trim()
            .ifEmpty { strings.noDescriptionProvided }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md),
        ) {
            Spacer(Modifier.height(AppTheme.spacing.xs))

            EditorHeaderRow(
                title = "",
                onBack = onClose,
                titleContent = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                    ) {
                        Text(
                            text = "Connect to",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ServerIconBadge(
                            icon = serverIcon,
                            modifier = Modifier.size(CATALOG_INSTALL_TITLE_ICON_SIZE),
                            contentDescription = serverTitle,
                            padding = AppTheme.spacing.xs,
                        )
                        Text(
                            text = serverTitle,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (externalUrl != null) {
                            OpenExternalLinkButton(
                                onClick = { onOpenExternalUrl(externalUrl) },
                                contentDescription = strings.openServerPageContentDescription,
                                modifier = Modifier.align(Alignment.Top),
                                hoverUrl = externalUrl,
                            )
                        }
                    }
                },
            ) {
                AppSecondaryButton(
                    onClick = onClose,
                    modifier = Modifier.height(actionRowHeight),
                ) {
                    Text(strings.cancel, style = MaterialTheme.typography.labelSmall)
                }
                AppPrimaryButton(
                    onClick = {
                        val result =
                            CatalogInstallPlanner.buildInstallResult(
                                session = session,
                                displayName = "",
                                fieldValues = values,
                            )
                        result.onSuccess { installResult ->
                            onInstall(installResult.draft)
                        }
                        result.onFailure { error ->
                            notify(error.message ?: "Failed to install catalog server")
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier.height(actionRowHeight),
                ) {
                    Text("Install", style = MaterialTheme.typography.labelSmall)
                }
            }

            Text(
                text = serverDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            vertical = AppTheme.spacing.lg,
                            horizontal = AppTheme.spacing.md + AppTheme.spacing.sm,
                        ),
            )

            if (installStepSpecs.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    installStepSpecs.forEachIndexed { index, step ->
                        CatalogInstallStepItem(
                            stepNumber = index + 1,
                            markdown = step.markdown,
                            fields = step.fieldIds.mapNotNull { fieldId -> fieldsById[fieldId] },
                            values = values,
                            clipboardManager = clipboardManager,
                            onOpenExternalUrl = onOpenExternalUrl,
                        )
                    }
                }
            } else {
                session.fields.forEach { field ->
                    CatalogInstallFieldEditor(
                        field = field,
                        values = values,
                        clipboardManager = clipboardManager,
                    )
                }
            }

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

@Composable
private fun CatalogInstallStepItem(
    stepNumber: Int,
    markdown: String,
    fields: List<CatalogInstallField>,
    values: SnapshotStateMap<String, String>,
    clipboardManager: ClipboardManager,
    onOpenExternalUrl: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = AppTheme.spacing.md + AppTheme.spacing.sm, bottom = AppTheme.spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        ) {
            Text(
                text = "$stepNumber.",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (markdown.isNotBlank()) {
                CatalogInstallMarkdownText(
                    markdown = markdown,
                    onOpenExternalUrl = onOpenExternalUrl,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        fields.forEach { field ->
            CatalogInstallFieldEditor(
                field = field,
                values = values,
                clipboardManager = clipboardManager,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun CatalogInstallMarkdownText(
    markdown: String,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var hoveredLinkUrl by remember { mutableStateOf<String?>(null) }
    val annotated =
        remember(markdown, linkColor, hoveredLinkUrl) {
            buildCatalogMarkdownAnnotatedString(
                markdown = markdown,
                linkColor = linkColor,
                hoveredLinkUrl = hoveredLinkUrl,
            )
        }
    BasicText(
        text = annotated,
        modifier =
            modifier
                .onPointerEvent(PointerEventType.Move) { event ->
                    val layout = textLayoutResult
                    val pointer = event.changes.firstOrNull()?.position
                    if (layout != null && pointer != null) {
                        val offset = layout.getOffsetForPosition(pointer)
                        hoveredLinkUrl =
                            annotated
                                .getStringAnnotations(
                                    tag = CATALOG_MARKDOWN_URL_TAG,
                                    start = offset,
                                    end = offset,
                                ).firstOrNull()
                                ?.item
                    }
                }.onPointerEvent(PointerEventType.Exit) {
                    hoveredLinkUrl = null
                }.pointerInput(annotated, textLayoutResult) {
                    detectTapGestures { position ->
                        val layout = textLayoutResult ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(position)
                        val url =
                            annotated
                                .getStringAnnotations(
                                    tag = CATALOG_MARKDOWN_URL_TAG,
                                    start = offset,
                                    end = offset,
                                ).firstOrNull()
                                ?.item
                                ?.trim()
                        if (!url.isNullOrEmpty()) {
                            onOpenExternalUrl(url)
                        }
                    }
                },
        style = MaterialTheme.typography.bodyMedium.copy(color = baseColor),
        onTextLayout = { layoutResult -> textLayoutResult = layoutResult },
    )
}

@Composable
private fun CatalogInstallFieldEditor(
    field: CatalogInstallField,
    values: SnapshotStateMap<String, String>,
    clipboardManager: ClipboardManager,
) {
    val fieldTitle = buildCatalogInstallFieldTitle(field)
    val fieldDescription = buildCatalogInstallFieldDescription(field)
    when (field.format) {
        CatalogFieldFormat.Boolean -> {
            val isChecked = values[field.id]?.trim()?.equals("true", ignoreCase = true) == true
            CatalogBooleanSettingItem(
                title = fieldTitle,
                description = fieldDescription,
                checked = isChecked,
                onCheckedChange = { checked ->
                    values[field.id] = if (checked) "true" else "false"
                },
            )
        }

        else -> {
            val currentValue = values[field.id].orEmpty()
            val missing = field.isRequired && currentValue.trim().isEmpty()
            CatalogTextSettingItem(
                title = fieldTitle,
                description = fieldDescription,
                value = currentValue,
                placeholder = field.placeholder,
                isSecret = field.isSecret,
                isError = missing,
                onValueChange = { next -> values[field.id] = next },
                onPasteFromClipboard = {
                    val clipboardText = clipboardManager.getText()?.text.orEmpty()
                    if (clipboardText.isNotEmpty()) {
                        values[field.id] = clipboardText
                    }
                },
            )
        }
    }
}

@Composable
private fun CatalogBooleanSettingItem(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsLikeItem(
        title = title,
        descriptionContent = {
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        contentPadding = catalogInstallFieldCardPadding(),
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun CatalogTextSettingItem(
    title: String,
    description: String?,
    value: String,
    placeholder: String?,
    isSecret: Boolean,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
) {
    SettingsLikeItem(
        title = title,
        descriptionContent = {
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        contentPadding = catalogInstallFieldCardPadding(),
    ) {
        Box(
            modifier =
                Modifier
                    .widthIn(min = CATALOG_INSTALL_CONTROL_WIDTH, max = CATALOG_INSTALL_CONTROL_WIDTH)
                    .height(CATALOG_INSTALL_CONTROL_HEIGHT),
            contentAlignment = Alignment.CenterEnd,
        ) {
            CatalogCompactTextField(
                value = value,
                placeholder = placeholder,
                isSecret = isSecret,
                isError = isError,
                onValueChange = onValueChange,
                onPasteFromClipboard = onPasteFromClipboard,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CatalogCompactTextField(
    value: String,
    placeholder: String?,
    isSecret: Boolean,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    val cursorColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(CATALOG_INSTALL_CONTROL_HEIGHT),
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        visualTransformation =
            if (isSecret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
        decorationBox = { innerTextField ->
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = AppTheme.shapes.input,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(AppTheme.strokeWidths.thin, borderColor),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = AppTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && !placeholder.isNullOrBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                    Box(
                        modifier =
                            Modifier
                                .size(18.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onPasteFromClipboard,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = "Paste from clipboard",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        },
        cursorBrush = SolidColor(cursorColor),
    )
}

@Composable
private fun catalogInstallFieldCardPadding(): PaddingValues =
    PaddingValues(
        start = AppTheme.spacing.md + AppTheme.spacing.sm,
        end = AppTheme.spacing.md,
        top = AppTheme.spacing.md,
        bottom = AppTheme.spacing.md,
    )

private fun buildCatalogInstallFieldTitle(field: CatalogInstallField): String {
    val suffix = if (field.isRequired) " *" else ""
    return field.label + suffix
}

private fun buildCatalogInstallFieldDescription(field: CatalogInstallField): String? =
    field.description
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal data class CatalogInstallStepSpec(
    val markdown: String,
    val fieldIds: List<String>,
)

internal data class CatalogMarkdownSegment(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val url: String? = null,
)

@Suppress("NestedBlockDepth")
internal fun buildCatalogInstallStepSpecs(session: CatalogInstallSession): List<CatalogInstallStepSpec> {
    if (session.installSteps.isEmpty()) return emptyList()

    val fieldReferenceIndex = buildCatalogFieldReferenceIndex(session.fields)
    val assignedFieldIds = linkedSetOf<String>()
    val steps = mutableListOf<CatalogInstallStepSpec>()

    session.installSteps.forEach { rawStep ->
        val step = rawStep.trim()
        if (step.isEmpty()) return@forEach

        val stepFieldIds = mutableListOf<String>()
        var renderedStep =
            CATALOG_FIELD_REFERENCE_REGEX.replace(step) { match ->
                val referenceLabel = match.groupValues[1].trim()
                val normalizedReference = normalizeCatalogFieldReference(referenceLabel)
                val fieldId = fieldReferenceIndex[normalizedReference]
                if (fieldId == null) {
                    match.value
                } else {
                    if (assignedFieldIds.add(fieldId)) {
                        stepFieldIds += fieldId
                    }
                    ""
                }
            }

        if (stepFieldIds.isEmpty()) {
            val normalizedStep = normalizeCatalogFieldReference(renderedStep)
            val fieldId = fieldReferenceIndex[normalizedStep]
            if (fieldId != null) {
                if (assignedFieldIds.add(fieldId)) {
                    stepFieldIds += fieldId
                }
                renderedStep = ""
            }
        }

        renderedStep = cleanupCatalogInstallStepMarkdown(renderedStep)
        if (renderedStep.isNotEmpty() || stepFieldIds.isNotEmpty()) {
            steps += CatalogInstallStepSpec(markdown = renderedStep, fieldIds = stepFieldIds.distinct())
        }
    }

    session.fields
        .filter { field -> field.isRequired && field.id !in assignedFieldIds }
        .forEach { field ->
            assignedFieldIds += field.id
            steps += CatalogInstallStepSpec(markdown = "Provide **${field.label}**.", fieldIds = listOf(field.id))
        }

    return steps
}

internal fun parseCatalogMarkdownSegments(markdown: String): List<CatalogMarkdownSegment> {
    val source = markdown.trim()
    if (source.isEmpty()) return emptyList()

    val segments = mutableListOf<CatalogMarkdownSegment>()
    var cursor = 0
    CATALOG_EXTERNAL_LINK_REGEX.findAll(source).forEach { match ->
        val start = match.range.first
        if (start > cursor) {
            segments +=
                parseCatalogInlineSegments(source.substring(cursor, start)).map { chunk ->
                    CatalogMarkdownSegment(
                        text = chunk.text,
                        bold = chunk.bold,
                        italic = chunk.italic,
                    )
                }
        }

        val label = match.groupValues[1]
        val url = match.groupValues[2]
        segments +=
            parseCatalogInlineSegments(label).map { chunk ->
                CatalogMarkdownSegment(
                    text = chunk.text,
                    bold = chunk.bold,
                    italic = chunk.italic,
                    url = url,
                )
            }
        cursor = match.range.last + 1
    }

    if (cursor < source.length) {
        segments +=
            parseCatalogInlineSegments(source.substring(cursor)).map { chunk ->
                CatalogMarkdownSegment(
                    text = chunk.text,
                    bold = chunk.bold,
                    italic = chunk.italic,
                )
            }
    }

    return segments.filter { it.text.isNotEmpty() }
}

internal fun normalizeCatalogFieldReference(raw: String): String =
    raw
        .trim()
        .lowercase()
        .replace(CATALOG_NON_ALNUM_REGEX, "")

private fun cleanupCatalogInstallStepMarkdown(raw: String): String =
    raw
        .replace(CATALOG_PUNCTUATION_SPACING_REGEX, "$1")
        .replace(CATALOG_MULTIPLE_SPACES_REGEX, " ")
        .trim()

private fun buildCatalogMarkdownAnnotatedString(
    markdown: String,
    linkColor: Color,
    hoveredLinkUrl: String?,
): AnnotatedString =
    buildAnnotatedString {
        parseCatalogMarkdownSegments(markdown).forEach { segment ->
            val start = length
            append(segment.text)
            val end = length
            if (end <= start) return@forEach

            val shouldStyle = segment.bold || segment.italic || segment.url != null
            if (shouldStyle) {
                addStyle(
                    SpanStyle(
                        fontWeight = if (segment.bold) FontWeight.SemiBold else null,
                        fontStyle = if (segment.italic) FontStyle.Italic else null,
                        color = if (segment.url != null) linkColor else Color.Unspecified,
                        textDecoration =
                            if (segment.url != null && segment.url == hoveredLinkUrl) {
                                TextDecoration.Underline
                            } else {
                                null
                            },
                    ),
                    start,
                    end,
                )
            }

            val url = segment.url?.trim().orEmpty()
            if (url.isNotEmpty()) {
                addStringAnnotation(
                    tag = CATALOG_MARKDOWN_URL_TAG,
                    annotation = url,
                    start = start,
                    end = end,
                )
            }
        }
    }

private fun buildCatalogFieldReferenceIndex(fields: List<CatalogInstallField>): Map<String, String> {
    val index = linkedMapOf<String, String>()

    fun register(
        raw: String,
        fieldId: String,
    ) {
        val normalized = normalizeCatalogFieldReference(raw)
        if (normalized.isEmpty()) return
        index.putIfAbsent(normalized, fieldId)
    }

    fields.forEach { field ->
        register(field.label, field.id)
        register(field.id, field.id)
        field.id
            .split(CATALOG_NON_ALNUM_REGEX)
            .filter { token -> token.isNotBlank() }
            .forEach { token -> register(token, field.id) }
    }

    return index
}

private data class CatalogInlineSegment(
    val text: String,
    val bold: Boolean,
    val italic: Boolean,
)

private fun parseCatalogInlineSegments(text: String): List<CatalogInlineSegment> {
    if (text.isEmpty()) return emptyList()

    val segments = mutableListOf<CatalogInlineSegment>()
    var isBold = false
    var isItalic = false
    val buffer = StringBuilder()

    fun flush() {
        if (buffer.isEmpty()) return
        segments += CatalogInlineSegment(text = buffer.toString(), bold = isBold, italic = isItalic)
        buffer.clear()
    }

    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                flush()
                isBold = !isBold
                index += 2
            }

            text[index] == '*' -> {
                flush()
                isItalic = !isItalic
                index += 1
            }

            else -> {
                buffer.append(text[index])
                index += 1
            }
        }
    }
    flush()

    return segments
}

private fun String?.toUiServerIcon(): UiServerIcon {
    val url = this?.trim().orEmpty()
    return if (url.isNotEmpty()) UiServerIcon.Remote(url) else UiServerIcon.Default
}
