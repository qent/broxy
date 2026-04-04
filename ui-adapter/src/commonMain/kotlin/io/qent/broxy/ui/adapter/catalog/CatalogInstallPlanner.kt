package io.qent.broxy.ui.adapter.catalog

import io.qent.broxy.registry.catalog.RegistryHttpDraft
import io.qent.broxy.registry.catalog.RegistryOAuthDraft
import io.qent.broxy.registry.catalog.RegistryServerDraft
import io.qent.broxy.registry.catalog.RegistryStdioDraft
import io.qent.broxy.registry.catalog.RegistryStreamableHttpDraft
import io.qent.broxy.registry.catalog.RegistryTransportDraft
import io.qent.broxy.ui.adapter.models.UiAuthConfig
import io.qent.broxy.ui.adapter.models.UiHttpDraft
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.registry.catalog.CatalogInstallPlanner as RegistryCatalogInstallPlanner

object CatalogInstallPlanner {
    fun buildServerEntries(servers: List<CatalogServerDetail>): List<CatalogServerEntry> =
        RegistryCatalogInstallPlanner
            .buildServerEntries(servers)

    fun toServerItems(
        entries: List<CatalogServerEntry>,
        installedServerIds: Set<String>,
    ): List<CatalogServerItem> =
        RegistryCatalogInstallPlanner.toServerItems(
            entries = entries,
            installedServerIds = installedServerIds,
        )

    fun buildInstallSession(detail: CatalogServerDetail): Result<CatalogInstallSession> =
        RegistryCatalogInstallPlanner
            .buildInstallSession(detail)

    fun missingRequiredFields(
        session: CatalogInstallSession,
        fieldValues: Map<String, String>,
    ): List<CatalogInstallField> =
        RegistryCatalogInstallPlanner.missingRequiredFields(
            session = session,
            fieldValues = fieldValues,
        )

    fun buildInitialFieldValues(session: CatalogInstallSession): Map<String, String> =
        RegistryCatalogInstallPlanner
            .buildInitialFieldValues(session)

    fun requiresInstallForm(
        session: CatalogInstallSession,
        fieldValues: Map<String, String> = buildInitialFieldValues(session),
    ): Boolean =
        RegistryCatalogInstallPlanner.requiresInstallForm(
            session = session,
            fieldValues = fieldValues,
        )

    fun buildInstallResult(
        session: CatalogInstallSession,
        displayName: String,
        fieldValues: Map<String, String>,
    ): Result<CatalogInstallResult> =
        RegistryCatalogInstallPlanner
            .buildInstallResult(
                session = session,
                displayName = displayName,
                fieldValues = fieldValues,
            ).map { result ->
                CatalogInstallResult(draft = result.draft.toUiDraft())
            }
}

private fun RegistryServerDraft.toUiDraft(): UiServerDraft =
    UiServerDraft(
        id = id,
        name = name,
        enabled = enabled,
        transport = transport.toUiTransportDraft(),
        env = env,
        auth = auth?.toUiAuth(),
        originalId = null,
        iconPath = null,
    )

private fun RegistryTransportDraft.toUiTransportDraft() =
    when (this) {
        is RegistryStreamableHttpDraft -> UiStreamableHttpDraft(url = url, headers = headers)
        is RegistryHttpDraft -> UiHttpDraft(url = url, headers = headers)
        is RegistryStdioDraft -> UiStdioDraft(command = command, args = args)
    }

private fun RegistryOAuthDraft.toUiAuth(): UiAuthConfig.OAuth =
    UiAuthConfig.OAuth(
        clientId = clientId,
        clientSecret = clientSecret,
        callbackPort = callbackPort,
        clientIdMetadataUrl = clientIdMetadataUrl,
        authServerMetadataUrl = authServerMetadataUrl,
        redirectUri = redirectUri,
        clientName = clientName,
        tokenEndpointAuthMethod = tokenEndpointAuthMethod,
        authorizationServer = authorizationServer,
        scopes = scopes,
        allowDynamicRegistration = allowDynamicRegistration,
    )
