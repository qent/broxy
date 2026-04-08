package io.qent.broxy.core.mcp.clients

import io.qent.broxy.core.mcp.PromptDescriptor
import io.qent.broxy.core.mcp.ResourceDescriptor
import io.qent.broxy.core.mcp.ToolDescriptor
import io.qent.broxy.core.utils.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

internal class CapabilityFetcher(
    private val logger: Logger,
) {
    private companion object {
        private const val NANOS_PER_MILLI = 1_000_000
    }

    suspend fun fetch(
        client: SdkClientFacade,
        timeoutMillis: Long,
    ): Triple<List<ToolDescriptor>, List<ResourceDescriptor>, List<PromptDescriptor>> =
        coroutineScope {
            val toolsDeferred =
                async { listWithTimeout("listTools", timeoutMillis, emptyList()) { client.getTools() } }
            val resourcesDeferred =
                async { listWithTimeout("listResources", timeoutMillis, emptyList()) { client.getResources() } }
            val promptsDeferred =
                async { listWithTimeout("listPrompts", timeoutMillis, emptyList()) { client.getPrompts() } }
            Triple(toolsDeferred.await(), resourcesDeferred.await(), promptsDeferred.await())
        }

    private suspend fun <T> listWithTimeout(
        operation: String,
        timeoutMillis: Long,
        defaultValue: T,
        fetch: suspend () -> T,
    ): T {
        val startNanos = System.nanoTime()
        return runCatching {
            withTimeout(timeoutMillis) { fetch() }
        }.onSuccess {
            val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
            logger.debug("$operation completed in ${elapsedMs}ms")
        }.onFailure { ex ->
            val kind =
                if (ex is TimeoutCancellationException) {
                    "timed out after ${timeoutMillis}ms"
                } else {
                    ex.message
                        ?: ex::class.simpleName
                }
            val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
            logger.warn("$operation $kind after ${elapsedMs}ms; treating as empty.", ex)
        }.getOrDefault(defaultValue)
    }
}
