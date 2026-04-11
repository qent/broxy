package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.qent.broxy.core.presetmanagement.CreatePresetRequest
import io.qent.broxy.core.presetmanagement.CreatePresetResponse
import io.qent.broxy.core.presetmanagement.ListPresetNamesResponse
import io.qent.broxy.core.presetmanagement.ListServerNamesResponse
import io.qent.broxy.core.presetmanagement.PresetCreationAlgorithmResponse
import io.qent.broxy.core.presetmanagement.PresetDescriptionRequest
import io.qent.broxy.core.presetmanagement.PresetDescriptionResponse
import io.qent.broxy.core.presetmanagement.PresetManagementAmbiguityException
import io.qent.broxy.core.presetmanagement.PresetManagementBackend
import io.qent.broxy.core.presetmanagement.PresetManagementException
import io.qent.broxy.core.presetmanagement.PresetManagementToolNames
import io.qent.broxy.core.presetmanagement.PresetToolSelection
import io.qent.broxy.core.presetmanagement.ServerDescriptionRequest
import io.qent.broxy.core.presetmanagement.ServerDescriptionResponse
import io.qent.broxy.core.utils.LogEventBuilder
import io.qent.broxy.core.utils.LogRequestContext
import io.qent.broxy.core.utils.LogRequestType
import io.qent.broxy.core.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Suppress("TooManyFunctions", "MethodOverloading", "LongParameterList")
internal object PresetManagementToolFactory {
    fun buildManagementTools(
        backendProvider: () -> PresetManagementBackend?,
        logger: Logger,
        json: Json,
    ): List<RegisteredTool> =
        listOf(
            buildGetAlgorithmTool(backendProvider, logger, json),
            buildListServerNamesTool(backendProvider, logger, json),
            buildGetServerDescriptionTool(backendProvider, logger, json),
            buildListPresetNamesTool(backendProvider, logger, json),
            buildGetPresetDescriptionTool(backendProvider, logger, json),
            buildCreatePresetTool(backendProvider, logger, json),
        )

    private fun buildGetAlgorithmTool(
        backendProvider: () -> PresetManagementBackend?,
        logger: Logger,
        json: Json,
    ): RegisteredTool =
        RegisteredTool(
            tool =
                Tool(
                    name = PresetManagementToolNames.GET_PRESET_CREATION_ALGORITHM,
                    title = null,
                    description = "Returns the required preset-creation algorithm prompt and steps.",
                    inputSchema = ToolSchema(),
                    outputSchema = null,
                    annotations = null,
                ),
            handler = { req ->
                executeCall(
                    backendProvider = backendProvider,
                    req = req,
                    toolName = PresetManagementToolNames.GET_PRESET_CREATION_ALGORITHM,
                    logger = logger,
                    json = json,
                ) { backend ->
                    val payload = backend.getPresetCreationAlgorithm()
                    successResult(
                        text = "Returned preset creation algorithm with ${payload.steps.size} steps.",
                        structured = json.toJsonObject(payload),
                    )
                }
            },
        )

    private fun buildListServerNamesTool(
        backendProvider: () -> PresetManagementBackend?,
        logger: Logger,
        json: Json,
    ): RegisteredTool =
        RegisteredTool(
            tool =
                Tool(
                    name = PresetManagementToolNames.LIST_SERVER_NAMES,
                    title = null,
                    description = "Lists configured server ids and names. Call get_preset_creation_algorithm first.",
                    inputSchema = ToolSchema(),
                    outputSchema = null,
                    annotations = null,
                ),
            handler = { req ->
                executeCall(
                    backendProvider = backendProvider,
                    req = req,
                    toolName = PresetManagementToolNames.LIST_SERVER_NAMES,
                    logger = logger,
                    json = json,
                ) { backend ->
                    val payload = backend.listServerNames()
                    successResult(
                        text = "Found ${payload.servers.size} configured server(s).",
                        structured = json.toJsonObject(payload),
                    )
                }
            },
        )

    private fun buildGetServerDescriptionTool(
        backendProvider: () -> PresetManagementBackend?,
        logger: Logger,
        json: Json,
    ): RegisteredTool =
        RegisteredTool(
            tool =
                Tool(
                    name = PresetManagementToolNames.GET_SERVER_DESCRIPTION,
                    title = null,
                    description =
                        "Describes a server and capabilities by server_name with optional server_id. " +
                            "Call get_preset_creation_algorithm first.",
                    inputSchema = getServerDescriptionSchema(),
                    outputSchema = null,
                    annotations = null,
                ),
            handler = { req ->
                executeCall(
                    backendProvider = backendProvider,
                    req = req,
                    toolName = PresetManagementToolNames.GET_SERVER_DESCRIPTION,
                    logger = logger,
                    json = json,
                ) { backend ->
                    val request = parseServerDescriptionRequest(req.arguments ?: JsonObject(emptyMap()))
                    val payload = backend.getServerDescription(request)
                    successResult(text = payload.description, structured = json.toJsonObject(payload))
                }
            },
        )

