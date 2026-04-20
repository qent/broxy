package io.qent.broxy.ui.adapter.data

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RepositoriesJvmTest {
    @Test
    fun providers_return_expected_runtime_objects() {
        val configurationRepository = provideConfigurationRepository()
        val logger = provideDefaultLogger()
        val cachePersistence = provideCapabilityCachePersistence(logger)
        val catalogRepository = provideCatalogRepository()
        val serverIconRepository = provideServerIconRepository()
        val hideRepository = provideImportedServerHideRepository()
        val installRepository = provideImportedServerInstallRepository()
        val uiSettingsRepository = provideUiSettingsRepository()

        assertNotNull(configurationRepository)
        assertNotNull(logger)
        assertNotNull(cachePersistence)
        assertNotNull(catalogRepository)
        assertNotNull(serverIconRepository)
        assertNotNull(hideRepository)
        assertNotNull(installRepository)
        assertNotNull(uiSettingsRepository)
    }

    @Test
    fun openExternalUrl_returns_result_without_throwing() {
        val result = openExternalUrl("not a valid url")
        assertTrue(result.isFailure)
    }
}
