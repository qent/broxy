package io.qent.broxy.registry.catalog
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogInstallPlannerTest {
    @Test
    fun buildInstallSession_includes_install_steps_from_meta() {
        val detail =
            CatalogServerDetail(
                name = "github",
                title = "GitHub",
                description = "desc",
                version = "1.0.0",
                remotes =
                    listOf(
                        CatalogRemoteTransport(
                            type = "streamable-http",
                            url = "https://example.com/mcp",
                        ),
                    ),
                meta =
                    buildJsonObject {
                        put(
                            "install_steps",
                            buildJsonArray {
                                add(JsonPrimitive("Step 1"))
                                add(JsonPrimitive(" "))
                                add(JsonPrimitive("Step 2"))
                            },
                        )
                    },
            )

        val session = CatalogInstallPlanner.buildInstallSession(detail).getOrThrow()

        assertEquals(listOf("Step 1", "Step 2"), session.installSteps)
    }

    @Test
    fun buildServerEntries_prioritizes_streamable_http_over_other_profiles() {
        val detail =
            CatalogServerDetail(
                name = "server-a",
                title = "Server A",
                description = "desc",
                version = "1.0.0",
                remotes =
                    listOf(
                        CatalogRemoteTransport(type = "sse", url = "https://example.com/sse"),
                        CatalogRemoteTransport(type = "streamable-http", url = "https://example.com/mcp"),
                    ),
                packages =
                    listOf(
                        CatalogPackage(
                            registryType = "pypi",
                            identifier = "pkg",
                            runtimeHint = "uvx",
                            transport = CatalogLocalTransport(type = "stdio"),
                        ),
                    ),
            )

        val entries = CatalogInstallPlanner.buildServerEntries(listOf(detail))

        assertEquals(1, entries.size)
        assertEquals(CatalogConnectionType.StreamableHttp, entries.first().connectionType)
        assertTrue(entries.first().canInstallWithoutInput)
        assertEquals("HTTP", entries.first().connectionTypeLabel)
        assertNull(entries.first().runtimeCommand)
        assertNull(entries.first().runtimeBinaryName)
    }

    @Test
    fun buildServerEntries_sets_runtime_command_and_binary_for_stdio_profile() {
        val detail =
            CatalogServerDetail(
                name = "time",
                title = "Time",
                description = "desc",
                version = "1.0.0",
                packages =
                    listOf(
                        CatalogPackage(
                            registryType = "pypi",
                            identifier = "mcp-server-time",
                            runtimeHint = "/opt/homebrew/bin/uvx",
                            transport = CatalogLocalTransport(type = "stdio"),
                        ),
                    ),
            )

        val entries = CatalogInstallPlanner.buildServerEntries(listOf(detail))
        val entry = entries.single()
        assertEquals("/opt/homebrew/bin/uvx", entry.runtimeCommand)
        assertEquals("uvx", entry.runtimeBinaryName)

        val items = CatalogInstallPlanner.toServerItems(entries, installedServerIds = emptySet())
        assertEquals("/opt/homebrew/bin/uvx", items.single().runtimeCommand)
        assertEquals("uvx", items.single().runtimeBinaryName)
    }

    @Test
    fun buildServerEntries_marks_canInstallWithoutInput_false_when_required_fields_have_no_defaults() {
        val detail =
            CatalogServerDetail(
                name = "secured-http",
                title = "Secured HTTP",
                description = "desc",
                version = "1.0.0",
                remotes =
                    listOf(
                        CatalogRemoteTransport(
                            type = "streamable-http",
                            url = "https://api.example.com/{workspace}",
                            variables =
                                mapOf(
                                    "workspace" to CatalogInput(isRequired = true),
                                ),
                        ),
                    ),
            )

        val entries = CatalogInstallPlanner.buildServerEntries(listOf(detail))

        assertEquals(1, entries.size)
        assertFalse(entries.first().canInstallWithoutInput)
    }

    @Test
    fun buildInstallSession_and_result_for_remote_profile_uses_template_variables() {
        val detail =
            CatalogServerDetail(
                name = "notion",
                title = "Notion",
                description = "desc",
                version = "1.0.0",
                remotes =
                    listOf(
                        CatalogRemoteTransport(
                            type = "streamable-http",
                            url = "https://api.example.com/{workspaceId}",
                            variables =
                                mapOf(
                                    "workspaceId" to
                                        CatalogInput(
                                            isRequired = true,
                                            description = "Workspace",
                                        ),
                                ),
                            headers =
                                listOf(
                                    CatalogKeyValueInput(
                                        name = "Authorization",
                                        value = "Bearer {token}",
                                        variables =
                                            mapOf(
                                                "token" to
                                                    CatalogInput(
                                                        isRequired = true,
                                                        isSecret = true,
                                                    ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )

        val session = CatalogInstallPlanner.buildInstallSession(detail).getOrThrow()
        val fieldIds = session.fields.map { it.id }.toSet()
        assertTrue("remote.url.var.workspaceid" in fieldIds)
        assertTrue("remote.headers.0.authorization.var.token" in fieldIds)

        val missing = CatalogInstallPlanner.missingRequiredFields(session, emptyMap())
        assertEquals(2, missing.size)

        val installResult =
            CatalogInstallPlanner
                .buildInstallResult(
                    session = session,
                    displayName = "Notion Prod",
                    fieldValues =
                        mapOf(
                            "remote.url.var.workspaceid" to "ws-123",
                            "remote.headers.0.authorization.var.token" to "secret-token",
                        ),
                ).getOrThrow()

        val draft = installResult.draft
        assertEquals("notion", draft.id)
        assertEquals("Notion Prod", draft.name)
        val transport = assertIs<RegistryStreamableHttpDraft>(draft.transport)
        assertEquals("https://api.example.com/ws-123", transport.url)
        assertEquals("Bearer secret-token", transport.headers["Authorization"])
    }

    @Test
    fun buildInstallResult_for_remote_profile_maps_oauth_block_to_auth_draft() {
        val detail =
            CatalogServerDetail(
                name = "slack",
                title = "Slack",
                description = "desc",
                version = "1.0.0",
                remotes =
                    listOf(
                        CatalogRemoteTransport(
                            type = "streamable-http",
                            url = "https://mcp.slack.com/mcp",
                            oauth =
                                CatalogRemoteOAuth(
                                    type = "oauth",
                                    clientId = "{slack_client_id}",
                                    clientSecret = "{slack_client_secret}",
                                    callbackPort = JsonPrimitive("{slack_callback_port}"),
                                    authServerMetadataUrl = "https://mcp.slack.com/.well-known/oauth-authorization-server",
                                    redirectUri = "https://localhost:{slack_callback_port}/callback",
                                    tokenEndpointAuthMethod = "client_secret_post",
                                    authorizationServer = "https://mcp.slack.com",
                                    allowDynamicRegistration = false,
                                ),
                            variables =
                                mapOf(
                                    "slack_client_id" to CatalogInput(isRequired = true),
                                    "slack_client_secret" to CatalogInput(isRequired = true, isSecret = true),
                                    "slack_callback_port" to CatalogInput(default = "3118"),
                                ),
                        ),
                    ),
            )

        val session = CatalogInstallPlanner.buildInstallSession(detail).getOrThrow()
        val fieldIds = session.fields.map { it.id }.toSet()
        assertTrue("remote.url.var.slack-client-id" in fieldIds)
        assertTrue("remote.url.var.slack-client-secret" in fieldIds)
        assertTrue("remote.url.var.slack-callback-port" in fieldIds)

        val installResult =
            CatalogInstallPlanner
                .buildInstallResult(
                    session = session,
                    displayName = "Slack Prod",
                    fieldValues =
                        mapOf(
                            "remote.url.var.slack-client-id" to "123.456",
                            "remote.url.var.slack-client-secret" to "super-secret",
                            "remote.url.var.slack-callback-port" to "3119",
                        ),
                ).getOrThrow()

        val auth = assertNotNull(installResult.draft.auth)
        assertEquals("123.456", auth.clientId)
        assertEquals("super-secret", auth.clientSecret)
        assertEquals(3119, auth.callbackPort)
        assertEquals("https://mcp.slack.com/.well-known/oauth-authorization-server", auth.authServerMetadataUrl)
        assertEquals("https://localhost:3119/callback", auth.redirectUri)
        assertEquals("client_secret_post", auth.tokenEndpointAuthMethod)
        assertEquals("https://mcp.slack.com", auth.authorizationServer)
        assertEquals(false, auth.allowDynamicRegistration)
    }

    @Test
    fun buildInitialFieldValues_and_requiresInstallForm_use_defaults_for_one_click_install() {
        val detail =
            CatalogServerDetail(
                name = "defaulted-http",
                title = "Defaulted HTTP",
                description = "desc",
                version = "1.0.0",
                remotes =
                    listOf(
                        CatalogRemoteTransport(
                            type = "streamable-http",
                            url = "https://api.example.com/{workspaceId}",
                            variables =
                                mapOf(
                                    "workspaceId" to
                                        CatalogInput(
                                            isRequired = true,
                                            default = "ws-default",
                                        ),
                                ),
                            headers =
                                listOf(
                                    CatalogKeyValueInput(
                                        name = "Authorization",
                                        value = "Bearer {token}",
                                        variables =
                                            mapOf(
                                                "token" to
                                                    CatalogInput(
                                                        isRequired = true,
                                                        default = "secret-default",
                                                    ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )

        val session = CatalogInstallPlanner.buildInstallSession(detail).getOrThrow()
        val initialValues = CatalogInstallPlanner.buildInitialFieldValues(session)

        assertEquals("ws-default", initialValues["remote.url.var.workspaceid"])
        assertEquals("secret-default", initialValues["remote.headers.0.authorization.var.token"])
        assertFalse(CatalogInstallPlanner.requiresInstallForm(session, initialValues))

        val installResult =
            CatalogInstallPlanner
                .buildInstallResult(
                    session = session,
                    displayName = "",
                    fieldValues = initialValues,
                ).getOrThrow()

        val transport = assertIs<RegistryStreamableHttpDraft>(installResult.draft.transport)
        assertEquals("https://api.example.com/ws-default", transport.url)
        assertEquals("Bearer secret-default", transport.headers["Authorization"])
        assertEquals("Defaulted HTTP", installResult.draft.name)
    }

    @Test
    fun buildServerEntries_marks_canInstallWithoutInput_false_when_install_steps_present() {
        val detail =
            CatalogServerDetail(
                name = "defaulted-http",
                title = "Defaulted HTTP",
                description = "desc",
                version = "1.0.0",
                remotes =
                    listOf(
                        CatalogRemoteTransport(
                            type = "streamable-http",
                            url = "https://api.example.com/{workspaceId}",
                            variables =
                                mapOf(
                                    "workspaceId" to
                                        CatalogInput(
                                            isRequired = true,
                                            default = "ws-default",
                                        ),
                                ),
                        ),
                    ),
                meta =
                    buildJsonObject {
                        put(
                            "install_steps",
                            buildJsonArray {
                                add(JsonPrimitive("Use **workspaceId**"))
                            },
                        )
                    },
            )

        val entries = CatalogInstallPlanner.buildServerEntries(listOf(detail))

        assertEquals(1, entries.size)
        assertFalse(entries.first().canInstallWithoutInput)
    }

    @Test
    fun buildInstallResult_for_stdio_package_profile_renders_command_args_and_env() {
        val detail =
            CatalogServerDetail(
                name = "time",
                title = "Time",
                description = "desc",
                version = "1.0.0",
                packages =
                    listOf(
                        CatalogPackage(
                            registryType = "pypi",
                            identifier = "mcp-server-time",
                            version = "0.6.0",
                            runtimeHint = "uvx",
                            transport = CatalogLocalTransport(type = "stdio"),
                            runtimeArguments =
                                listOf(
                                    CatalogArgument(
                                        type = "named",
                                        name = "--quiet",
                                        value = "",
                                    ),
                                ),
                            packageArguments =
                                listOf(
                                    CatalogArgument(
                                        type = "named",
                                        name = "--tz",
                                        valueHint = "timezone",
                                        isRequired = true,
                                    ),
                                ),
                            environmentVariables =
                                listOf(
                                    CatalogKeyValueInput(
                                        name = "TIME_TOKEN",
                                        isRequired = true,
                                    ),
                                ),
                        ),
                    ),
            )

        val session = CatalogInstallPlanner.buildInstallSession(detail).getOrThrow()

        val installResult =
            CatalogInstallPlanner
                .buildInstallResult(
                    session = session,
                    displayName = "",
                    fieldValues =
                        mapOf(
                            "package.packageArguments.0.tz" to "UTC",
                            "package.env.0.time-token" to "token-value",
                        ),
                ).getOrThrow()

        val draft = installResult.draft
        assertEquals("time", draft.id)
        assertEquals("Time", draft.name)
        val transport = assertIs<RegistryStdioDraft>(draft.transport)
        assertEquals("uvx", transport.command)
        assertEquals(
            listOf("--quiet", "mcp-server-time==0.6.0", "--tz", "UTC"),
            transport.args,
        )
        assertEquals("token-value", draft.env["TIME_TOKEN"])
    }
}
