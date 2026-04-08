package io.qent.broxy.agents.codex.runtime

import io.qent.broxy.agents.AgentExecutionOperation
import io.qent.broxy.agents.AgentRunActionEntry
import io.qent.broxy.agents.AgentRunActionType
import io.qent.broxy.agents.AgentRunDialogueEntry
import io.qent.broxy.agents.AgentRunDialogueRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Suppress("LongMethod", "LongParameterList")
internal class CodexJsonlEventMapper(
    private val json: Json = Json,
) {
    fun consumeLine(
        line: String,
        state: CodexEventStreamState,
        onOperation: (AgentExecutionOperation) -> Unit,
        onTraceDialogue: (AgentRunDialogueEntry) -> Unit,
        onTraceAction: (AgentRunActionEntry) -> Unit,
    ) {
        val event = parseEvent(line) ?: return
        when (event.type) {
            "turn.started" -> handleTurnStarted(state, onOperation, onTraceAction)
            "item.started", "item.updated", "item.completed" ->
                handleItemEvent(
                    event = event,
                    state = state,
                    onOperation = onOperation,
                    onTraceDialogue = onTraceDialogue,
                    onTraceAction = onTraceAction,
                )
            "turn.failed" -> {
                val message = event.errorMessage?.takeIf { it.isNotBlank() } ?: "Codex turn failed"
                error(message)
            }
            "error" -> {
                val message = event.errorMessage?.takeIf { it.isNotBlank() } ?: "Codex execution failed"
                error(message)
            }
        }
    }

    private fun handleTurnStarted(
        state: CodexEventStreamState,
        onOperation: (AgentExecutionOperation) -> Unit,
        onTraceAction: (AgentRunActionEntry) -> Unit,
    ) {
        state.step += 1
        val step = state.step
        onOperation(AgentExecutionOperation.LlmRequest(step = step))
        onOperation(AgentExecutionOperation.LlmThinking(step = step))
        val now = System.currentTimeMillis()
        onTraceAction(
            AgentRunActionEntry(
                type = AgentRunActionType.LLM_REQUEST,
                step = step,
                timestampEpochMillis = now,
            ),
        )
        onTraceAction(
            AgentRunActionEntry(
                type = AgentRunActionType.LLM_THINKING,
                step = step,
                timestampEpochMillis = now,
            ),
        )
    }

    private fun handleItemEvent(
        event: ParsedCodexEvent,
        state: CodexEventStreamState,
        onOperation: (AgentExecutionOperation) -> Unit,
        onTraceDialogue: (AgentRunDialogueEntry) -> Unit,
        onTraceAction: (AgentRunActionEntry) -> Unit,
    ) {
        val item = event.item ?: return
        val itemType = item.stringValueOrNull("type").orEmpty()
        when (itemType) {
            "mcp_tool_call" -> {
                val toolInfo = emitToolOperation(item, state, onOperation, onTraceAction)
                if (event.type == "item.completed") {
                    emitToolResult(
                        item = item,
                        state = state,
                        onOperation = onOperation,
                        onTraceDialogue = onTraceDialogue,
                        onTraceAction = onTraceAction,
                        fallbackServer = toolInfo.serverId,
                        fallbackTool = toolInfo.toolName,
                    )
                }
            }
            "mcp_tool_result" -> {
                if (event.type == "item.completed") {
                    emitToolResult(
                        item = item,
                        state = state,
                        onOperation = onOperation,
                        onTraceDialogue = onTraceDialogue,
                        onTraceAction = onTraceAction,
                        fallbackServer = "unknown",
                        fallbackTool = "unknown",
                    )
                }
            }
            "agent_message" -> {
                if (event.type == "item.completed") {
                    val text = item.stringValueOrNull("text").orEmpty()
                    if (text.isNotBlank()) {
                        state.finalResponse = text
                        val step = state.step.coerceAtLeast(1)
                        val now = System.currentTimeMillis()
                        onOperation(AgentExecutionOperation.LlmResponseGeneration(step = step))
                        onTraceAction(
                            AgentRunActionEntry(
                                type = AgentRunActionType.LLM_RESPONSE_GENERATION,
                                step = step,
                                timestampEpochMillis = now,
                            ),
                        )
                        onTraceDialogue(
                            AgentRunDialogueEntry(
                                role = AgentRunDialogueRole.ASSISTANT,
                                content = text,
                                step = step,
                                timestampEpochMillis = now,
                            ),
                        )
                    }
                }
            }
            "error" -> {
                if (event.type == "item.completed") {
                    val message = item.errorMessageOrNull("message").orEmpty()
                    if (message.isNotBlank()) {
                        error(message)
                    }
                }
            }
        }
    }

    private fun emitToolOperation(
        item: JsonObject,
        state: CodexEventStreamState,
        onOperation: (AgentExecutionOperation) -> Unit,
        onTraceAction: (AgentRunActionEntry) -> Unit,
    ): ToolInfo {
        val itemId = item.stringValueOrNull("id").orEmpty()
        val shouldEmit = itemId.isBlank() || state.seenToolCallIds.add(itemId)
        val server = item.stringValue("server", fallback = "unknown")
        val tool = item.stringValue("tool", fallback = "unknown")
        if (!shouldEmit) {
            return ToolInfo(server, tool)
        }
        val step = state.step.coerceAtLeast(1)
        val now = System.currentTimeMillis()
        onOperation(
            AgentExecutionOperation.ToolExecution(
                serverId = server,
                toolName = tool,
                step = step,
            ),
        )
        onTraceAction(
            AgentRunActionEntry(
                type = AgentRunActionType.TOOL_CALL,
                step = step,
                serverId = server,
                toolName = tool,
                requestPayload = item.toString(),
                timestampEpochMillis = now,
            ),
        )
        return ToolInfo(server, tool)
    }

    private fun emitToolResult(
        item: JsonObject,
        state: CodexEventStreamState,
        onOperation: (AgentExecutionOperation) -> Unit,
        onTraceDialogue: (AgentRunDialogueEntry) -> Unit,
        onTraceAction: (AgentRunActionEntry) -> Unit,
        fallbackServer: String,
        fallbackTool: String,
    ) {
        val responsePayload =
            item["result"]?.toString()
                ?: item["output"]?.toString()
                ?: item.stringValueOrNull("text")
                ?: item.toString()
        val errorMessage = item.errorMessageOrNull("error")
        val now = System.currentTimeMillis()
        val step = state.step.coerceAtLeast(1)
        val server = item.stringValue("server", fallbackServer)
        val tool = item.stringValue("tool", fallbackTool)

        onTraceAction(
            AgentRunActionEntry(
                type = AgentRunActionType.TOOL_RESULT,
                step = step,
                serverId = server,
                toolName = tool,
                responsePayload = responsePayload,
                errorMessage = errorMessage,
                timestampEpochMillis = now,
            ),
        )
        onTraceDialogue(
            AgentRunDialogueEntry(
                role = AgentRunDialogueRole.TOOL,
                content = responsePayload,
                step = step,
                serverId = server,
                toolName = tool,
                timestampEpochMillis = now,
            ),
        )
        onOperation(AgentExecutionOperation.LlmThinking(step = step))
    }

    private fun parseEvent(line: String): ParsedCodexEvent? {
        val payload = runCatching { json.parseToJsonElement(line) }.getOrNull()?.asJsonObjectOrNull()
        val type = payload?.stringValueOrNull("type")
        if (payload == null || type == null) {
            return null
        }
        return ParsedCodexEvent(
            type = type,
            item = payload.objectValueOrNull("item"),
            errorMessage =
                when (type) {
                    "turn.failed" -> payload.errorMessageOrNull("error")
                    "error" -> payload.errorMessageOrNull("message") ?: payload.errorMessageOrNull("error")
                    else -> null
                },
        )
    }
}

private data class ToolInfo(
    val serverId: String,
    val toolName: String,
)

private fun JsonObject.stringValue(
    key: String,
    fallback: String,
): String =
    get(key)
        ?.let { value ->
            when (value) {
                is JsonPrimitive -> value.contentOrNull
                else -> value.toString()
            }
        }.orEmpty()
        .ifBlank { fallback }

private fun JsonObject.stringValueOrNull(key: String): String? = get(key).asStringOrNull()

private fun JsonObject.objectValueOrNull(key: String): JsonObject? = get(key).asJsonObjectOrNull()

private fun JsonObject.errorMessageOrNull(key: String): String? = get(key).errorMessageOrNull()

private fun JsonElement?.errorMessageOrNull(): String? {
    val value = this ?: return null
    val direct = value.asStringOrNull()?.takeIf { it.isNotBlank() }
    val message = value.asJsonObjectOrNull()?.stringValueOrNull("message")?.takeIf { it.isNotBlank() }
    val fallback = value.toString().takeIf { it.isNotBlank() }
    return direct ?: message ?: fallback
}

private fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

internal data class CodexEventStreamState(
    var step: Int = 0,
    var finalResponse: String = "",
    val seenToolCallIds: MutableSet<String> = linkedSetOf(),
)
