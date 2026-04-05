package io.qent.broxy.core.mcp.auth

import kotlinx.coroutines.CompletableDeferred

/**
 * Coordinates STDIO bootstrap authorization popup lifecycle between core and UI layers.
 * A session is opened before showing the popup and completed when the popup is dismissed.
 */
object AuthorizationPopupSessionRegistry {
    class SessionHandle internal constructor(
        val resourceUrl: String,
        internal val id: Long,
    )

    private data class SessionState(
        val id: Long,
        val completed: CompletableDeferred<Unit>,
    )

    private val lock = Any()
    private val sessions = mutableMapOf<String, SessionState>()
    private var nextId = 0L

    fun open(resourceUrl: String): SessionHandle {
        val completed = CompletableDeferred<Unit>()
        val state =
            synchronized(lock) {
                sessions.remove(resourceUrl)?.completed?.complete(Unit)
                val id = ++nextId
                SessionState(id = id, completed = completed).also {
                    sessions[resourceUrl] = it
                }
            }
        return SessionHandle(resourceUrl = resourceUrl, id = state.id)
    }

    suspend fun await(handle: SessionHandle) {
        val deferred =
            synchronized(lock) {
                sessions[handle.resourceUrl]
                    ?.takeIf { it.id == handle.id }
                    ?.completed
            } ?: return
        deferred.await()
    }

    fun complete(resourceUrl: String) {
        val deferred =
            synchronized(lock) {
                sessions.remove(resourceUrl)?.completed
            }
        deferred?.complete(Unit)
    }

    fun complete(handle: SessionHandle) {
        val deferred =
            synchronized(lock) {
                val current = sessions[handle.resourceUrl]
                if (current != null && current.id == handle.id) {
                    sessions.remove(handle.resourceUrl)?.completed
                } else {
                    null
                }
            }
        deferred?.complete(Unit)
    }
}
