package io.qent.broxy.core.mcp.tls

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIOEngineConfig
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

private typealias CioClientConfig = HttpClientConfig<CIOEngineConfig>

internal fun CioClientConfig.configureCioCertificateValidation(ignoreHttpsCertificateErrors: Boolean) {
    if (!ignoreHttpsCertificateErrors) return
    engine {
        https {
            trustManager = TrustAllX509TrustManager
        }
    }
}

private object TrustAllX509TrustManager : X509TrustManager {
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
