package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.qent.broxy.core.presetmanagement.CreatePresetResponse
import io.qent.broxy.core.presetmanagement.ListPresetNamesResponse
import io.qent.broxy.core.presetmanagement.ListServerNamesResponse
import io.qent.broxy.core.presetmanagement.PresetCreationAlgorithmResponse
import io.qent.broxy.core.presetmanagement.PresetDescriptionResponse
import io.qent.broxy.core.presetmanagement.PresetManagementBackend
import io.qent.broxy.core.presetmanagement.PresetManagementToolNames
import io.qent.broxy.core.presetmanagement.ServerDescriptionResponse
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal object PresetManagementToolFactory {
    fun buildManagementTools(
        backendProvider: () -> PresetManagementBackend?,
        logger: Logger,
        json: Json,
    ): List<RegisteredTool> {
        val context =
            PresetToolFactoryContext(
                backendProvider = backendProvider,
                logger = logger,
                json = json,
            )
        return listOf(
            buildGetAlgorithmTool(context),
            buildListServerNamesTool(context),
            buildGetServerDescriptionTool(context),
            buildListPresetNamesTool(context),
            buildGetPresetDescriptionTool(context),
            buildCreatePresetTool(context),
        )
    }

    private fun buildGetAlgorithmTool(context: PresetToolFactoryContext): RegisteredTool =
        registerTool(
            context = context,
            definition =
                PresetManagementToolDefinition(
                    name = PresetManagementToolNames.GET_PRESET_CREATION_ALGORITHM,
                    description = GET_PRESET_CREATION_ALGORITHM_DESCRIPTION,
                    inputSchema = ToolSchema(),
                    outputSchema = GET_PRESET_CREATION_ALGORITHM_OUTPUT_SCHEMA,
                ),
        ) { backend, _ ->
            val payload = backend.getPresetCreationAlgorithm()
            successResult(
                text = "Returned preset creation algorithm with ${payload.steps.size} steps.",
                structured =
                    context.json.encodeStructuredPayload(
                        PresetCreationAlgorithmResponse.serializer(),
                        payload,
                    ),
            )
        }

    private fun buildListServerNamesTool(context: PresetToolFactoryContext): RegisteredTool =
        registerTool(
            context = context,
            definition =
                PresetManagementToolDefinition(
                    name = PresetManagementToolNames.LIST_SERVER_NAMES,
                    description = LIST_SERVER_NAMES_DESCRIPTION,
                    inputSchema = ToolSchema(),
                    outputSchema = LIST_SERVER_NAMES_OUTPUT_SCHEMA,
                ),
        ) { backend, _ ->
            val payload = backend.listServerNames()
            successResult(
                text = "Found ${payload.servers.size} configured server(s).",
                structured = context.json.encodeStructuredPayload(ListServerNamesResponse.serializer(), payload),
            )
        }

    private fun buildGetServerDescriptionTool(context: PresetToolFactoryContext): RegisteredTool =
        registerTool(
            context = context,
            definition =
                PresetManagementToolDefinition(
                    name = PresetManagementToolNames.GET_SERVER_DESCRIPTION,
                    description = GET_SERVER_DESCRIPTION_DESCRIPTION,
                    inputSchema = GET_SERVER_DESCRIPTION_INPUT_SCHEMA,
                    outputSchema = GET_SERVER_DESCRIPTION_OUTPUT_SCHEMA,
                ),
        ) { backend, arguments ->
            val request = decodeServerDescriptionRequest(context.json, arguments)
            val payload = backend.getServerDescription(request)
            successResult(
                text = payload.description,
                structured = context.json.encodeStructuredPayload(ServerDescriptionResponse.serializer(), payload),
            )
        }

    private fun buildListPresetNamesTool(context: PresetToolFactoryContext): RegisteredTool =
        registerTool(
            context = context,
            definition =
                PresetManagementToolDefinition(
                    name = PresetManagementToolNames.LIST_PRESET_NAMES,
                    description = LIST_PRESET_NAMES_DESCRIPTION,
                    inputSchema = ToolSchema(),
                    outputSchema = LIST_PRESET_NAMES_OUTPUT_SCHEMA,
                ),
        ) { backend, _ ->
            val payload = backend.listPresetNames()
            successResult(
                text = "Found ${payload.presets.size} preset(s).",
                structured = context.json.encodeStructuredPayload(ListPresetNamesResponse.serializer(), payload),
            )
        }

    private fun buildGetPresetDescriptionTool(context: PresetToolFactoryContext): RegisteredTool =
        registerTool(
            context = context,
            definition =
                PresetManagementToolDefinition(
                    name = PresetManagementToolNames.GET_PRESET_DESCRIPTION,
                    description = GET_PRESET_DESCRIPTION_DESCRIPTION,
                    inputSchema = GET_PRESET_DESCRIPTION_INPUT_SCHEMA,
                    outputSchema = GET_PRESET_DESCRIPTION_OUTPUT_SCHEMA,
                ),
        ) { backend, arguments ->
            val request = decodePresetDescriptionRequest(context.json, arguments)
            val payload = backend.getPresetDescription(request)
            successResult(
                text = payload.description,
                structured = context.json.encodeStructuredPayload(PresetDescriptionResponse.serializer(), payload),
            )
        }

    private fun buildCreatePresetTool(context: PresetToolFactoryContext): RegisteredTool =
        registerTool(
            context = context,
            definition =
                PresetManagementToolDefinition(
                    name = PresetManagementToolNames.CREATE_PRESET,
                    description = CREATE_PRESET_DESCRIPTION,
                    inputSchema = CREATE_PRESET_INPUT_SCHEMA,
                    outputSchema = CREATE_PRESET_OUTPUT_SCHEMA,
                ),
        ) { backend, arguments ->
            val request = decodeCreatePresetRequest(context.json, arguments)
            val payload = backend.createPreset(request)
            successResult(
                text = "Created preset '${payload.presetName}' (id='${payload.presetId}').",
                structured = context.json.encodeStructuredPayload(CreatePresetResponse.serializer(), payload),
            )
        }

    private fun registerTool(
        context: PresetToolFactoryContext,
        definition: PresetManagementToolDefinition,
        handler: suspend (backend: PresetManagementBackend, arguments: JsonObject) -> CallToolResult,
    ): RegisteredTool =
        RegisteredTool(
            tool =
                Tool(
                    name = definition.name,
                    title = null,
                    description = definition.description,
                    inputSchema = definition.inputSchema,
                    outputSchema = definition.outputSchema,
                    annotations = null,
                ),
            handler = { req ->
                executePresetManagementToolCall(
                    context = context,
                    req = req,
                    toolName = definition.name,
                ) { backend ->
                    val arguments = req.arguments ?: EmptyJsonObject
                    handler(backend, arguments)
                }
            },
        )
}

internal data class PresetToolFactoryContext(
    val backendProvider: () -> PresetManagementBackend?,
    val logger: Logger,
    val json: Json,
)

private data class PresetManagementToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: ToolSchema,
    val outputSchema: ToolSchema,
)