    private fun buildListPresetNamesTool(
        backendProvider: () -> PresetManagementBackend?,
        logger: Logger,
        json: Json,
    ): RegisteredTool =
        RegisteredTool(
            tool =
                Tool(
                    name = PresetManagementToolNames.LIST_PRESET_NAMES,
                    title = null,
                    description =
                        "Lists built-in and saved preset ids/names. " +
                            "Call get_preset_creation_algorithm first.",
                    inputSchema = ToolSchema(),
                    outputSchema = null,
                    annotations = null,
                ),
            handler = { req ->
                executeCall(
                    backendProvider = backendProvider,
                    req = req,
                    toolName = PresetManagementToolNames.LIST_PRESET_NAMES,
                    logger = logger,
                    json = json,
                ) { backend ->
                    val payload = backend.listPresetNames()
                    successResult(
                        text = "Found ${payload.presets.size} preset(s).",
                        structured = json.toJsonObject(payload),
                    )
                }
            },
        )

    private fun buildGetPresetDescriptionTool(
        backendProvider: () -> PresetManagementBackend?,
        logger: Logger,
        json: Json,
    ): RegisteredTool =
        RegisteredTool(
            tool =
                Tool(
                    name = PresetManagementToolNames.GET_PRESET_DESCRIPTION,
                    title = null,
                    description =
                        "Describes effective preset capabilities by preset_name with optional preset_id. " +
                            "Call get_preset_creation_algorithm first.",
                    inputSchema = getPresetDescriptionSchema(),
                    outputSchema = null,
                    annotations = null,
                ),
            handler = { req ->
                executeCall(
                    backendProvider = backendProvider,
                    req = req,
                    toolName = PresetManagementToolNames.GET_PRESET_DESCRIPTION,
                    logger = logger,
                    json = json,
                ) { backend ->
                    val request = parsePresetDescriptionRequest(req.arguments ?: JsonObject(emptyMap()))
                    val payload = backend.getPresetDescription(request)
                    successResult(text = payload.description, structured = json.toJsonObject(payload))
                }
            },
        )

    private fun buildCreatePresetTool(
        backendProvider: () -> PresetManagementBackend?,
        logger: Logger,
        json: Json,
    ): RegisteredTool =
        RegisteredTool(
            tool =
                Tool(
                    name = PresetManagementToolNames.CREATE_PRESET,
                    title = null,
                    description =
                        "Creates a new preset with explicit preset_id and preset_name. " +
                            "Call get_preset_creation_algorithm first.",
                    inputSchema = createPresetSchema(),
                    outputSchema = null,
                    annotations = null,
                ),
            handler = { req ->
                executeCall(
                    backendProvider = backendProvider,
                    req = req,
                    toolName = PresetManagementToolNames.CREATE_PRESET,
                    logger = logger,
                    json = json,
                ) { backend ->
                    val request = parseCreatePresetRequest(req.arguments ?: JsonObject(emptyMap()))
                    val payload = backend.createPreset(request)
                    successResult(
                        text = "Created preset '${payload.presetName}' (id='${payload.presetId}').",
                        structured = json.toJsonObject(payload),
                    )
                }
            },
        )

    private suspend fun executeCall(
        backendProvider: () -> PresetManagementBackend?,
        req: CallToolRequest,
        toolName: String,
        logger: Logger,
        json: Json,
        call: suspend (PresetManagementBackend) -> CallToolResult,
    ): CallToolResult {
        val requestContext = LogRequestContext(logger, LogRequestType.TOOL, toolName)
        LogEventBuilder.llmToFacadeRequest(
            request = requestContext,
            arguments = req.arguments,
            meta = req.meta?.json,
        )
        val backend = backendProvider()
        if (backend == null) {
            return errorResult(
                message = "Preset management backend is unavailable.",
                requestContext = requestContext,
                json = json,
            )
        }
        return runCatching { call(backend) }
            .getOrElse { error ->
                errorResult(
                    message = error.message ?: "Preset management error",
                    requestContext = requestContext,
                    json = json,
                    error = error,
                )
            }
    }

    private fun parseServerDescriptionRequest(arguments: JsonObject): ServerDescriptionRequest =
        ServerDescriptionRequest(
            serverName = arguments.requiredString("server_name"),
            serverId = arguments.optionalString("server_id"),
        )

