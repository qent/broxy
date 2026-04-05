package io.qent.broxy.ui.adapter.icons

import io.qent.broxy.ui.adapter.catalog.CatalogConnectionType
import io.qent.broxy.ui.adapter.catalog.CatalogIcon
import io.qent.broxy.ui.adapter.catalog.CatalogRepositoryMetadata
import io.qent.broxy.ui.adapter.catalog.CatalogServerDetail
import io.qent.broxy.ui.adapter.catalog.CatalogServerEntry
import io.qent.broxy.ui.adapter.catalog.CatalogServerItem
import io.qent.broxy.ui.adapter.models.UiHttpTransport
import io.qent.broxy.ui.adapter.models.UiMcpServerConfig
import io.qent.broxy.ui.adapter.models.UiServerDraft
import io.qent.broxy.ui.adapter.models.UiServerIcon
import io.qent.broxy.ui.adapter.models.UiStdioDraft
import io.qent.broxy.ui.adapter.models.UiStdioTransport
import io.qent.broxy.ui.adapter.models.UiStreamableHttpDraft
import io.qent.broxy.ui.adapter.models.UiStreamableHttpTransport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ServerIconResolverTest {
    @Test
    fun resolvesMatchedMetadataWithWebsiteAndDescription() {
        val config =
            UiMcpServerConfig(
                id = "manual-context7",
                name = "Manual Context7",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://mcp.context7.com/mcp",
                        headers = emptyMap(),
                    ),
            )
        val entries =
            listOf(
                registryEntry(
                    name = "io.qent.broxy/context7",
                    iconUrl = "https://cdn.example/context7.png",
                    description = "Context7 MCP server",
                    websiteUrl = "https://context7.com",
                    repositoryUrl = "https://github.com/upstash/context7",
                ),
            )
        val metadata = ServerIconResolver.registryMetadata(entries)

        val matched = ServerIconResolver.resolveMatchedMetadata(config, metadata)

        val resolved = assertNotNull(matched)
        assertEquals("https://context7.com", resolved.externalUrl)
        assertEquals("Context7 MCP server", resolved.description)
    }

    @Test
    fun matchedMetadataPrefersWebsiteOverRepository() {
        val config =
            UiMcpServerConfig(
                id = "manual-context7",
                name = "Manual Context7",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://mcp.context7.com/mcp",
                        headers = emptyMap(),
                    ),
            )
        val entries =
            listOf(
                registryEntry(
                    name = "io.qent.broxy/context7",
                    iconUrl = "https://cdn.example/context7.png",
                    websiteUrl = "https://context7.com",
                    repositoryUrl = "https://github.com/upstash/context7",
                ),
            )
        val metadata = ServerIconResolver.registryMetadata(entries)

        val matched = ServerIconResolver.resolveMatchedMetadata(config, metadata)

        assertEquals("https://context7.com", matched?.externalUrl)
    }

    @Test
    fun matchedMetadataFallsBackToRepositoryWhenWebsiteMissing() {
        val config =
            UiMcpServerConfig(
                id = "manual-context7",
                name = "Manual Context7",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://mcp.context7.com/mcp",
                        headers = emptyMap(),
                    ),
            )
        val entries =
            listOf(
                registryEntry(
                    name = "io.qent.broxy/context7",
                    iconUrl = "https://cdn.example/context7.png",
                    websiteUrl = "   ",
                    repositoryUrl = "https://github.com/upstash/context7",
                ),
            )
        val metadata = ServerIconResolver.registryMetadata(entries)

        val matched = ServerIconResolver.resolveMatchedMetadata(config, metadata)

        assertEquals("https://github.com/upstash/context7", matched?.externalUrl)
    }

    @Test
    fun resolvesRemoteIconByRuleAndRegistryEntry() {
        val config =
            UiMcpServerConfig(
                id = "manual-context7",
                name = "Manual Context7",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://mcp.context7.com/mcp",
                        headers = emptyMap(),
                    ),
            )
        val entries = listOf(registryEntry("io.qent.broxy/context7", "https://cdn.example/context7.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/context7.png"), icon)
    }

    @Test
    fun resolvesRuleWhenUrlHasTrailingSlash() {
        val config =
            UiMcpServerConfig(
                id = "manual-context7",
                name = "Manual Context7",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://mcp.context7.com/mcp/",
                        headers = emptyMap(),
                    ),
            )
        val entries = listOf(registryEntry("io.qent.broxy/context7", "https://cdn.example/context7.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/context7.png"), icon)
    }

    @Test
    fun resolvesJetbrainsIconBySseUrlRule() {
        val config =
            UiMcpServerConfig(
                id = "ide",
                name = "JetBrains Gateway",
                transport =
                    UiHttpTransport(
                        url = "http://localhost:64343/sse",
                        headers = emptyMap(),
                    ),
            )
        val entries = listOf(registryEntry("io.qent.broxy/jetbrains", "https://cdn.example/jetbrains.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/jetbrains.png"), icon)
    }

    @Test
    fun resolvesDraftIconUsingCatalogItemLookup() {
        val draft =
            UiServerDraft(
                id = "my-notion",
                name = "Notion",
                enabled = true,
                transport =
                    UiStreamableHttpDraft(
                        url = "https://mcp.notion.com/mcp",
                        headers = emptyMap(),
                    ),
                env = emptyMap(),
                originalId = null,
            )
        val items =
            listOf(
                CatalogServerItem(
                    id = "notion",
                    title = "Notion",
                    canonicalName = "notion",
                    canInstallWithoutInput = true,
                    description = "Official Notion MCP server.",
                    connectionTypeLabel = "HTTP",
                    capabilities = emptyList(),
                    iconUrl = "https://cdn.example/notion.png",
                    websiteUrl = null,
                    repositoryUrl = null,
                    installed = false,
                ),
            )
        val lookup = ServerIconResolver.registryIconUrlsFromItems(items)

        val icon = ServerIconResolver.resolve(draft, lookup)

        assertEquals(UiServerIcon.Remote("https://cdn.example/notion.png"), icon)
    }

    @Test
    fun fallsBackToDefaultWhenRuleMatchesButRegistryIconMissing() {
        val config =
            UiMcpServerConfig(
                id = "notion-missing",
                name = "Notion Missing",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://mcp.notion.com/mcp",
                        headers = emptyMap(),
                    ),
            )
        val entries = listOf(registryEntry("context7", "https://cdn.example/context7.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Default, icon)
    }

    @Test
    fun fallsBackToDefaultWhenNoRuleMatches() {
        val config =
            UiMcpServerConfig(
                id = "custom-server",
                name = "Custom",
                transport =
                    UiStdioTransport(
                        command = "npx",
                        args = listOf("@modelcontextprotocol/server-github"),
                    ),
            )
        val entries = listOf(registryEntry("github", "https://cdn.example/github.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Default, icon)
    }

    @Test
    fun prefersCustomIconPathOverRuleResolution() {
        val config =
            UiMcpServerConfig(
                id = "custom",
                name = "Custom",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://mcp.context7.com/mcp",
                        headers = emptyMap(),
                    ),
                iconPath = "icons/custom.png",
            )
        val entries = listOf(registryEntry("context7", "https://cdn.example/context7.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Custom("icons/custom.png"), icon)
    }

    @Test
    fun resolvesBraveRuleFromNpxStdio() {
        val config =
            UiMcpServerConfig(
                id = "brave-local",
                name = "Brave",
                transport =
                    UiStdioTransport(
                        command = "npx",
                        args = listOf("-y", "@modelcontextprotocol/server-brave-search"),
                    ),
            )
        val entries = listOf(registryEntry("brave", "https://cdn.example/brave.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/brave.png"), icon)
    }

    @Test
    fun keepsCustomDraftIconPath() {
        val draft =
            UiServerDraft(
                id = "custom-draft",
                name = "Custom Draft",
                enabled = true,
                transport = UiStdioDraft(command = "npx", args = emptyList()),
                env = emptyMap(),
                originalId = null,
                iconPath = "icons/custom-draft.png",
            )
        val items =
            listOf(
                CatalogServerItem(
                    id = "notion",
                    title = "Notion",
                    canonicalName = "notion",
                    canInstallWithoutInput = true,
                    description = "Official Notion MCP server.",
                    connectionTypeLabel = "HTTP",
                    capabilities = emptyList(),
                    iconUrl = "https://cdn.example/notion.png",
                    websiteUrl = null,
                    repositoryUrl = null,
                    installed = false,
                ),
            )
        val lookup = ServerIconResolver.registryIconUrlsFromItems(items)

        val icon = ServerIconResolver.resolve(draft, lookup)

        assertEquals(UiServerIcon.Custom("icons/custom-draft.png"), icon)
    }

    @Test
    fun resolvesAwsApiIconByStdioArgsMatcher() {
        val config =
            UiMcpServerConfig(
                id = "aws-api-local",
                name = "AWS API",
                transport =
                    UiStdioTransport(
                        command = "custom-runtime",
                        args = listOf("--from", "awslabs.aws-api-mcp-server@latest"),
                    ),
            )
        val entries = listOf(registryEntry("io.qent.broxy/aws-api", "https://cdn.example/aws-api.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/aws-api.png"), icon)
    }

    @Test
    fun resolvesSlackIconByRemoteUrlMatcher() {
        val config =
            UiMcpServerConfig(
                id = "slack-local",
                name = "Slack",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://mcp.slack.com/mcp/",
                        headers = emptyMap(),
                    ),
            )
        val entries = listOf(registryEntry("io.qent.broxy/slack", "https://cdn.example/slack.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/slack.png"), icon)
    }

    @Test
    fun resolvesSentryIconByRemoteUrlMatcher() {
        val config =
            UiMcpServerConfig(
                id = "sentry-local",
                name = "Sentry",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://mcp.sentry.dev/mcp/",
                        headers = emptyMap(),
                    ),
            )
        val entries = listOf(registryEntry("io.qent.broxy/sentry", "https://cdn.example/sentry.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/sentry.png"), icon)
    }

    @Test
    fun resolvesDeepwikiIconByRemoteUrlMatcher() {
        val config =
            UiMcpServerConfig(
                id = "deepwiki-local",
                name = "DeepWiki",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://mcp.deepwiki.com/mcp",
                        headers = emptyMap(),
                    ),
            )
        val entries = listOf(registryEntry("io.qent.broxy/deepwiki", "https://cdn.example/deepwiki.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/deepwiki.png"), icon)
    }

    @Test
    fun resolvesDesktopCommanderIconByStdioArgsMatcher() {
        val config =
            UiMcpServerConfig(
                id = "desktop-commander-local",
                name = "Desktop Commander",
                transport =
                    UiStdioTransport(
                        command = "npx",
                        args = listOf("-y", "@wonderwhy-er/desktop-commander@latest"),
                    ),
            )
        val entries =
            listOf(
                registryEntry(
                    "io.qent.broxy/desktop-commander",
                    "https://cdn.example/desktop-commander.png",
                ),
            )

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/desktop-commander.png"), icon)
    }

    @Test
    fun resolvesGitlabIconByRemoteUrlMatcher() {
        val config =
            UiMcpServerConfig(
                id = "gitlab-local",
                name = "GitLab",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://gitlab.com/api/v4/mcp",
                        headers = emptyMap(),
                    ),
            )
        val entries = listOf(registryEntry("io.qent.broxy/gitlab", "https://cdn.example/gitlab.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/gitlab.png"), icon)
    }

    @Test
    fun resolvesElasticsearchIconByAgentBuilderPathMatcher() {
        val config =
            UiMcpServerConfig(
                id = "elastic-local",
                name = "Elastic",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://kibana.example.com/s/ops/api/agent_builder/mcp",
                        headers = emptyMap(),
                    ),
            )
        val entries =
            listOf(
                registryEntry(
                    "io.qent.broxy/elasticsearch",
                    "https://cdn.example/elasticsearch.png",
                ),
            )

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/elasticsearch.png"), icon)
    }

    @Test
    fun resolvesFirebaseIconBySplitArgsMatcher() {
        val config =
            UiMcpServerConfig(
                id = "firebase-local",
                name = "Firebase",
                transport =
                    UiStdioTransport(
                        command = "npx",
                        args = listOf("-y", "firebase-tools@latest", "mcp"),
                    ),
            )
        val entries = listOf(registryEntry("io.qent.broxy/firebase", "https://cdn.example/firebase.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/firebase.png"), icon)
    }

    @Test
    fun resolvesHuggingFaceIconByRemoteUrlMatcher() {
        val config =
            UiMcpServerConfig(
                id = "hf-local",
                name = "Hugging Face",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://huggingface.co/mcp",
                        headers = emptyMap(),
                    ),
            )
        val entries = listOf(registryEntry("io.qent.broxy/huggingface", "https://cdn.example/huggingface.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/huggingface.png"), icon)
    }

    @Test
    fun resolvesMapboxIconByRemoteUrlMatcher() {
        val config =
            UiMcpServerConfig(
                id = "mapbox-local",
                name = "Mapbox",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://mcp.mapbox.com/mcp/",
                        headers = emptyMap(),
                    ),
            )
        val entries = listOf(registryEntry("io.qent.broxy/mapbox", "https://cdn.example/mapbox.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/mapbox.png"), icon)
    }

    @Test
    fun resolvesPaypalIconByRemoteUrlMatcher() {
        val config =
            UiMcpServerConfig(
                id = "paypal-local",
                name = "PayPal",
                transport =
                    UiHttpTransport(
                        url = "https://mcp.paypal.com/sse",
                        headers = emptyMap(),
                    ),
            )
        val entries = listOf(registryEntry("io.qent.broxy/paypal", "https://cdn.example/paypal.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/paypal.png"), icon)
    }

    @Test
    fun resolvesWixIconByRemoteUrlMatcher() {
        val config =
            UiMcpServerConfig(
                id = "wix-local",
                name = "Wix",
                transport =
                    UiStreamableHttpTransport(
                        url = "https://mcp.wix.com/mcp",
                        headers = emptyMap(),
                    ),
            )
        val entries = listOf(registryEntry("io.qent.broxy/wix", "https://cdn.example/wix.png"))

        val icon = ServerIconResolver.resolve(config, entries)

        assertEquals(UiServerIcon.Remote("https://cdn.example/wix.png"), icon)
    }

    @Test
    fun serverIconRulesCoverSnapshotCatalogIdsWithoutLegacyEntries() {
        val raw =
            ServerIconResolverTest::class.java
                .getResourceAsStream("/server_icons.json")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("Missing /server_icons.json test resource")
        val ruleSet = testJson.decodeFromString(ServerIconRuleSetForTest.serializer(), raw)
        val actualIds = ruleSet.rules.map { it.registryId }
        val expectedIds =
            setOf(
                "apify",
                "appium",
                "asana",
                "atlassian",
                "aws-api",
                "aws-diagram",
                "aws-documentation",
                "aws-knowledge",
                "aws-pricing",
                "azure",
                "box",
                "brave",
                "browserbase",
                "chroma-mcp",
                "chrome-devtools-mcp",
                "cloud-run",
                "context7",
                "database-toolbox",
                "deepwiki",
                "desktop-commander",
                "dropbox",
                "elasticsearch",
                "exa",
                "filesystem",
                "firebase",
                "firecrawl",
                "github",
                "gitlab",
                "grafana",
                "graphlit",
                "huggingface",
                "jetbrains",
                "linear",
                "mapbox",
                "mcp-clickhouse",
                "mcp-server-neon",
                "miro",
                "monday",
                "mongodb",
                "n8n",
                "notion",
                "paypal",
                "perplexity-ask",
                "phoenix",
                "pipedream",
                "playwright",
                "postgres-mcp-pro",
                "postman",
                "redis",
                "semgrep",
                "sentry",
                "slack",
                "sonarqube",
                "stripe",
                "supabase",
                "tavily-mcp",
                "terraform",
                "time",
                "todoist",
                "vercel",
                "wix",
                "zapier",
            )

        assertEquals(62, actualIds.size, "Unexpected rule count for 2026-04-05 snapshot + compatibility rules")
        assertEquals(actualIds.toSet().size, actualIds.size, "Duplicate registryId in server_icons.json")
        assertEquals(expectedIds, actualIds.toSet(), "Rule IDs must exactly match the snapshot registry IDs")
    }
}

private val testJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class ServerIconRuleSetForTest(
    val rules: List<ServerIconRuleForTest> = emptyList(),
)

@Serializable
private data class ServerIconRuleForTest(
    val registryId: String,
)

private fun registryEntry(
    name: String,
    iconUrl: String,
    description: String = "$name description",
    websiteUrl: String? = null,
    repositoryUrl: String? = null,
): CatalogServerEntry =
    CatalogServerEntry(
        detail =
            CatalogServerDetail(
                name = name,
                title = name.replaceFirstChar { it.titlecase() },
                description = description,
                version = "1.0.0",
                websiteUrl = websiteUrl,
                repository =
                    repositoryUrl?.let { url ->
                        CatalogRepositoryMetadata(
                            url = url,
                            source = "github",
                        )
                    },
                icons =
                    listOf(
                        CatalogIcon(
                            src = iconUrl,
                            mimeType = "image/png",
                        ),
                    ),
            ),
        connectionType = CatalogConnectionType.StreamableHttp,
        canInstallWithoutInput = true,
        connectionTypeLabel = "HTTP",
        capabilities = emptyList(),
        iconUrl = iconUrl,
    )
