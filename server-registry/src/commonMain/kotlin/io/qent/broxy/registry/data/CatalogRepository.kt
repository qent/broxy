package io.qent.broxy.registry.data

import io.qent.broxy.registry.catalog.CatalogBundle

interface CatalogRepository {
    suspend fun loadCatalog(): Result<CatalogBundle>

    suspend fun refreshCatalog(): Result<CatalogBundle?>

    companion object {
        val Noop: CatalogRepository =
            object : CatalogRepository {
                override suspend fun loadCatalog(): Result<CatalogBundle> = Result.success(CatalogBundle())

                override suspend fun refreshCatalog(): Result<CatalogBundle?> = Result.success(null)
            }
    }
}
