package io.qent.broxy.ui.adapter.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogBinaryInstallUrlResolverTest {
    @Test
    fun resolve_returns_expected_urls_for_known_binaries() {
        assertEquals("https://nodejs.org/en/download", CatalogBinaryInstallUrlResolver.resolve("npx"))
        assertEquals(
            "https://docs.astral.sh/uv/getting-started/installation/",
            CatalogBinaryInstallUrlResolver.resolve("uvx"),
        )
        assertEquals(
            "https://docs.astral.sh/uv/getting-started/installation/",
            CatalogBinaryInstallUrlResolver.resolve("uv"),
        )
        assertEquals("https://docs.docker.com/get-started/get-docker/", CatalogBinaryInstallUrlResolver.resolve("docker"))
    }

    @Test
    fun resolve_normalizes_binary_key() {
        assertEquals(
            "https://nodejs.org/en/download",
            CatalogBinaryInstallUrlResolver.resolve("  NPX  "),
        )
    }

    @Test
    fun resolve_returns_null_for_unknown_binary() {
        assertNull(CatalogBinaryInstallUrlResolver.resolve("missing-binary"))
        assertNull(CatalogBinaryInstallUrlResolver.resolve("   "))
        assertNull(CatalogBinaryInstallUrlResolver.resolve(null))
    }
}
