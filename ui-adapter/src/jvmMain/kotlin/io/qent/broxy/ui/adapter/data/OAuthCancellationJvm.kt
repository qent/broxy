package io.qent.broxy.ui.adapter.data

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

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
        val connection = openCancellationConnection(uri, cancelUri)
        connection.requestMethod = "GET"
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.inputStream.use { it.readBytes() }
        connection.disconnect()
    }

private fun openCancellationConnection(
    redirectUri: URI,
    cancelUri: URI,
): HttpURLConnection {
    val connection = cancelUri.toURL().openConnection() as HttpURLConnection
    val scheme = redirectUri.scheme?.lowercase(Locale.ROOT)
    val host = redirectUri.host?.lowercase(Locale.ROOT)
    val isLoopbackHost = host == "localhost" || host == "127.0.0.1"
    if (scheme == "https" && isLoopbackHost && connection is HttpsURLConnection) {
        connection.sslSocketFactory = LoopbackTlsContext.socketFactory
        connection.hostnameVerifier =
            HostnameVerifier { hostname, _ ->
                hostname.equals("localhost", ignoreCase = true) || hostname == "127.0.0.1"
            }
    }
    return connection
}

private object LoopbackTlsContext {
    val socketFactory =
        SSLContext
            .getInstance("TLS")
            .apply {
                init(null, arrayOf(TrustAllLoopbackCertificates), SecureRandom())
            }.socketFactory
}

private object TrustAllLoopbackCertificates : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
