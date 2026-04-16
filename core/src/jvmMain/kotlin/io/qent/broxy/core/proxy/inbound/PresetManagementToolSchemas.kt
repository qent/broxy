package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal const val ARG_SERVER_NAME = "server_name"
internal const val ARG_SERVER_ID = "server_id"
internal const val ARG_PRESET_NAME = "preset_name"
internal const val ARG_PRESET_ID = "preset_id"
internal const val ARG_TOOLS = "tools"
internal const val ARG_TOOL_NAME = "tool_name"

internal const val FIELD_ERROR = "error"
internal const val FIELD_CANDIDATES = "candidates"

private const val CALL_GET_PRESET_CREATION_ALGORITHM_FIRST = "Call get_preset_creation_algorithm first."

internal const val GET_PRESET_CREATION_ALGORITHM_DESCRIPTION =
    "Returns the required preset-creation algorithm prompt and steps."
internal const val LIST_SERVER_NAMES_DESCRIPTION =
    "Lists configured server ids and names. $CALL_GET_PRESET_CREATION_ALGORITHM_FIRST"
internal const val GET_SERVER_DESCRIPTION_DESCRIPTION =
    "Describes a server and capabilities by server_name with optional server_id. " +
        CALL_GET_PRESET_CREATION_ALGORITHM_FIRST
internal const val LIST_PRESET_NAMES_DESCRIPTION =
    "Lists built-in and saved preset ids/names. $CALL_GET_PRESET_CREATION_ALGORITHM_FIRST"
internal const val GET_PRESET_DESCRIPTION_DESCRIPTION =
    "Describes effective preset capabilities by preset_name with optional preset_id. " +
        CALL_GET_PRESET_CREATION_ALGORITHM_FIRST
internal const val CREATE_PRESET_DESCRIPTION =
    "Creates a new preset with explicit preset_id and preset_name. " +
        CALL_GET_PRESET_CREATION_ALGORITHM_FIRST

private const val SCHEMA_KEY_TYPE = "type"
private const val SCHEMA_KEY_DESCRIPTION = "description"
private const val SCHEMA_KEY_PROPERTIES = "properties"
private const val SCHEMA_KEY_REQUIRED = "required"
private const val SCHEMA_KEY_ITEMS = "items"
private const val SCHEMA_KEY_ENUM = "enum"

private const val SCHEMA_VALUE_OBJECT = "object"
private const val SCHEMA_VALUE_ARRAY = "array"
private const val SCHEMA_VALUE_STRING = "string"
private const val SCHEMA_VALUE_BOOLEAN = "boolean"

private const val FIELD_ID = "id"
private const val FIELD_NAME = "name"
private const val FIELD_PROMPT = "prompt"
private const val FIELD_STEPS = "steps"
private const val FIELD_SERVERS = "servers"
private const val FIELD_PRESETS = "presets"
private const val FIELD_DESCRIPTION = "description"
private const val FIELD_CAPABILITIES_SOURCE = "capabilities_source"
private const val FIELD_TOOLS = "tools"
private const val FIELD_PROMPTS = "prompts"
private const val FIELD_RESOURCES = "resources"
private const val FIELD_ARGUMENTS = "arguments"
private const val FIELD_REQUIRED = "required"
private const val FIELD_KEY = "key"
private const val FIELD_SOURCE_SERVER_ID = "source_server_id"
private const val FIELD_SOURCE_SERVER_NAME = "source_server_name"
private const val FIELD_MISSING_CAPABILITIES = "missing_capabilities"
private const val FIELD_TYPE = "type"

private val toolSelectionItemSchema =
    objectSchema(
        properties =
            buildJsonObject {
                put(ARG_SERVER_ID, stringSchema("Server id containing the tool."))
                put(ARG_TOOL_NAME, stringSchema("Tool name from the selected server."))
            },
        required = listOf(ARG_SERVER_ID, ARG_TOOL_NAME),
    )

