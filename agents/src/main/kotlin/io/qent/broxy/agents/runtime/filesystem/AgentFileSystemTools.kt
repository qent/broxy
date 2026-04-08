package io.qent.broxy.agents.runtime.filesystem

import dev.langchain4j.agent.tool.ToolSpecification
import io.qent.broxy.agents.AgentFileSystemAccess
import kotlinx.serialization.json.JsonObject

internal data class AgentFileSystemExecution(
    val payload: String,
    val ok: Boolean,
    val code: String? = null,
)

internal class AgentFileSystemTools(
    workspace: AgentFileSystemWorkspace,
) {
    private val payloadCodec = AgentFileSystemPayloadCodec()
    private val handlers = buildHandlers(workspace)

    fun specifications(access: AgentFileSystemAccess): Map<String, ToolSpecification> {
        val orderedNames =
            when (access) {
                AgentFileSystemAccess.NONE -> emptyList()
                AgentFileSystemAccess.READ_ONLY ->
                    listOf(
                        AgentFileSystemToolNames.INSPECT,
                        AgentFileSystemToolNames.READ,
                        AgentFileSystemToolNames.SEARCH,
                    )

                AgentFileSystemAccess.READ_WRITE ->
                    listOf(
                        AgentFileSystemToolNames.INSPECT,
                        AgentFileSystemToolNames.READ,
                        AgentFileSystemToolNames.SEARCH,
                        AgentFileSystemToolNames.EDIT,
                    )
            }
        return orderedNames.associateWithTo(linkedMapOf()) { toolName ->
            checkNotNull(handlers[toolName]) { "Unknown tool handler for '$toolName'" }.specification()
        }
    }

    fun execute(
        toolName: String,
        arguments: JsonObject,
    ): AgentFileSystemExecution =
        runCatching {
            val handler =
                handlers[toolName]
                    ?: throw AgentFileSystemException(
                        code = "invalid_argument",
                        message = "Unknown filesystem tool: $toolName",
                    )
            val data = handler.execute(arguments)
            AgentFileSystemExecution(payload = payloadCodec.success(data), ok = true)
        }.getOrElse { failure ->
            val normalized = payloadCodec.normalizeFailure(failure)
            AgentFileSystemExecution(
                payload = payloadCodec.error(normalized.code, normalized.message, normalized.hint),
                ok = false,
                code = normalized.code,
            )
        }

    private fun buildHandlers(workspace: AgentFileSystemWorkspace): Map<String, AgentFileSystemToolHandler> {
        val pathMetadata = AgentFileSystemPathMetadata()
        val textGuard = AgentFileSystemTextGuard()
        val lineEditEngine = AgentFileSystemLineEditEngine()

        val inspectHandler = AgentFileSystemInspectHandler(workspace = workspace, pathMetadata = pathMetadata)
        val readHandler = AgentFileSystemReadHandler(workspace = workspace, textGuard = textGuard)
        val searchHandler =
            AgentFileSystemSearchHandler(
                workspace = workspace,
                textGuard = textGuard,
                pathMetadata = pathMetadata,
            )
        val editHandler =
            AgentFileSystemEditHandler(
                workspace = workspace,
                textGuard = textGuard,
                lineEditEngine = lineEditEngine,
            )

        return linkedMapOf(
            inspectHandler.name to inspectHandler,
            readHandler.name to readHandler,
            searchHandler.name to searchHandler,
            editHandler.name to editHandler,
        )
    }
}
