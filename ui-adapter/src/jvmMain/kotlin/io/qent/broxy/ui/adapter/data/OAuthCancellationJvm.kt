package io.qent.broxy.ui.adapter.data

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

actual fun signalOAuthCancellation(redirectUri: String): Result<Unit> =
    runCatching {
        val uri = URI(redirectUri)
        val errorDesc = URLEncoder.encode("cancelled_by_user", StandardCharsets.UTF_8)
        val cancelUri =
            URI(
                uri.scheme,
                null,
                uri.host,
                uri.port,
                uri.path,
                "error=access_denied&error_description=$errorDesc",
                null,
            )
        val connection = cancelUri.toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.inputStream.use { it.readBytes() }
        connection.disconnect()
    }