private val namedPresetItemSchema =
    objectSchema(
        properties =
            buildJsonObject {
                put(FIELD_ID, stringSchema("Entity id."))
                put(FIELD_NAME, stringSchema("Entity display name."))
            },
        required = listOf(FIELD_ID, FIELD_NAME),
    )

private val capabilityArgumentSchema =
    objectSchema(
        properties =
            buildJsonObject {
                put(FIELD_NAME, stringSchema("Argument name."))
                put(FIELD_TYPE, stringSchema("Argument type hint."))
                put(FIELD_REQUIRED, booleanSchema("Whether argument is required."))
            },
        required = listOf(FIELD_NAME, FIELD_TYPE, FIELD_REQUIRED),
    )

private val toolCapabilitySchema =
    objectSchema(
        properties =
            buildJsonObject {
                put(FIELD_NAME, stringSchema("Tool name."))
                put(FIELD_DESCRIPTION, stringSchema("Tool description."))
                put(FIELD_ARGUMENTS, arraySchema("Tool arguments.", capabilityArgumentSchema))
            },
        required = listOf(FIELD_NAME, FIELD_DESCRIPTION, FIELD_ARGUMENTS),
    )

private val promptCapabilitySchema =
    objectSchema(
        properties =
            buildJsonObject {
                put(FIELD_NAME, stringSchema("Prompt name."))
                put(FIELD_DESCRIPTION, stringSchema("Prompt description."))
                put(FIELD_ARGUMENTS, arraySchema("Prompt arguments.", capabilityArgumentSchema))
            },
        required = listOf(FIELD_NAME, FIELD_DESCRIPTION, FIELD_ARGUMENTS),
    )

private val resourceCapabilitySchema =
    objectSchema(
        properties =
            buildJsonObject {
                put(FIELD_KEY, stringSchema("Resource key used for reads."))
                put(FIELD_NAME, stringSchema("Resource name."))
                put(FIELD_DESCRIPTION, stringSchema("Resource description."))
                put(FIELD_ARGUMENTS, arraySchema("Resource template arguments.", capabilityArgumentSchema))
            },
        required = listOf(FIELD_KEY, FIELD_NAME, FIELD_DESCRIPTION, FIELD_ARGUMENTS),
    )

private val sourcedToolCapabilitySchema =
    objectSchema(
        properties =
            buildJsonObject {
                put(FIELD_NAME, stringSchema("Tool name."))
                put(FIELD_DESCRIPTION, stringSchema("Tool description."))
                put(FIELD_ARGUMENTS, arraySchema("Tool arguments.", capabilityArgumentSchema))
                put(FIELD_SOURCE_SERVER_ID, stringSchema("Source server id."))
                put(FIELD_SOURCE_SERVER_NAME, stringSchema("Source server name."))
            },
        required =
            listOf(
                FIELD_NAME,
                FIELD_DESCRIPTION,
                FIELD_ARGUMENTS,
                FIELD_SOURCE_SERVER_ID,
                FIELD_SOURCE_SERVER_NAME,
            ),
    )

private val sourcedPromptCapabilitySchema =
    objectSchema(
        properties =
            buildJsonObject {
                put(FIELD_NAME, stringSchema("Prompt name."))
                put(FIELD_DESCRIPTION, stringSchema("Prompt description."))
                put(FIELD_ARGUMENTS, arraySchema("Prompt arguments.", capabilityArgumentSchema))
                put(FIELD_SOURCE_SERVER_ID, stringSchema("Source server id."))
                put(FIELD_SOURCE_SERVER_NAME, stringSchema("Source server name."))
            },
        required =
            listOf(
                FIELD_NAME,
                FIELD_DESCRIPTION,
                FIELD_ARGUMENTS,
                FIELD_SOURCE_SERVER_ID,
                FIELD_SOURCE_SERVER_NAME,
            ),
    )