    private fun parsePresetDescriptionRequest(arguments: JsonObject): PresetDescriptionRequest =
        PresetDescriptionRequest(
            presetName = arguments.requiredString("preset_name"),
            presetId = arguments.optionalString("preset_id"),
        )

    private fun parseCreatePresetRequest(arguments: JsonObject): CreatePresetRequest {
        val toolsArray = arguments["tools"]?.jsonArray ?: throw PresetManagementException("tools must be an array")
        val tools =
            toolsArray.map { element ->
                val obj = element.jsonObject
                PresetToolSelection(
                    serverId = obj.requiredString("server_id"),
                    toolName = obj.requiredString("tool_name"),
                )
            }
        return CreatePresetRequest(
            presetId = arguments.requiredString("preset_id"),
            presetName = arguments.requiredString("preset_name"),
            tools = tools,
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name) ?: throw PresetManagementException("$name is required")

    private fun JsonObject.optionalString(name: String): String? =
        (this[name] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun getServerDescriptionSchema(): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(
                        "server_name",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Server name to inspect."))
                        },
                    )
                    put(
                        "server_id",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Optional server id to resolve name ambiguity."))
                        },
                    )
                },
            required = listOf("server_name"),
        )

    private fun getPresetDescriptionSchema(): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(
                        "preset_name",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Preset name to inspect."))
                        },
                    )
                    put(
                        "preset_id",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Optional preset id to resolve name ambiguity."))
                        },
                    )
                },
            required = listOf("preset_name"),
        )

    private fun createPresetSchema(): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(
                        "preset_id",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("New preset id (required, explicit, path-safe)."))
                        },
                    )
                    put(
                        "preset_name",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("New preset display name (required)."))
                        },
                    )
                    put(
                        "tools",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive("Non-empty list of tool references to include in the new preset."),
                            )
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", JsonPrimitive("object"))
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("server_id", buildJsonObject { put("type", JsonPrimitive("string")) })
                                            put("tool_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                                        },
                                    )
                                    put(
                                        "required",
                                        buildJsonArray {
                                            add(JsonPrimitive("server_id"))
                                            add(JsonPrimitive("tool_name"))
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            required = listOf("preset_id", "preset_name", "tools"),
        )

    private fun successResult(
        text: String,
        structured: JsonObject,
    ): CallToolResult =
        CallToolResult(
            content = listOf(TextContent(text = text)),
            isError = false,
            structuredContent = structured,
            meta = JsonObject(emptyMap()),
        )

    private fun errorResult(
        message: String,
        requestContext: LogRequestContext,
        json: Json,
        error: Throwable? = null,
    ): CallToolResult {
        val structured =
            when (error) {
                is PresetManagementAmbiguityException ->
                    buildJsonObject {
                        put("error", JsonPrimitive(message))
                        put(
                            "candidates",
                            JsonArray(
                                error.candidates.map { candidate ->
                                    buildJsonObject {
                                        put("id", JsonPrimitive(candidate.id))
                                        put("name", JsonPrimitive(candidate.name))
                                    }
                                },
                            ),
                        )
                    }

                else -> buildJsonObject { put("error", JsonPrimitive(message)) }
            }
        LogEventBuilder.facadeToLlmError(
            request = requestContext,
            errorMessage = message,
            failure = error,
        )
        val result =
            CallToolResult(
                content = listOf(TextContent(text = message)),
                isError = true,
                structuredContent = structured,
                meta = JsonObject(emptyMap()),
            )
        LogEventBuilder.facadeToLlmResponse(
            request = requestContext,
            response = json.encodeToJsonElement(CallToolResult.serializer(), result),
        )
        return result
    }

    private fun Json.toJsonObject(payload: PresetCreationAlgorithmResponse): JsonObject =
        encodeToJsonElement(PresetCreationAlgorithmResponse.serializer(), payload).jsonObject

    private fun Json.toJsonObject(payload: ListServerNamesResponse): JsonObject =
        encodeToJsonElement(ListServerNamesResponse.serializer(), payload).jsonObject

    private fun Json.toJsonObject(payload: ServerDescriptionResponse): JsonObject =
        encodeToJsonElement(ServerDescriptionResponse.serializer(), payload).jsonObject

    private fun Json.toJsonObject(payload: ListPresetNamesResponse): JsonObject =
        encodeToJsonElement(ListPresetNamesResponse.serializer(), payload).jsonObject

    private fun Json.toJsonObject(payload: PresetDescriptionResponse): JsonObject =
        encodeToJsonElement(PresetDescriptionResponse.serializer(), payload).jsonObject

    private fun Json.toJsonObject(payload: CreatePresetResponse): JsonObject =
        encodeToJsonElement(CreatePresetResponse.serializer(), payload).jsonObject
}
