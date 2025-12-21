package io.qent.broxy.core.mcp.clients

import io.qent.broxy.core.mcp.auth.OAuthAuthorizer
import io.qent.broxy.core.mcp.auth.OAuthChallenge
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtorMcpClientAuthFlowTest {
    private class FakeAuthorizer : OAuthAuthorizer {
        var ensureCount = 0

        override fun currentAccessToken(): String? = null

        override suspend fun ensureAuthorized(challenge: OAuthChallenge?): Result<String?> {
            ensureCount += 1
            return Result.success("token")
        }

        override fun close() = Unit
    }

    @Test
    fun connect_preauthorizes_auto_oauth_before_connector() {
        runBlocking {
            val authorizer = FakeAuthorizer()
            val facade: SdkClientFacade = mock()
            val client =
                KtorMcpClient(
                    mode = KtorMcpClient.Mode.StreamableHttp,
                    url = "http://localhost",
                    connector = SdkConnector { facade },
                    oauthAuthorizerFactory = { _, _, _, _ -> authorizer },
                    preauthorizeWithConnector = true,
                )

            val result = client.connect()

            assertTrue(result.isSuccess)
            assertEquals(1, authorizer.ensureCount)
        }
    }
}