private val sourcedResourceCapabilitySchema =
    objectSchema(
        properties =
            buildJsonObject {
                put(FIELD_KEY, stringSchema("Resource key used for reads."))
                put(FIELD_NAME, stringSchema("Resource name."))
                put(FIELD_DESCRIPTION, stringSchema("Resource description."))
                put(FIELD_ARGUMENTS, arraySchema("Resource template arguments.", capabilityArgumentSchema))
                put(FIELD_SOURCE_SERVER_ID, stringSchema("Source server id."))
                put(FIELD_SOURCE_SERVER_NAME, stringSchema("Source server name."))
            },
        required =
            listOf(
                FIELD_KEY,
                FIELD_NAME,
                FIELD_DESCRIPTION,
                FIELD_ARGUMENTS,
                FIELD_SOURCE_SERVER_ID,
                FIELD_SOURCE_SERVER_NAME,
            ),
    )

private val missingCapabilitySchema =
    objectSchema(
        properties =
            buildJsonObject {
                put(FIELD_TYPE, stringSchema("Capability type."))
                put(FIELD_KEY, stringSchema("Capability key."))
                put(FIELD_SOURCE_SERVER_ID, stringSchema("Source server id."))
                put(FIELD_SOURCE_SERVER_NAME, stringSchema("Source server name, when known."))
            },
        required = listOf(FIELD_TYPE, FIELD_KEY, FIELD_SOURCE_SERVER_ID),
    )

internal val GET_SERVER_DESCRIPTION_INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                put(ARG_SERVER_NAME, stringSchema("Server name to inspect."))
                put(ARG_SERVER_ID, stringSchema("Optional server id to resolve name ambiguity."))
            },
        required = listOf(ARG_SERVER_NAME),
    )

internal val GET_PRESET_DESCRIPTION_INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                put(ARG_PRESET_NAME, stringSchema("Preset name to inspect."))
                put(ARG_PRESET_ID, stringSchema("Optional preset id to resolve name ambiguity."))
            },
        required = listOf(ARG_PRESET_NAME),
    )

internal val CREATE_PRESET_INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                put(ARG_PRESET_ID, stringSchema("New preset id (required, explicit, path-safe)."))
                put(ARG_PRESET_NAME, stringSchema("New preset display name (required)."))
                put(
                    ARG_TOOLS,
                    arraySchema(
                        description = "Non-empty list of tool references to include in the new preset.",
                        items = toolSelectionItemSchema,
                    ),
                )
            },
        required = listOf(ARG_PRESET_ID, ARG_PRESET_NAME, ARG_TOOLS),
    )

internal val GET_PRESET_CREATION_ALGORITHM_OUTPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                put(FIELD_PROMPT, stringSchema("Preset creation workflow prompt."))
                put(FIELD_STEPS, arraySchema("Preset creation workflow steps.", stringSchema("Step text.")))
            },
        required = listOf(FIELD_PROMPT, FIELD_STEPS),
    )

internal val LIST_SERVER_NAMES_OUTPUT_SCHEMA =
    ToolSchema(
        properties = buildJsonObject { put(FIELD_SERVERS, arraySchema("Configured servers.", namedPresetItemSchema)) },
        required = listOf(FIELD_SERVERS),
    )

internal val GET_SERVER_DESCRIPTION_OUTPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                put(ARG_SERVER_ID, stringSchema("Resolved server id."))
                put(ARG_SERVER_NAME, stringSchema("Resolved server name."))
                put(FIELD_DESCRIPTION, stringSchema("Human-readable server capability summary."))
                put(
                    FIELD_CAPABILITIES_SOURCE,
                    enumStringSchema(
                        description = "Source used to build server capabilities.",
                        values = listOf("live", "cached", "missing"),
                    ),
                )
                put(FIELD_TOOLS, arraySchema("Resolved tools.", toolCapabilitySchema))
                put(FIELD_PROMPTS, arraySchema("Resolved prompts.", promptCapabilitySchema))
                put(FIELD_RESOURCES, arraySchema("Resolved resources.", resourceCapabilitySchema))
            },
        required =
            listOf(
                ARG_SERVER_ID,
                ARG_SERVER_NAME,
                FIELD_DESCRIPTION,
                FIELD_CAPABILITIES_SOURCE,
                FIELD_TOOLS,
                FIELD_PROMPTS,
                FIELD_RESOURCES,
            ),
    )

