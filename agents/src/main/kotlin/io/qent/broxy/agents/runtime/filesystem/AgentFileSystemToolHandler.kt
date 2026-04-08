package io.qent.broxy.agents.runtime.filesystem

import dev.langchain4j.agent.tool.ToolSpecification
import kotlinx.serialization.json.JsonObject

internal interface AgentFileSystemToolHandler {
    val name: String

    fun specification(): ToolSpecification

    fun execute(arguments: JsonObject): JsonObject
}
