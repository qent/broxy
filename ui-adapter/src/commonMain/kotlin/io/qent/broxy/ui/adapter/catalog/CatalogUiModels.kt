package io.qent.broxy.ui.adapter.catalog

import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.registry.catalog.CatalogConnectionType as RegistryCatalogConnectionType
import io.qent.broxy.registry.catalog.CatalogFieldFormat as RegistryCatalogFieldFormat
import io.qent.broxy.registry.catalog.CatalogInstallField as RegistryCatalogInstallField
import io.qent.broxy.registry.catalog.CatalogInstallSession as RegistryCatalogInstallSession
import io.qent.broxy.registry.catalog.CatalogServerEntry as RegistryCatalogServerEntry
import io.qent.broxy.registry.catalog.CatalogServerItem as RegistryCatalogServerItem

typealias CatalogConnectionType = RegistryCatalogConnectionType
typealias CatalogFieldFormat = RegistryCatalogFieldFormat
typealias CatalogServerEntry = RegistryCatalogServerEntry
typealias CatalogServerItem = RegistryCatalogServerItem
typealias CatalogInstallField = RegistryCatalogInstallField
typealias CatalogInstallSession = RegistryCatalogInstallSession

data class CatalogInstallResult(
    val draft: UiServerDraft,
)