internal val LIST_PRESET_NAMES_OUTPUT_SCHEMA =
    ToolSchema(
        properties = buildJsonObject { put(FIELD_PRESETS, arraySchema("Available presets.", namedPresetItemSchema)) },
        required = listOf(FIELD_PRESETS),
    )

internal val GET_PRESET_DESCRIPTION_OUTPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                put(ARG_PRESET_ID, stringSchema("Resolved preset id."))
                put(ARG_PRESET_NAME, stringSchema("Resolved preset name."))
                put(FIELD_DESCRIPTION, stringSchema("Human-readable effective capability summary."))
                put(FIELD_TOOLS, arraySchema("Effective tools.", sourcedToolCapabilitySchema))
                put(FIELD_PROMPTS, arraySchema("Effective prompts.", sourcedPromptCapabilitySchema))
                put(FIELD_RESOURCES, arraySchema("Effective resources.", sourcedResourceCapabilitySchema))
                put(
                    FIELD_MISSING_CAPABILITIES,
                    arraySchema("Preset references that cannot be resolved.", missingCapabilitySchema),
                )
            },
        required =
            listOf(
                ARG_PRESET_ID,
                ARG_PRESET_NAME,
                FIELD_DESCRIPTION,
                FIELD_TOOLS,
                FIELD_PROMPTS,
                FIELD_RESOURCES,
                FIELD_MISSING_CAPABILITIES,
            ),
    )

internal val CREATE_PRESET_OUTPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                put(ARG_PRESET_ID, stringSchema("Created preset id."))
                put(ARG_PRESET_NAME, stringSchema("Created preset name."))
            },
        required = listOf(ARG_PRESET_ID, ARG_PRESET_NAME),
    )

private fun stringSchema(description: String): JsonObject =
    buildJsonObject {
        put(SCHEMA_KEY_TYPE, JsonPrimitive(SCHEMA_VALUE_STRING))
        put(SCHEMA_KEY_DESCRIPTION, JsonPrimitive(description))
    }

private fun booleanSchema(description: String): JsonObject =
    buildJsonObject {
        put(SCHEMA_KEY_TYPE, JsonPrimitive(SCHEMA_VALUE_BOOLEAN))
        put(SCHEMA_KEY_DESCRIPTION, JsonPrimitive(description))
    }

private fun enumStringSchema(
    description: String,
    values: List<String>,
): JsonObject =
    buildJsonObject {
        put(SCHEMA_KEY_TYPE, JsonPrimitive(SCHEMA_VALUE_STRING))
        put(SCHEMA_KEY_DESCRIPTION, JsonPrimitive(description))
        put(SCHEMA_KEY_ENUM, requiredValues(values))
    }

private fun arraySchema(
    description: String,
    items: JsonObject,
): JsonObject =
    buildJsonObject {
        put(SCHEMA_KEY_TYPE, JsonPrimitive(SCHEMA_VALUE_ARRAY))
        put(SCHEMA_KEY_DESCRIPTION, JsonPrimitive(description))
        put(SCHEMA_KEY_ITEMS, items)
    }

private fun objectSchema(
    properties: JsonObject,
    required: List<String>,
): JsonObject =
    buildJsonObject {
        put(SCHEMA_KEY_TYPE, JsonPrimitive(SCHEMA_VALUE_OBJECT))
        put(SCHEMA_KEY_PROPERTIES, properties)
        put(SCHEMA_KEY_REQUIRED, requiredValues(required))
    }

private fun requiredValues(fields: List<String>): JsonArray = JsonArray(fields.map(::JsonPrimitive))
