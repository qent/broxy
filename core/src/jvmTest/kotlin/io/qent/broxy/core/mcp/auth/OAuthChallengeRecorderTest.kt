package io.qent.broxy.core.mcp.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OAuthChallengeRecorderTest {
    @Test
    fun record_captures_bearer_challenge_from_unauthorized_response() {
        val engine =
            MockEngine {
                respond(
                    content = "",
                    status = HttpStatusCode.Unauthorized,
                    headers =
                        headersOf(
                            HttpHeaders.WWWAuthenticate,
                            "Bearer resource_metadata=\"https://mcp.example.com/metadata\"",
                        ),
                )
            }
        val client = HttpClient(engine)
        try {
            runBlocking {
                val response = client.get("http://localhost/test")
                val recorder = OAuthChallengeRecorder()

                recorder.record(response)

                val challenge = recorder.consume()
                assertEquals(401, challenge?.statusCode)
                assertEquals("https://mcp.example.com/metadata", challenge?.resourceMetadataUrl)
                assertNull(recorder.consume())
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun record_ignores_non_auth_responses() {
        val engine =
            MockEngine {
                respond(content = "", status = HttpStatusCode.OK)
            }
        val client = HttpClient(engine)
        try {
            runBlocking {
                val response = client.get("http://localhost/test")
                val recorder = OAuthChallengeRecorder()

                recorder.record(response)

                assertNull(recorder.consume())
            }
        } finally {
            client.close()
        }
    }
}
