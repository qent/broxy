package io.qent.broxy.core.proxy.inbound

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredPrompt
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredResource
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server

internal fun syncTools(
    server: Server,
    desired: List<RegisteredTool>,
) {
    val desiredByName = desired.associateBy { it.tool.name }
    val currentByName = server.tools
    val toRemove = currentByName.keys - desiredByName.keys
    if (toRemove.isNotEmpty()) {
        server.removeTools(toRemove.toList())
    }
    val toAddOrUpdate =
        desiredByName.values.filter { desiredTool ->
            val current = currentByName[desiredTool.tool.name]
            current == null || current.tool != desiredTool.tool
        }
    if (toAddOrUpdate.isNotEmpty()) {
        server.addTools(toAddOrUpdate)
    }
}

internal fun syncPrompts(
    server: Server,
    desired: List<RegisteredPrompt>,
) {
    val desiredByName = desired.associateBy { it.prompt.name }
    val currentByName = server.prompts
    val toRemove = currentByName.keys - desiredByName.keys
    if (toRemove.isNotEmpty()) {
        server.removePrompts(toRemove.toList())
    }
    val toAddOrUpdate =
        desiredByName.values.filter { desiredPrompt ->
            val current = currentByName[desiredPrompt.prompt.name]
            current == null || current.prompt != desiredPrompt.prompt
        }
    if (toAddOrUpdate.isNotEmpty()) {
        server.addPrompts(toAddOrUpdate)
    }
}

internal fun syncResources(
    server: Server,
    desired: List<RegisteredResource>,
) {
    val desiredByUri = desired.associateBy { it.resource.uri }
    val currentByUri = server.resources
    val toRemove = currentByUri.keys - desiredByUri.keys
    if (toRemove.isNotEmpty()) {
        server.removeResources(toRemove.toList())
    }
    val toAddOrUpdate =
        desiredByUri.values.filter { desiredResource ->
            val current = currentByUri[desiredResource.resource.uri]
            current == null || current.resource != desiredResource.resource
        }
    if (toAddOrUpdate.isNotEmpty()) {
        server.addResources(toAddOrUpdate)
    }
}
