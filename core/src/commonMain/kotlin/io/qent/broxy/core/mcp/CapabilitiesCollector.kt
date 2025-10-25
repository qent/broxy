package io.qent.broxy.core.mcp

import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal suspend fun collectCapabilities(
    servers: List<McpServerConnection>,
    onFailure: (serverId: String, error: Throwable?) -> Unit = { _, _ -> },
): Map<String, ServerCapabilities> =
    supervisorScope {
        if (servers.isEmpty()) return@supervisorScope emptyMap()
        val mutex = Mutex()
        val results = mutableMapOf<String, ServerCapabilities>()
        val jobs =
            servers.map { server ->
                launch {
                    val caps =
                        runCatching { server.getCapabilities() }
                            .getOrElse { Result.failure(it) }
                    if (caps.isSuccess) {
                        val value = caps.getOrThrow()
                        mutex.withLock {
                            results[server.serverId] = value
                        }
                    } else {
                        onFailure(server.serverId, caps.exceptionOrNull())
                    }
                }
            }
        jobs.joinAll()
        results.toMap()
    }
