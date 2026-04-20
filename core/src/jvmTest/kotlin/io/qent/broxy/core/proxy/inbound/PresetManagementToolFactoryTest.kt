package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.qent.broxy.core.presetmanagement.CatalogServerInstallState
import io.qent.broxy.core.presetmanagement.CatalogServerInstallStatusResponse
import io.qent.broxy.core.presetmanagement.CreatePresetRequest
import io.qent.broxy.core.presetmanagement.CreatePresetResponse
import io.qent.broxy.core.presetmanagement.GetCatalogServerInstallStatusRequest
import io.qent.broxy.core.presetmanagement.InstallCatalogServerRequest
import io.qent.broxy.core.presetmanagement.InstallCatalogServerResponse
import io.qent.broxy.core.presetmanagement.ListCatalogServerNamesResponse
import io.qent.broxy.core.presetmanagement.ListPresetNamesResponse
import io.qent.broxy.core.presetmanagement.ListServerNamesResponse
import io.qent.broxy.core.presetmanagement.NamedPresetManagementItem
import io.qent.broxy.core.presetmanagement.PresetCreationAlgorithmResponse
import io.qent.broxy.core.presetmanagement.PresetDescriptionRequest
import io.qent.broxy.core.presetmanagement.PresetDescriptionResponse
import io.qent.broxy.core.presetmanagement.PresetManagementAmbiguityException
import io.qent.broxy.core.presetmanagement.PresetManagementBackend
import io.qent.broxy.core.presetmanagement.PresetManagementToolNames
import io.qent.broxy.core.presetmanagement.ServerDescriptionRequest
import io.qent.broxy.core.presetmanagement.ServerDescriptionResponse
import io.qent.broxy.core.presetmanagement.SetServerEnabledRequest
import io.qent.broxy.core.presetmanagement.SetServerEnabledResponse
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.mockito.kotlin.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetManagementToolFactoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun buildManagementTools_registers_exact_six_tools_and_algorithm_notes() {
        val tools =
            PresetManagementToolFactory.buildManagementTools(
                backendProvider = { SuccessBackend() },
                logger = NoopLogger,
                json = json,
            )

        assertEquals(PresetManagementToolNames.all, tools.map { it.tool.name })
        tools
            .filter { it.tool.name != PresetManagementToolNames.GET_PRESET_CREATION_ALGORITHM }
            .forEach { registered ->
                val description = registered.tool.description.orEmpty()
                assertTrue(description.contains("Call get_preset_creation_algorithm first."))
            }
    }

    @Test
    fun buildManagementTools_defines_output_schema_for_each_tool() {
        val tools =
            PresetManagementToolFactory.buildManagementTools(
                backendProvider = { SuccessBackend() },
                logger = NoopLogger,
                json = json,
            )

        val requiredByTool =
            mapOf(
                PresetManagementToolNames.GET_PRESET_CREATION_ALGORITHM to setOf("prompt", "steps"),
                PresetManagementToolNames.LIST_SERVER_NAMES to setOf("servers"),
                PresetManagementToolNames.GET_SERVER_DESCRIPTION to
                    setOf(
                        "server_id",
                        "server_name",
                        "description",
                        "capabilities_source",
                        "tools",
                        "prompts",
                        "resources",
                    ),
                PresetManagementToolNames.LIST_PRESET_NAMES to setOf("presets"),
                PresetManagementToolNames.GET_PRESET_DESCRIPTION to
                    setOf(
                        "preset_id",
                        "preset_name",
                        "description",
                        "tools",
                        "prompts",
                        "resources",
                        "missing_capabilities",
                    ),
                PresetManagementToolNames.CREATE_PRESET to setOf("preset_id", "preset_name"),
            )

        requiredByTool.forEach { (toolName, expectedRequired) ->
            val schema = assertNotNull(tools.first { it.tool.name == toolName }.tool.outputSchema)
            assertTrue(schema.requiredFields().containsAll(expectedRequired))
        }
    }

    @Test
    fun buildManagementTools_includes_agentic_tools_when_enabled() {
        val tools =
            PresetManagementToolFactory.buildManagementTools(
                backendProvider = { AgenticBackend() },
                logger = NoopLogger,
                json = json,
            )

        assertEquals(PresetManagementToolNames.allWithAgentic.toSet(), tools.map { it.tool.name }.toSet())
    }

    @Test
    fun list_server_names_returns_text_and_structured_payload() {
        val tools =
            PresetManagementToolFactory.buildManagementTools(
                backendProvider = { SuccessBackend() },
                logger = NoopLogger,
                json = json,
            )
        val listTool = tools.first { it.tool.name == PresetManagementToolNames.LIST_SERVER_NAMES }

        val result = invoke(listTool, PresetManagementToolNames.LIST_SERVER_NAMES)

        assertEquals(false, result.isError)
        val text = assertIs<TextContent>(result.content.first()).text
        assertTrue(text.contains("configured server"))
        val structured = assertNotNull(result.structuredContent)
        assertNotNull(structured.jsonObject["servers"])
    }

    @Test
    fun ambiguity_errors_include_candidates_in_text_and_no_structured_payload() {
        val tools =
            PresetManagementToolFactory.buildManagementTools(
                backendProvider = { AmbiguousBackend() },
                logger = NoopLogger,
                json = json,
            )
        val getServerTool = tools.first { it.tool.name == PresetManagementToolNames.GET_SERVER_DESCRIPTION }

        val result =
            invoke(
                getServerTool,
                PresetManagementToolNames.GET_SERVER_DESCRIPTION,
                buildJsonObject { put("server_name", JsonPrimitive("Alpha")) },
            )

        assertEquals(true, result.isError)
        assertNull(result.structuredContent)
        val text = assertIs<TextContent>(result.content.first()).text
        assertTrue(text.contains("candidates"))
        assertTrue(text.contains("s1"))
        assertTrue(text.contains("Alpha"))
    }

    @Test
    fun unavailable_backend_returns_error_without_structured_payload() {
        val tools =
            PresetManagementToolFactory.buildManagementTools(
                backendProvider = { null },
                logger = NoopLogger,
                json = json,
            )
        val listTool = tools.first { it.tool.name == PresetManagementToolNames.LIST_SERVER_NAMES }

        val result = invoke(listTool, PresetManagementToolNames.LIST_SERVER_NAMES)

        assertEquals(true, result.isError)
        assertNull(result.structuredContent)
        val text = assertIs<TextContent>(result.content.first()).text
        assertTrue(text.contains("backend is unavailable"))
    }

    private fun invoke(
        tool: RegisteredTool,
        name: String,
        arguments: JsonObject = EmptyJsonObject,
    ): CallToolResult =
        runBlocking {
            val clientConnection: ClientConnection = mock()
            tool.handler(
                clientConnection,
                CallToolRequest(
                    CallToolRequestParams(
                        name = name,
                        arguments = arguments,
                        meta = null,
                    ),
                ),
            )
        }

    private open class SuccessBackend : PresetManagementBackend {
        override suspend fun getPresetCreationAlgorithm(): PresetCreationAlgorithmResponse =
            PresetCreationAlgorithmResponse(
                prompt = "prompt",
                steps = listOf("one", "two"),
            )

        override suspend fun listServerNames(): ListServerNamesResponse =
            ListServerNamesResponse(
                servers = listOf(NamedPresetManagementItem(id = "s1", name = "Server 1")),
            )

        override suspend fun getServerDescription(request: ServerDescriptionRequest): ServerDescriptionResponse =
            ServerDescriptionResponse(
                serverId = "s1",
                serverName = request.serverName,
                description = "Server description",
                capabilitiesSource = io.qent.broxy.core.presetmanagement.CapabilitySourceStatus.Live,
            )

        override suspend fun listPresetNames(): ListPresetNamesResponse =
            ListPresetNamesResponse(
                presets = listOf(NamedPresetManagementItem(id = "p1", name = "Preset 1")),
            )

        override suspend fun getPresetDescription(request: PresetDescriptionRequest): PresetDescriptionResponse =
            PresetDescriptionResponse(
                presetId = "p1",
                presetName = request.presetName,
                description = "Preset description",
            )

        override suspend fun createPreset(request: CreatePresetRequest): CreatePresetResponse =
            CreatePresetResponse(
                presetId = request.presetId,
                presetName = request.presetName,
            )
    }

    private class AgenticBackend : SuccessBackend() {
        override val agenticModeEnabled: Boolean = true

        override suspend fun listCatalogServerNames(): ListCatalogServerNamesResponse =
            ListCatalogServerNamesResponse(
                servers = listOf(NamedPresetManagementItem(id = "catalog-1", name = "Catalog 1")),
            )

        override suspend fun installCatalogServer(request: InstallCatalogServerRequest): InstallCatalogServerResponse =
            InstallCatalogServerResponse(
                serverId = request.serverId,
                state = CatalogServerInstallState.Installing,
                message = "started",
            )

        override suspend fun getCatalogServerInstallStatus(
            request: GetCatalogServerInstallStatusRequest,
        ): CatalogServerInstallStatusResponse =
            CatalogServerInstallStatusResponse(
                serverId = request.serverId,
                state = CatalogServerInstallState.Installed,
                installed = true,
                ready = false,
            )

        override suspend fun setServerEnabled(request: SetServerEnabledRequest): SetServerEnabledResponse =
            SetServerEnabledResponse(
                serverId = request.serverId,
                enabled = request.enabled,
            )
    }

    private class AmbiguousBackend : SuccessBackend() {
        override suspend fun getServerDescription(request: ServerDescriptionRequest): ServerDescriptionResponse =
            throw PresetManagementAmbiguityException(
                message = "ambiguous",
                candidates =
                    listOf(
                        NamedPresetManagementItem(id = "s1", name = "Alpha"),
                        NamedPresetManagementItem(id = "s2", name = "Alpha"),
                    ),
            )
    }

    private object NoopLogger : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
}

private fun ToolSchema.requiredFields(): Set<String> = required.orEmpty().toSet()
