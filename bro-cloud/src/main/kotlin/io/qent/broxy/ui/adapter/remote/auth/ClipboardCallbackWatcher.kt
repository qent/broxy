package io.qent.broxy.ui.adapter.remote.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.net.URI

class ClipboardCallbackWatcher(
    private val expectedState: String,
    private val timeoutMillis: Long = 120_000,
) {
    suspend fun await(): OAuthCallback? =
        withContext<OAuthCallback?>(Dispatchers.IO) {
            val clipboard =
                runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()
                    ?: return@withContext null
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                val text = runCatching { clipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
                val callback = text?.let { parse(it.trim()) }
                if (callback != null) return@withContext callback
                delay(1_000)
            }
            null
        }

    private fun parse(raw: String): OAuthCallback? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val params = uri.query?.let { parseQuery(it) } ?: return null
        val code = params["code"]
        val state = params["state"]
        if (code.isNullOrBlank() || state.isNullOrBlank()) return null
        if (state != expectedState) return null
        return OAuthCallback(code = code, state = state)
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = urlDecode(pair.substring(0, idx))
            val value = urlDecode(pair.substring(idx + 1))
            key to value
        }.toMap()

    private fun urlDecode(raw: String): String =
        runCatching {
            java.net.URLDecoder.decode(raw, Charsets.UTF_8)
        }.getOrDefault(raw)
}
