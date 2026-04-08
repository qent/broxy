package io.qent.broxy.ui.screens

import io.qent.broxy.ui.adapter.catalog.CatalogConnectionType
import io.qent.broxy.ui.adapter.catalog.CatalogInstallField
import io.qent.broxy.ui.adapter.catalog.CatalogInstallSession
import io.qent.broxy.ui.adapter.catalog.CatalogServerDetail
import io.qent.broxy.ui.adapter.catalog.CatalogServerItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogScreenLogicTest {
    @Test
    fun `resolveCatalogExternalUrl prefers website over repository`() {
        val item =
            catalogItem(
                id = "ctx",
                websiteUrl = "https://context7.com",
                repositoryUrl = "https://github.com/upstash/context7",
            )

        val resolved = resolveCatalogExternalUrl(item)

        assertEquals("https://context7.com", resolved)
    }

    @Test
    fun `resolveCatalogExternalUrl falls back to repository when website is blank`() {
        val item =
            catalogItem(
                id = "ctx",
                websiteUrl = "   ",
                repositoryUrl = "https://github.com/upstash/context7",
            )

        val resolved = resolveCatalogExternalUrl(item)

        assertEquals("https://github.com/upstash/context7", resolved)
    }

    @Test
    fun `shouldRedirectToServersAfterCatalogInstall follows one-click flag`() {
        val oneClickItem = catalogItem(id = "one-click", canInstallWithoutInput = true)
        val formItem = catalogItem(id = "form", canInstallWithoutInput = false)

        assertEquals(true, shouldRedirectToServersAfterCatalogInstall(oneClickItem))
        assertEquals(false, shouldRedirectToServersAfterCatalogInstall(formItem))
    }

    @Test
    fun `filterCatalogItems matches only visible card fields`() {
        val items =
            listOf(
                catalogItem(
                    id = "alpha",
                    title = "Alpha Server",
                    canonicalName = "alpha",
                    description = "Handles alpha workflows",
                    capabilities = listOf("filesystem"),
                ),
                catalogItem(
                    id = "beta",
                    title = "Beta Server",
                    canonicalName = "beta",
                    description = "Handles beta workflows",
                    capabilities = listOf("secret-capability"),
                ),
            )

        val byCapability = filterCatalogItems(items, "secret-capability")
        val byVisibleFields = filterCatalogItems(items, "  BETA  ")

        assertEquals(emptyList(), byCapability)
        assertEquals(listOf("beta"), byVisibleFields.map { it.id })
    }

    @Test
    fun `buildCatalogRows keeps pairs and leaves trailing empty slot for odd count`() {
        val rows =
            buildCatalogRows(
                listOf(
                    catalogItem(id = "one"),
                    catalogItem(id = "two"),
                    catalogItem(id = "three"),
                ),
            )

        assertEquals(2, rows.size)
        assertEquals("one", rows[0].left.id)
        assertEquals("two", rows[0].right?.id)
        assertEquals("three", rows[1].left.id)
        assertNull(rows[1].right)
    }

    @Test
    fun `parseCatalogMarkdownSegments supports bold italic and external links`() {
        val segments =
            parseCatalogMarkdownSegments(
                "Open **GitHub** and *generate* [token](https://github.com/settings/tokens).",
            )

        assertTrue(segments.any { it.text == "GitHub" && it.bold })
        assertTrue(segments.any { it.text == "generate" && it.italic })
        assertTrue(
            segments.any {
                it.text == "token" && it.url == "https://github.com/settings/tokens"
            },
        )
    }

    @Test
    fun `buildCatalogInstallStepSpecs resolves field reference by header token`() {
        val session =
            CatalogInstallSession(
                serverId = "io.qent.broxy/github",
                defaultName = "GitHub",
                transportLabel = "HTTP",
                connectionType = CatalogConnectionType.StreamableHttp,
                detail = minimalDetail(),
                installSteps = listOf("Use created token below [Authorization]"),
                fields =
                    listOf(
                        CatalogInstallField(
                            id = "remote.headers.0.authorization.var.github-pat",
                            label = "github_pat",
                            isRequired = true,
                        ),
                    ),
            )

        val steps = buildCatalogInstallStepSpecs(session)

        assertEquals(1, steps.size)
        assertEquals(listOf("remote.headers.0.authorization.var.github-pat"), steps.first().fieldIds)
        assertEquals("Use created token below", steps.first().markdown)
    }

    @Test
    fun `buildCatalogInstallStepSpecs resolves plain field-name step and hides field text`() {
        val session =
            CatalogInstallSession(
                serverId = "io.qent.broxy/github",
                defaultName = "GitHub",
                transportLabel = "HTTP",
                connectionType = CatalogConnectionType.StreamableHttp,
                detail = minimalDetail(),
                installSteps = listOf("Authorization"),
                fields =
                    listOf(
                        CatalogInstallField(
                            id = "remote.headers.0.authorization.var.github-pat",
                            label = "github_pat",
                            isRequired = true,
                        ),
                    ),
            )

        val steps = buildCatalogInstallStepSpecs(session)

        assertEquals(1, steps.size)
        assertEquals(listOf("remote.headers.0.authorization.var.github-pat"), steps.first().fieldIds)
        assertEquals("", steps.first().markdown)
    }

    @Test
    fun `buildCatalogInstallStepSpecs appends fallback steps for missing required fields`() {
        val session =
            CatalogInstallSession(
                serverId = "io.qent.broxy/github",
                defaultName = "GitHub",
                transportLabel = "HTTP",
                connectionType = CatalogConnectionType.StreamableHttp,
                detail = minimalDetail(),
                installSteps = listOf("Open docs first"),
                fields =
                    listOf(
                        CatalogInstallField(
                            id = "remote.headers.0.authorization",
                            label = "Authorization",
                            isRequired = true,
                        ),
                    ),
            )

        val steps = buildCatalogInstallStepSpecs(session)

        assertEquals(2, steps.size)
        assertEquals("Open docs first", steps[0].markdown)
        assertEquals("Provide **Authorization**.", steps[1].markdown)
        assertEquals(listOf("remote.headers.0.authorization"), steps[1].fieldIds)
    }

    @Test
    fun `normalizeCatalogFieldReference preserves cyrillic letters`() {
        assertEquals("окружение123", normalizeCatalogFieldReference(" Окружение-123 "))
    }

    private fun catalogItem(
        id: String,
        title: String = id,
        canonicalName: String = id,
        canInstallWithoutInput: Boolean = true,
        description: String = "Description for $id",
        capabilities: List<String> = emptyList(),
        websiteUrl: String? = null,
        repositoryUrl: String? = null,
    ): CatalogServerItem =
        CatalogServerItem(
            id = id,
            title = title,
            canonicalName = canonicalName,
            canInstallWithoutInput = canInstallWithoutInput,
            description = description,
            connectionTypeLabel = "HTTP",
            capabilities = capabilities,
            iconUrl = null,
            websiteUrl = websiteUrl,
            repositoryUrl = repositoryUrl,
            installed = false,
        )

    private fun minimalDetail(): CatalogServerDetail =
        CatalogServerDetail(
            name = "io.qent.broxy/github",
            title = "GitHub",
            description = "GitHub MCP",
            version = "latest",
        )
}
